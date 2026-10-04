package com.amiri.videoengine.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    /** Long read timeout: free GPU queues can keep a stream open for minutes. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    /** Short timeouts for status checks. */
    val quick: OkHttpClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
}

/** Tracks in-flight calls so a provider can cancel everything at once. */
class CallTracker {
    private val calls = CopyOnWriteArraySet<Call>()

    fun cancelAll() {
        calls.forEach { it.cancel() }
        calls.clear()
    }

    internal fun add(call: Call) = calls.add(call)
    internal fun remove(call: Call) = calls.remove(call)
}

/** Executes a request in a coroutine-cancellable way. Caller must close the response. */
suspend fun OkHttpClient.await(request: Request, tracker: CallTracker? = null): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        tracker?.add(call)
        cont.invokeOnCancellation {
            call.cancel()
            tracker?.remove(call)
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                tracker?.remove(call)
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                tracker?.remove(call)
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

/** Streams a successful response body into [out]. */
suspend fun Response.saveTo(out: File) = withContext(Dispatchers.IO) {
    use { resp ->
        val body = resp.body ?: throw IOException("Empty response body")
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        body.byteStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw IOException("Could not save file")
    }
}
