package io.github.nanomuse.net

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Runs the call and suspends until [read] has consumed its response: cancelling the
 * coroutine cancels the call, including a body read after the headers arrived. `execute()` inside
 * `withContext(Dispatchers.IO)` does not notice cancellation; it keeps the IO thread parked in
 * the read until OkHttp's own timeout, so leaving the sign-in page or the network page used to
 * leave the request running for up to half a minute (#336).
 *
 * [read] runs on OkHttp's callback thread. The response is closed before the caller resumes,
 * or immediately if it arrives after cancellation. Returning a raw response would detach
 * later blocking body reads from this continuation's cancellation handler (#336).
 * Throws [IOException] as `execute()` would, and `CancellationException` when cancelled.
 */
suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (!cont.isActive) {
                response.close()
                return
            }
            try {
                val result = response.use(read)
                cont.resume(result)
            } catch (error: Exception) {
                if (!cont.isCancelled) cont.resumeWithException(error)
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
