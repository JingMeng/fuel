package com.github.kittinunf.fuel.core.requests

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.Client
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.core.HttpException
import com.github.kittinunf.fuel.core.InterruptCallback
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.RequestExecutionOptions
import com.github.kittinunf.fuel.core.Response
import java.util.concurrent.Callable

private typealias RequestTaskResult = Pair<Request, Response>

/**
 * Synchronous version of [SuspendableRequest]. Turns a [Request] into a [Callable]
 */
internal class RequestTask(internal val request: Request) : Callable<Response> {
    private val interruptCallback: InterruptCallback by lazy { executor.interruptCallback }
    private val executor: RequestExecutionOptions by lazy { request.executionOptions }
    private val client: Client by lazy { executor.client }

    private fun prepareRequest(request: Request): Request = executor.requestTransformer(request)

    @Throws(FuelError::class)
    private fun executeRequest(request: Request): RequestTaskResult {
        return runCatching { Pair(request, client.executeRequest(request)) }
            .recover { error -> throw FuelError.wrap(error, Response(request.url)) }
            .getOrThrow()
    }

    @Throws(FuelError::class)
    private fun prepareResponse(result: RequestTaskResult): Response {
        val (request, response) = result
        /**
         * DownloadRequest 的 transformResponse 就是从这里被调用了，直接写入到了文件中了
         */
        return runCatching { executor.responseTransformer(request, response) }
            .mapCatching { transformedResponse ->
                val valid = executor.responseValidator(transformedResponse)
                if (valid) transformedResponse
                else throw FuelError.wrap(
                    HttpException(
                        transformedResponse.statusCode,
                        transformedResponse.responseMessage
                    ), transformedResponse
                )
            }
            .recover { error -> throw FuelError.wrap(error, response) }
            .getOrThrow()
    }

    @Throws(FuelError::class)
    override fun call(): Response {
        return runCatching { prepareRequest(request) }
            //主要业务
            .mapCatching { executeRequest(it) }
            /**
             * private typealias RequestTaskResult = Pair<Request, Response>
             *
             *  RequestTaskResult  这个就是一个 Pair 对象，所以说 这个地方地方定义了 pair
             *
             *  这个参数也是符合  ResponseTransformer 以及  StreamDestinationCallback
             *
             *  在  DownloadRequest 中有相关的认证
             *
             */
            .mapCatching { pair ->
                // Nested runCatching so response can be rebound
                runCatching { prepareResponse(pair) }
                    .recover { error ->
                        error.also { Fuel.trace { "[RequestTask] execution error\n\r\t$error" } }
                        throw FuelError.wrap(error, pair.second)
                    }
                    .getOrThrow()
            }
            .onFailure { error ->
                Fuel.trace { "[RequestTask] on failure (interrupted=${(error as? FuelError)?.causedByInterruption ?: error})" }
                if (error is FuelError && error.causedByInterruption) {
                    Fuel.trace { "[RequestTask] execution error\n\r\t$error" }
                    interruptCallback.invoke(request)
                }
            }
            .getOrThrow()
    }
}

internal fun Request.toTask(): Callable<Response> = RequestTask(this)
