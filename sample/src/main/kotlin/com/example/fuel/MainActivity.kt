package com.example.fuel

import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.Method
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.core.FileDataPart
import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.core.ResponseDeserializable
import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.core.extensions.cUrlString
import com.github.kittinunf.fuel.coroutines.awaitStringResponseResult
import com.github.kittinunf.fuel.gson.responseObject
import com.github.kittinunf.fuel.httpDelete
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.httpPut
import com.github.kittinunf.fuel.livedata.liveDataObject
import com.github.kittinunf.fuel.rx.rxObject
import com.github.kittinunf.fuel.stetho.StethoHook
import com.github.kittinunf.result.Result
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.mainAuxText
import kotlinx.android.synthetic.main.activity_main.mainClearButton
import kotlinx.android.synthetic.main.activity_main.mainGoButton
import kotlinx.android.synthetic.main.activity_main.mainGoCoroutineButton
import kotlinx.android.synthetic.main.activity_main.mainResultText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.Reader

class MainActivity : AppCompatActivity() {

    private val TAG = MainActivity::class.java.simpleName

    private val bag by lazy { CompositeDisposable() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FuelManager.instance.apply {
            basePath = "http://httpbin.org"
            baseHeaders = mapOf("Device" to "Android")
            baseParams = listOf("key" to "value")
            hook = StethoHook("Fuel Sample App")
//            addResponseInterceptor { loggingResponseInterceptor() }
        }

        mainGoCoroutineButton.setOnClickListener {
            GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
                executeCoroutine()
            }
        }

        mainGoButton.setOnClickListener {
            httpGsonListResponseObject()
//            execute()
        }


