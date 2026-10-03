package lt.gfau.se.shuriken.wigle

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class WigleUploadResult(val transactionIds: List<String>)

class WigleUploadException(val userMessage: String, val httpCode: Int? = null,
    val retryAt: Long? = null) : IOException(userMessage)

class WigleUpload internal constructor(
    private val endpoint: String = "https://api.wigle.net/api/v2/file/upload"
) {
    // Redirects must not forward credentials. A POST must never be retried silently.
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    internal fun buildRequest(file: File, credentials: WigleCredentials,
        originalFileName: String = "shuriken.csv"): Request {
        val gzip = file.asRequestBody("application/gzip".toMediaType())
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("file", WigleGzip.uploadName(originalFileName), gzip)
        }.build()
        // OkHttp 4's MultipartBody does not propagate part-level isOneShot.
        val singleUseBody = object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            // Prevents status-based retries as well (e.g. 503 Retry-After: 0).
            override fun isOneShot() = true
        }
        return Request.Builder().url(endpoint)
            .header("Authorization", credentials.authorizationHeader())
            .header("User-Agent", "Shuriken-Android")
            .post(singleUseBody).build()
    }

    internal fun httpFailure(code: Int, retryAfter: String? = null): WigleUploadException? = when (code) {
        400 -> WigleUploadException("WiGLE rejected the file: check its original filename and useful CSV observations.", 400)
        401, 403 -> WigleUploadException("WiGLE rejected the credentials. Scan a new activation QR.", code)
        418 -> WigleUploadException("WiGLE blocked uploading. Contact WiGLE-admin@wigle.net.", 418)
        429 -> WigleUploadException("WiGLE's daily upload limit was reached. Remaining files were not sent.",
            429, WigleRateLimit.retryAt(retryAfter))
        500 -> WigleUploadException("WiGLE could not process the file (HTTP 500). Check upload history before retrying.", 500)
        in 200..299 -> null
        else -> WigleUploadException("WiGLE returned HTTP $code. Check upload history before retrying.", code)
    }

    suspend fun upload(file: File, credentials: WigleCredentials,
        originalFileName: String = "shuriken.csv"): WigleUploadResult = suspendCancellableCoroutine { continuation ->
        val request = buildRequest(file, credentials, originalFileName)
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Never surface raw transport exceptions or server echoes containing secrets.
                continuation.resumeWith(Result.failure(WigleUploadException(
                    "Upload interrupted. WiGLE may have received it; check upload history before retrying.")))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        httpFailure(response.code, response.header("Retry-After"))?.let { throw it }
                        val source = response.body?.source()
                            ?: throw WigleUploadException("Missing WiGLE response. Check upload history before retrying.")
                        if (source.request(64 * 1024 + 1L)) throw WigleUploadException(
                            "Unexpected WiGLE response. Check upload history before retrying.")
                        val json = JSONObject(source.readUtf8())
                        if (!json.optBoolean("success", false)) throw WigleUploadException(
                            "WiGLE did not accept the file. Check its CSV format and your account.")
                        val transactions = json.optJSONObject("results")?.optJSONArray("transids")
                        val ids = (0 until (transactions?.length() ?: 0)).mapNotNull { index ->
                            transactions?.optJSONObject(index)?.let { it.optString("transId").ifEmpty { it.optString("transid") } }
                                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
                        }
                        continuation.resumeWith(Result.success(WigleUploadResult(ids)))
                    } catch (e: Exception) {
                        continuation.resumeWith(Result.failure(
                            if (e is WigleUploadException) e else WigleUploadException(
                                "Unreadable WiGLE response. Check upload history before retrying.")))
                    }
                }
            }
        })
    }
}
