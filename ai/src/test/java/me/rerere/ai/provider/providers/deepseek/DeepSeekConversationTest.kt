package me.rerere.ai.provider.providers.deepseek

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.DeepSeekModelOptions
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.deepseek.withDeepSeekFileId
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekConversationTest {
    private val openAI = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", apiKey = "one,two")
    private val messagesProvider = ProviderSetting.Claude(baseUrl = "https://api.deepseek.com/anthropic/v1", apiKey = "one")
    private val client = OkHttpClient()
    private val chat = ChatCompletionsAPI(client, KeyRoulette.default())
    private val responses = ResponseAPI(client)
    private val messages = ClaudeProvider(client)
    private val params = TextGenerationParams(
        model = Model(
            modelId = "deepseek-flash",
            displayName = "Flash",
            tools = setOf(BuiltInTools.Search, BuiltInTools.DeepSeek),
            deepSeek = DeepSeekModelOptions(userMetadata = true),
        ),
        reasoningLevel = ReasoningLevel.XHIGH,
        deepSeekRequestTitle = true,
    )

    @Test
    fun nativeRequestsDeclareToolsAndEncodeUploadedDocumentsAndImages() {
        val user = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("Check these"),
                UIMessagePart.Document("content://doc", "doc.pdf").withDeepSeekFileId("file-api-doc"),
                UIMessagePart.Image("content://image").withDeepSeekFileId("file-api-image"),
            ),
        )
        val request = params.copy(
            deepSeekReminders = mapOf(user.id.toString() to "2026-11-21,Saturday,,App,English"),
            customBody = listOf(CustomBody("safety_check.level", JsonPrimitive("low"))),
        )
        val completions = chat.buildChatCompletionRequest(listOf(user), request, openAI)
        val response = responses.buildRequestBody(ResponseProviderCapabilities(), listOf(user), request, true,
            openAI.deepSeekConversation(DeepSeekProtocol.RESPONSES, request))
        val message = messages.buildMessageRequest(messagesProvider, listOf(user), request)
        listOf(completions, response, message).forEach { body ->
            assertEquals("high", body["safety_check"]?.jsonObject?.string("level"))
            assertEquals(JsonPrimitive(true), body["title"]?.jsonObject?.get("enabled"))
            assertEquals(listOf(DEEPSEEK_WEB_SEARCH, DEEPSEEK_READ_URL), body.objectList("tools").map { it.string("type") })
        }
        assertEquals("max", completions.string("reasoning_effort"))
        assertEquals("max", response["reasoning"]?.jsonObject?.string("effort"))
        assertEquals("max", message["output_config"]?.jsonObject?.string("effort"))
        assertEquals(listOf("latest_reminder", "user"), completions.objectList("messages").map { it.string("role") })
        assertEquals(listOf("latest_reminder", "user"), message.objectList("messages").map { it.string("role") })
        assertEquals(listOf("user"), response.objectList("input").map { it.string("role") })
        val chatFiles = completions.objectList("messages").last().objectList("content").drop(1)
        assertEquals(listOf("file", "file"), chatFiles.map { it.string("type") })
        assertEquals(listOf("file-api-doc", "file-api-image"), chatFiles.map { it.string("file_id") })
        val responseFiles = response.objectList("input").last().objectList("content").drop(1)
        assertEquals(listOf("input_file", "input_image"), responseFiles.map { it.string("type") })
        val messageFiles = message.objectList("messages").last().objectList("content").drop(1)
        assertEquals(listOf("document", "image"), messageFiles.map { it.string("type") })
        assertEquals(listOf("file-api-doc", "file-api-image"), messageFiles.map { it["source"]?.jsonObject?.string("file_id") })
    }

    @Test
    fun masterSwitchAndEndpointGateManagedExtensions() {
        val ordinary = params.copy(model = params.model.copy(tools = emptySet()))
        val body = chat.buildChatCompletionRequest(listOf(UIMessage.user("hello")), ordinary, openAI)
        assertFalse("safety_check" in body)
        assertFalse("title" in body)
        assertFalse(openAI.deepSeekConversation(DeepSeekProtocol.CHAT_COMPLETIONS, ordinary)!!.shouldBuffer(body))
        assertNull(openAI.copy(baseUrl = "https://example.invalid/v1").deepSeekConversation(DeepSeekProtocol.CHAT_COMPLETIONS, params))
        val noWeb = params.copy(model = params.model.copy(deepSeek = params.model.deepSeek.copy(webTools = false)))
        val noWebBody = chat.buildChatCompletionRequest(listOf(UIMessage.user("hello")), noWeb, openAI)
        assertTrue(noWebBody.objectList("tools").isEmpty())
        assertNotNull(noWebBody["safety_check"])
    }

    @Test
    fun chatStreamHidesRefusedOutputAndKeepsSignedReadLink() {
        val native = openAI.deepSeekConversation(DeepSeekProtocol.CHAT_COMPLETIONS, params)!!
        val decoder = DeepSeekStreamDecoder(native.protocol) { chat.parseCompletion(it, native) }
        val signedStep = """[{"index":0,"reasoning_content":"hidden step","calls":[{"index":0,"id":"link","type":"deepseek_read_url","kind":"read_link","status":"completed","action":{"type":"read_link","url":"https://example.com"},"signature":"signed page"}]}]"""
        assertTrue(decoder.accept(sse("""{"choices":[{"delta":{"reasoning_content":"unreviewed thinking","content":"unreviewed answer"}}]}""")).chunks.isEmpty())
        assertTrue(decoder.accept(sse("""{"choices":[{"delta":{"server_tool_calls":$signedStep,"title":"hidden title"},"finish_reason":"content_filter"}]}""")).chunks.isEmpty())
        val result = merge(decoder.accept(sse("[DONE]")).chunks)
        assertEquals(DEEPSEEK_REFUSAL, result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertNull(result.deepSeekTitle)
        assertTrue(result.parts.none { it is UIMessagePart.Reasoning || it is UIMessagePart.Tool })
        val payload = native.payloads(result).single().jsonObject
        val step = payload.objectList("server_tool_calls").single()
        assertEquals("", step.string("reasoning_content"))
        assertEquals("signed page", step.objectList("calls").single().string("signature"))
        assertFalse(payload.toString().contains("unreviewed"))
    }

    @Test
    fun responsesReplayPreservesItemsAndInsertsClientToolOutputBetweenSegments() {
        val native = openAI.deepSeekConversation(DeepSeekProtocol.RESPONSES, params)!!
        val first = jsonObject("""{"status":"completed","output":[{"type":"web_search_call","id":"srv","status":"completed","kind":"read_link","action":{"type":"open_page","url":"https://example.com"},"signature":"opaque"},{"type":"function_call","call_id":"local","name":"lookup","arguments":"{}"}]}""")
        val second = jsonObject("""{"status":"completed","title":"My title","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Done","annotations":[{"type":"url_citation","url":"https://example.com","title":"Example","start_index":0,"end_index":4}]}]}]}""")
        val one = responses.parseResponseOutput(first, native).message
        val two = responses.parseResponseOutput(second, native).message
        val history = one.copy(
            parts = one.parts.map { if (it is UIMessagePart.Tool) it.copy(output = listOf(UIMessagePart.Text("found"))) else it } + two.parts,
            deepSeekReplay = one.deepSeekReplay + two.deepSeekReplay,
        )
        val items = responses.buildMessages(listOf(history), native)
        assertEquals(first["output"]!!.jsonArray[0], items[0])
        assertEquals(first["output"]!!.jsonArray[1], items[1])
        assertEquals("found", items[2].jsonObject.string("output"))
        assertEquals(second["output"]!!.jsonArray[0], items[3])
        assertEquals("My title", two.deepSeekTitle)
        assertEquals(listOf(UIMessageAnnotation.UrlCitation("Example", "https://example.com")), two.annotations)
        val changedKey = openAI.copy(apiKey = "another").deepSeekConversation(DeepSeekProtocol.RESPONSES, params)!!
        assertTrue(changedKey.payloads(history).isEmpty())
        assertFalse(responses.buildMessages(listOf(history), changedKey).toString().contains("opaque"))
    }

    @Test
    fun messagesStreamReassemblesOrderedBlocksAndDoesNotReleaseUntilVerdict() {
        val native = messagesProvider.deepSeekConversation(DeepSeekProtocol.MESSAGES, params)!!
        val decoder = DeepSeekStreamDecoder(native.protocol) { messages.parseMessageResponse(it, native) }
        val events = listOf(
            """{"type":"message_start","message":{"role":"assistant","id":"msg","model":"deepseek-flash","content":[],"stop_reason":null}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Thought"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"think signature"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"link","name":"deepseek_read_url","kind":"read_link","input":{"action":"read_link"}}}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"web_fetch_tool_result","tool_use_id":"link","kind":"read_link","signature":"link signature","content":{"type":"web_fetch_result"}}}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"Answer"}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://example.com","cited_text":"Answer"}}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","title":"Native title"}}""",
        )
        events.forEach { assertTrue(decoder.accept(sse(it)).chunks.isEmpty()) }
        val result = merge(decoder.accept(sse("""{"type":"message_stop"}""")).chunks)
        val blocks = native.payloads(result).single() as JsonArray
        assertEquals(listOf("thinking", "server_tool_use", "web_fetch_tool_result", "text"), blocks.map { it.jsonObject.string("type") })
        assertEquals("think signature", blocks[0].jsonObject.string("signature"))
        assertEquals("link signature", blocks[2].jsonObject.string("signature"))
        assertEquals("Native title", result.deepSeekTitle)
        assertEquals(ServerToolStatus.COMPLETED, result.parts.filterIsInstance<UIMessagePart.ServerTool>().single().status)
        assertEquals(1, result.annotations.size)
        val replay = messages.buildMessageRequest(messagesProvider, listOf(result, UIMessage.user("Next")), params)
        assertEquals(blocks, replay.objectList("messages").first()["content"])
    }

    @Test
    fun truncatedStreamFailsClosed() {
        val decoder = DeepSeekStreamDecoder(DeepSeekProtocol.MESSAGES) { error("No final response expected") }
        assertTrue(decoder.accept(sse("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"unreviewed"}}""")).chunks.isEmpty())
        assertThrows(IllegalStateException::class.java) { decoder.onClosed() }
    }

    private fun jsonObject(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun sse(data: String) = SseEvent(id = null, event = null, data = data)

    private fun merge(chunks: List<me.rerere.ai.ui.StreamChunk>): UIMessage {
        val handler = StreamChunkHandler()
        return chunks.fold(listOf(UIMessage.user("Hello"))) { state, chunk -> handler.handle(state, chunk) }.last()
    }
}