        mainClearButton.setOnClickListener {
            mainResultText.text = ""
            mainAuxText.text = ""
        }
    }

    private fun httpGsonListResponseObject() {
//        看一下报错 ---使用单个的获取，在json转义的时候出现问题
        "https://api.github.com/repos/kittinunf/Fuel/issues"
            .httpGet()
            .also { Log.d(TAG, it.cUrlString()) }
            .responseObject<ArrayList<Issue>> { _, _, result -> update(result) }
    }

    private fun httpGsonResponseObject2() {
        "https://api.github.com/repos/kittinunf/Fuel/issues/1"
            .httpGet()
            .also { Log.d(TAG, it.cUrlString()) }
            .responseObject<Issue> { _, _, result -> update(result) }
    }

    private fun httpListResponseObject2() {
        "https://api.github.com/repos/kittinunf/Fuel/issues"
            .httpGet()
            .also { Log.d(TAG, it.cUrlString()) }
            .responseObject(Issue.ListDeserializer()) { _, _, result -> update(result) }
    }

    override fun onDestroy() {
        super.onDestroy()
        bag.clear()
    }

    private fun execute() {
        //basic
        httpGet()
        httpPut()
        httpPost()
        httpPatch()
        httpDelete()
        // need study
        httpDownload()
        httpUpload()
        //enhance
        httpBasicAuthentication()
        /**
         * 我们传入的
         *
         *    response(deserializer, handler)
         *
         */
        httpListResponseObject()
        /**
         *  我们传入的
         *    response(deserializer, handler)
         */
        httpResponseObject()
        /**
         * TODO:  等待解析
         *
         * 是不是这个，不在乎你返回了是list了还是object呢？
         *
         *     response(gsonDeserializer(), handler)
         */
        httpGsonResponseObject()

        httpCancel()
        httpRxSupport()
        httpLiveDataSupport()
    }

    private suspend fun executeCoroutine() {
        httpGetCoroutine()
    }

    private suspend fun httpGetCoroutine() {
        val (request, response, result) = Fuel.get("/get", listOf("userId" to "123"))
            .awaitStringResponseResult()
        Log.d(TAG, response.toString())
        Log.d(TAG, request.toString())
        update(result)
    }

    private fun httpCancel() {
        val request = Fuel.get("/delay/10")
            .interrupt { Log.d(TAG, it.url.toString() + " is interrupted") }
            .responseString { _, _, _ -> /* noop */ }

        Handler().postDelayed({ request.cancel() }, 1000)
    }

    /**
     * 在此处的基础上分析一下到底优化了那些，因为是 HttpURLConnection 请求的话，这个就要对比 volley
     * 因为 volley 也是针对了 HttpURLConnection 进行了一些列的封装
     */
    private fun httpResponseObject() {
        "https://api.github.com/repos/kittinunf/Fuel/issues/1"
            /**
             *产生一个 Request 对象
             *
             * applyOptions 的时候产生的线程池对象  executorService
             *
             *  这个地方进行请求的时候说的事异步的
             */
            .httpGet()
            //针对 Request 封装 curl
            .also { Log.d(TAG, it.cUrlString()) }
            /**
             *执行真正的请求
             *
             * Issue.Deserializer() 自定义的序列化对象
             *
             * 这个序列化对象有点复杂了，没有Retrofit的那么好用
             *
             *  Deserializable.kt
             *
             *  Request.response  RequestTaskCallbacks
             *
             *
             *  最终是  RequestTaskCallbacks.kt   call()
             *
             *
             *  RequestTask 里面使用的是 HttpClient  ，自己使用的线程池
             *
             *  也就是  FuelManager#executorService
             *   1. 不分线程了;都是 newCachedThreadPool 创建的
             *     不分线程是 rxjava的 io线程和 cpu线程
             *      不过这个地方那个已经需要区分了，都是 io线程，因为都是网络请求
             *
             *      https://www.zhihu.com/question/23212914 系统创建的特点
             *      针对每种特点执行不同的请求
             *
             */
            .responseObject(Issue.Deserializer()) { _, _, result -> update(result) }
    }

    private fun httpListResponseObject() {
        "https://api.github.com/repos/kittinunf/Fuel/issues"
            .httpGet()
            .also { Log.d(TAG, it.cUrlString()) }
            .responseObject(Issue.ListDeserializer()) { _, _, result -> update(result) }
    }

    /**
     *
     * gson解析的时候，直接使用的 TypeToken ，这样就简单很多了
     * inline fun <reified T : Any> gsonDeserializer(gson: Gson = Gson()) = object : ResponseDeserializable<T> {
     *       override fun deserialize(reader: Reader): T? = gson.fromJson<T>(reader, object : TypeToken<T>() {}.type)
     *  }
     */
    private fun httpGsonResponseObject() {
        "https://api.github.com/repos/kittinunf/Fuel/issues/1"
            .httpGet()
            .also { Log.d(TAG, it.cUrlString()) }
            .responseObject<Issue> { _, _, result -> update(result) }
    }

    private fun httpGet() {
        Fuel.get("/get", listOf("foo" to "foo", "bar" to "bar"))
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }

        "/get"
            .httpGet()
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }
    }

    private fun httpPut() {
        Fuel.put("/put", listOf("foo" to "foo", "bar" to "bar"))
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }

        "/put"
            .httpPut(listOf("foo" to "foo", "bar" to "bar"))
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }
    }

    private fun httpPost() {
        Fuel.post("/post", listOf("foo" to "foo", "bar" to "bar"))
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }

        "/post"
            .httpPost(listOf("foo" to "foo", "bar" to "bar"))
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }
    }

    private fun httpPatch() {
        val manager = FuelManager().apply {
            basePath = "http://httpbin.org"
            baseHeaders = mapOf("Device" to "Android")
            baseParams = listOf("key" to "value")
        }

        manager.forceMethods = true

        manager.request(Method.PATCH, "/patch", listOf("foo" to "foo", "bar" to "bar"))
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }
    }

    private fun httpDelete() {
        Fuel.delete("/delete", listOf("foo" to "foo", "bar" to "bar"))
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }

        "/delete"
            .httpDelete(listOf("foo" to "foo", "bar" to "bar"))
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }
    }

    private fun httpDownload() {
        val n = 100
        Fuel.download("/bytes/${1024 * n}")
            .fileDestination { _, _ -> File(filesDir, "test.tmp") }
            .progress { readBytes, totalBytes ->
                val progress = "$readBytes / $totalBytes"
                runOnUiThread { mainAuxText.text = progress }
                Log.v(TAG, progress)
            }
            .also { Log.d(TAG, it.toString()) }
            .responseString { _, _, result -> update(result) }
    }

    private fun httpUpload() {
        Fuel.upload("/post")
            .add {
                // create random file with some non-sense string
                val file = File(filesDir, "out.tmp")
                file.writer().use { writer ->
                    repeat(100) {
                        writer.appendln("abcdefghijklmnopqrstuvwxyz")
                    }
                }
                FileDataPart(file)
            }
            .progress { writtenBytes, totalBytes ->
                Log.v(TAG, "Upload: ${writtenBytes.toFloat() / totalBytes.toFloat()}")
            }
            .also { Log.d(TAG, it.toString()) }
            .responseString { _, _, result -> update(result) }
    }

    private fun httpBasicAuthentication() {
        val username = "U$3|2|\\|@me"
        val password = "P@$\$vv0|2|)"

        Fuel.get("/basic-auth/$username/$password")
            .authentication()
            .basic(username, password)
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }

        "/basic-auth/$username/$password".httpGet()
            .authentication()
            .basic(username, password)
            .also { Log.d(TAG, it.cUrlString()) }
            .responseString { _, _, result -> update(result) }
    }

    private fun httpRxSupport() {
        val disposable = "https://api.github.com/repos/kittinunf/Fuel/issues/1"
            .httpGet()
            .rxObject(Issue.Deserializer())
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { result -> Log.d(TAG, result.toString()) }
        bag.add(disposable)
    }

    private fun httpLiveDataSupport() {
        "https://api.github.com/repos/kittinunf/Fuel/issues/1"
            .httpGet()
            .liveDataObject(Issue.Deserializer())
            .observeForever { result -> Log.d(TAG, result.toString()) }
    }

    private fun <T : Any> update(result: Result<T, FuelError>) {
        result.fold(success = {
            mainResultText.append(it.toString())
        }, failure = {
            mainResultText.append(String(it.errorData))
        })
    }

    data class Issue(
        val id: Int = 0,
        val title: String = "",
        val url: String = ""
    ) {
        class Deserializer : ResponseDeserializable<Issue> {
            override fun deserialize(reader: Reader) = Gson().fromJson(reader, Issue::class.java)!!
        }

        class ListDeserializer : ResponseDeserializable<List<Issue>> {
            override fun deserialize(reader: Reader): List<Issue> {
                val type = object : TypeToken<List<Issue>>() {}.type
                return Gson().fromJson(reader, type)
            }
        }
    }
}