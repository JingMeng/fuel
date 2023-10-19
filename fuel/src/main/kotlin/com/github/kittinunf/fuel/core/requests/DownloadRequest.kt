package com.github.kittinunf.fuel.core.requests

import com.github.kittinunf.fuel.core.ProgressCallback
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL

typealias LegacyDestinationCallback = (Response, URL) -> File
typealias FileDestinationCallback = (Response, Request) -> File
typealias StreamDestinationCallback = (Response, Request) -> Pair<OutputStream, DestinationAsStreamCallback>
typealias DestinationAsStreamCallback = () -> InputStream

class DownloadRequest private constructor(private val wrapped: Request) : Request by wrapped {
    override val request: DownloadRequest = this
    override fun toString() = "Download[\n\r\t$wrapped\n\r]"

    //最终就是初始化了这个对象
    private lateinit var destinationCallback: StreamDestinationCallback

    init {
        //也是复写了 plusAssign 这个参数
        executionOptions += this::transformResponse
    }

    @Deprecated("Use fileDestination with (Request, Response) -> File")
    fun destination(destination: LegacyDestinationCallback) =
        fileDestination { response: Response, request: Request ->
            destination(
                response,
                request.url
            )
        }

    /**
     * Set the destination callback
     *
     * @note destination *MUST* be writable or this may or may not silently fail, dependent on the JVM/engine this is
     *   called on. For example, Android silently fails and hangs if used an inaccessible temporary directory, which
     *   is syntactically valid.
     *
     * @param destination [FileDestinationCallback] callback called with the [Response] and [Request]
     * @return [DownloadRequest] self
     */
    fun fileDestination(destination: FileDestinationCallback) =
        /**
         * 最外面的传递
         * typealias FileDestinationCallback = (Response, Request) -> File
         *
         * typealias StreamDestinationCallback = (Response, Request) -> Pair<OutputStream, DestinationAsStreamCallback>
         *     这个是结果
         *  typealias DestinationAsStreamCallback = () -> InputStream
         *
         *  这个 {}的高阶函数，你看不太懂，也就是 DestinationAsStreamCallback的使用
         */
        streamDestination { response: Response, request: Request ->
            destination(response, request).let { file ->
//                Pair(
//                    FileOutputStream(file),
//                    { FileInputStream(file) })

                //上面的那种写法不熟悉，后面的这种写法就熟悉了
                Pair(
                    FileOutputStream(file)
                ) { FileInputStream(file) }
            }
        }

    /**
     * Set the destination callback
     *
     * @note with the current implementation, the stream will be CLOSED after the body has been written to it.
     *
     * @param destination [StreamDestinationCallback] callback called with the [Response] and [Request]
     * @return []
     */
    fun streamDestination(destination: StreamDestinationCallback): DownloadRequest {
        destinationCallback = destination
        return request
    }

    fun progress(progress: ProgressCallback) = responseProgress(progress)


    /**
     * 这个就是  ResponseTransformer 一个实现
     *
     * 在参数化的时候是：
     *
     * var responseTransformer: ResponseTransformer
     */
    private fun transformResponse(request: Request, response: Response): Response {
        //typealias StreamDestinationCallback = (Response, Request) -> Pair<OutputStream, DestinationAsStreamCallback>
        //typealias DestinationAsStreamCallback = () -> InputStream
        val (output, inputCallback) = this.destinationCallback(response, request)

        /**
         *分开看，不要在一起看
         * outputStream  写出
         *
         * inputStream 读入
         *
         * FileInputStream 从文件系统中的某个文件中获得输入字节。
         *
         * 这个我记得是针对内存而言的读写
         *
         * https://blog.csdn.net/lyb1832567496/article/details/52712218
         */
        output.use { outputStream ->
            response.body.toStream().use { inputStream ->
                inputStream.copyTo(out = outputStream)
            }
        }

        // This allows the stream to be written to disk first and then return the written file.
        // We can not calculate the length here because inputCallback might not return the actual output as we write it.
        /**
         * 这个body有什么用呢 ？
         *
         * 这允许先将流写入磁盘，然后返回写入的文件。
         *
         * 我们不能在这里计算长度，因为inputCallback在编写时可能不会返回实际输出。
         *
         * inputCallback  还是一个流
         *
         * typealias BodySource = (() -> InputStream)
         */
        return response.copy(body = DefaultBody.from(inputCallback, null))
    }

    companion object {
        val FEATURE: String = DownloadRequest::class.java.canonicalName
        fun enableFor(request: Request) = request.enabledFeatures
            .getOrPut(FEATURE) { DownloadRequest(request) } as DownloadRequest
    }
}

fun Request.download(): DownloadRequest = DownloadRequest.enableFor(this)
