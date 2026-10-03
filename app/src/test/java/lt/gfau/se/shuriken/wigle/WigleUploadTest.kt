package lt.gfau.se.shuriken.wigle

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.concurrent.TimeUnit

class WigleUploadTest {
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private val credentials = WigleCredentials.fromQr("display:api_name:api_token")
    private val accepted = """{"success":true,"results":{"transids":[{"transId":"12345"}]}}"""

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        file = File.createTempFile("wigle-test-", ".csv.gz")
        val source = File.createTempFile("wigle-source-", ".csv").apply { writeText("CSV test bytes") }
        try { WigleGzip.compress(source, file) } finally { source.delete() }
    }

    @After fun tearDown() { file.delete(); server.shutdown() }

    private fun uploader() = WigleUpload(server.url("/api/v2/file/upload").toString())

    @Test fun entireMultipartRequestIsOneShot() {
        // Part-level flags are not propagated by MultipartBody in OkHttp 4.
        assertTrue(uploader().buildRequest(file, credentials).body!!.isOneShot())
    }

    @Test fun postsAuthenticatedMultipartAndReportsAcceptedTransaction() = runBlocking {
        server.enqueue(MockResponse().setBody(accepted))
        val result = uploader().upload(file, credentials, "original-survey.csv")
        assertEquals(listOf("12345"), result.transactionIds)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/v2/file/upload", request.path)
        assertEquals(credentials.authorizationHeader(), request.getHeader("Authorization"))
        val bytes = request.body.readByteArray()
        val body = String(bytes, Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"original-survey.csv.gz\""))
        assertTrue(body.contains("Content-Type: application/gzip"))
        val payloadStart = body.indexOf("\r\n\r\n", body.indexOf("name=\"file\"")) + 4
        val payloadEnd = body.lastIndexOf("\r\n--")
        val decompressed = GZIPInputStream(bytes.copyOfRange(payloadStart, payloadEnd).inputStream())
            .use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("CSV test bytes", decompressed)
        assertEquals(bytes.size.toLong(), request.getHeader("Content-Length")!!.toLong())
        assertFalse(body.contains("name=\"donate\""))
        assertFalse(body.contains("name=\"fileSize\""))
    }

    @Test fun productionEndpointUsesWorkingV2Path() {
        assertEquals("https://api.wigle.net/api/v2/file/upload", WigleUpload().buildRequest(file, credentials).url.toString())
    }

    @Test fun authenticationFailureIsNotRetriedOrEchoed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("echo api_token"))
        try { uploader().upload(file, credentials); fail("Unauthorized upload accepted") }
        catch (e: WigleUploadException) {
            assertFalse(e.userMessage.contains("api_token"))
            assertTrue(e.userMessage.contains("credentials"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun documentedFailureStatusesOverrideMisleadingSuccessFlag() = runBlocking {
        for (code in listOf(400, 401, 418, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"success":true,"message":"api_token"}"""))
            try { uploader().upload(file, credentials); fail("HTTP $code accepted") }
            catch (e: WigleUploadException) {
                assertEquals(code, e.httpCode)
                assertFalse(e.userMessage.contains("api_token"))
                if (code == 429) assertTrue(e.retryAt!! > System.currentTimeMillis())
            }
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun rateLimitPreservesRetryAfterAndNeverRetries() = runBlocking {
        val before = System.currentTimeMillis()
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
        try { uploader().upload(file, credentials); fail("Rate limit ignored") }
        catch (e: WigleUploadException) {
            assertEquals(429, e.httpCode)
            assertTrue(e.retryAt!! >= before + 120000)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun legacyLowercaseTransactionIdStillWorks() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"results":{"transids":[{"transid":"legacy"}]}}"""))
        assertEquals(listOf("legacy"), uploader().upload(file, credentials).transactionIds)
    }

    @Test fun retryableServerFailureDoesNotResendThePost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody(accepted))
        try { uploader().upload(file, credentials); fail("POST retried") }
        catch (e: WigleUploadException) { assertTrue(e.userMessage.contains("503")) }
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectsAreNotFollowed() = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/leak")))
            try { uploader().upload(file, credentials); fail("Redirect followed") }
            catch (e: WigleUploadException) { assertTrue(e.userMessage.contains("307")) }
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun serverRejectionAndMalformedResponseAreNotSuccess() = runBlocking {
        for (body in listOf("""{"success":false,"message":"api_token"}""", "not JSON")) {
            server.enqueue(MockResponse().setBody(body))
            try { uploader().upload(file, credentials); fail("Bad response accepted") }
            catch (e: WigleUploadException) { assertFalse(e.userMessage.contains("api_token")) }
        }
    }

    @Test fun oversizedResponseIsRejected() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(65537)))
        try { uploader().upload(file, credentials); fail("Oversized response accepted") }
        catch (e: WigleUploadException) { assertTrue(e.userMessage.contains("Unexpected")) }
    }

    @Test fun cancellationCancelsTheHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { uploader().upload(file, credentials) }
        // Run the blocking wait on IO so the child coroutine can start.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    }
}
