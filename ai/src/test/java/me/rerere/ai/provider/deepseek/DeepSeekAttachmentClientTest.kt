package me.rerere.ai.provider.deepseek

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DeepSeekAttachmentClientTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun uploadUsesOfficialEndpointAndBearerAndSetsExpiry() = runTest {
        val file = folder.newFile("image.png")
        val factory = RecordingCallFactory("""{"id":"file-api-image"}""")
        val client = DeepSeekAttachmentClient(factory)

        val id = client.upload("sk-test", file, file.name, "image/png", DeepSeekFilePurpose.USER_DATA, true, 3600)

        assertEquals("file-api-image", id)
        val request = factory.call.request()
        assertEquals("https://api.deepseek.com/files", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals("POST", request.method)
        val parts = (request.body as MultipartBody).parts
        val form = parts.associate { part ->
            part.headers?.get("Content-Disposition") to Buffer().also { part.body.writeTo(it) }.readUtf8()
        }
        assertEquals("created_at", form["form-data; name=\"expires_after[anchor]\""])
        assertEquals("3600", form["form-data; name=\"expires_after[seconds]\""])
    }

    @Test
    fun cancellingOcrCancelsTheUnderlyingCall() = runTest {
        val file = folder.newFile("ocr.png")
        val factory = RecordingCallFactory()
        val client = DeepSeekAttachmentClient(factory)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            client.recognize("sk-test", file, file.name, "image/png")
        }
        assertEquals("https://api.deepseek.com/v1/tools/ocr", factory.call.request().url.toString())
        assertTrue(factory.call.isExecuted())
        job.cancelAndJoin()
        assertTrue(factory.call.isCanceled())
    }

    @Test
    fun multipartPlacesPurposeBeforeTheFile() {
        val file = folder.newFile().apply { writeText("attachment-bytes") }
        val body = deepSeekMultipartBody(
            file, "input.pdf", "application/pdf", DeepSeekFilePurpose.RICH_LAYOUT_RECOGNITION, 3600,
        )
        val contents = Buffer().also { body.writeTo(it) }.readUtf8()
        val purpose = contents.indexOf("name=\"purpose\"")
        val attachment = contents.indexOf("name=\"file\"; filename=\"input.pdf\"")
        assertTrue(purpose >= 0 && attachment > purpose)
        assertTrue(contents.contains("rich_layout_recognition"))
        assertTrue(contents.contains("expires_after[anchor]"))
        assertTrue(contents.contains("attachment-bytes"))
    }

    @Test
    fun ocrMultipartContainsOnlyPurposeAndFile() {
        val file = folder.newFile("image.png")
        val body = deepSeekMultipartBody(file, file.name, "image/png", DeepSeekFilePurpose.TEXT_RECOGNITION)
        assertEquals(2, body.parts.size)
    }

    @Test
    fun ocrResponseAcceptsKeepaliveWhitespaceAndPreservesPageMarkers() {
        val payload = "\n\n" + """
            {"id":"ocr-abc","object":"ocr.result","purpose":"text_recognition","page_count":1,
            "pages":[{"index":0,"text":"<｜ref｜>Title<｜/ref｜>","finish_reason":"length"}]}
        """.trimIndent()
        val result = json.decodeFromJsonElement(
            DeepSeekOcrResult.serializer(),
            decodeDeepSeekAttachmentResponse(200, payload, ""),
        )
        assertEquals(1, result.pageCount)
        assertEquals("<｜ref｜>Title<｜/ref｜>", result.pages.single().text)
        assertEquals("length", result.pages.single().finishReason)
    }

    @Test
    fun ocrSurfacesAnErrorAfterHttp200AndRedactsTheKey() {
        val error = assertThrows(IOException::class.java) {
            decodeDeepSeekAttachmentResponse(200, "\n{\"error\":{\"message\":\"Timed out for sk-secret\"}}", "sk-secret")
        }
        assertTrue(error.message.orEmpty().contains("Timed out"))
        assertFalse(error.message.orEmpty().contains("sk-secret"))
    }

    @Test
    fun fileReferencesUseACopyAndPreserveExistingMetadata() {
        val part = UIMessagePart.Document("file:///upload/doc.pdf", "doc.pdf", metadata = JsonObject(mapOf("old" to JsonPrimitive("value"))))
        val requestPart = part.withDeepSeekFileId("file-api-example")
        assertNull(part.deepSeekFileId)
        assertEquals("file-api-example", requestPart.deepSeekFileId)
        assertEquals(JsonPrimitive("value"), requestPart.metadata?.get("old"))
        assertEquals(part.url, (requestPart as UIMessagePart.Document).url)
    }

    private class RecordingCallFactory(private val payload: String? = null) : Call.Factory {
        private val client = OkHttpClient()
        lateinit var call: RecordingCall
            private set

        override fun newCall(request: Request): Call = RecordingCall(client.newCall(request), payload).also { call = it }
    }

    private class RecordingCall(private val delegate: Call, private val payload: String?) : Call by delegate {
        private var executed = false

        override fun execute(): Response = error("Unexpected synchronous execution")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            if (payload != null) {
                val response = Response.Builder().request(request())
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(payload.toResponseBody()).build()
                responseCallback.onResponse(this, response)
            }
        }

        override fun isExecuted(): Boolean = executed
        override fun clone(): Call = RecordingCall(delegate.clone(), payload)
    }
}
