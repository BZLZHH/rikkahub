package me.rerere.ai.provider.providers.deepseek

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail

/** Holds unreviewed text, thinking and tool calls until the protocol supplies its terminal result. */
internal class DeepSeekStreamDecoder(
    private val protocol: DeepSeekProtocol,
    private val parseResult: (JsonObject) -> TextGenerationResult,
) : StreamChunkDecoder {
    private var completed = false
    private val response = linkedMapOf<String, JsonElement>()
    private val chatMessage = linkedMapOf<String, JsonElement>()
    private val chatTools = sortedMapOf<Int, JsonObject>()
    private val claudeBlocks = sortedMapOf<Int, JsonObject>()
    private val claudeToolInput = mutableMapOf<Int, StringBuilder>()
    private var chatReasoning = StringBuilder()
    private var chatFinish: String? = null

    override fun accept(event: SseEvent): DecodeResult {
        if (completed) return DecodeResult(completed = true)
        if (event.data == "[DONE]") {
            check(protocol == DeepSeekProtocol.CHAT_COMPLETIONS && chatFinish != null) {
                "DeepSeek stream ended without a completion verdict"
            }
            return finish(buildJsonObject {
                response.forEach { (key, value) -> put(key, value) }
                put("choices", JsonArray(listOf(buildJsonObject {
                    put("finish_reason", chatFinish)
                    put("message", buildJsonObject {
                        chatMessage.forEach { (key, value) -> put(key, value) }
                        if (chatReasoning.isNotEmpty()) put("reasoning_content", chatReasoning.toString())
                        if (chatTools.isNotEmpty()) put("tool_calls", JsonArray(chatTools.values.toList()))
                    })
                })))
            })
        }
        val eventBody = json.parseToJsonElement(event.data) as? JsonObject ?: return DecodeResult()
        if (eventBody.hasError() || eventBody.string("type") == "error") throw eventBody.parseErrorDetail()
        return when (protocol) {
            DeepSeekProtocol.CHAT_COMPLETIONS -> acceptChat(eventBody)
            DeepSeekProtocol.RESPONSES -> acceptResponses(eventBody)
            DeepSeekProtocol.MESSAGES -> acceptMessages(eventBody)
        }
    }

    override fun onClosed(): List<StreamChunk> {
        check(completed) { "DeepSeek stream closed before a completion verdict" }
        return emptyList()
    }

    private fun acceptChat(event: JsonObject): DecodeResult {
        listOf("id", "model", "usage").forEach { key ->
            event[key]?.takeUnless { it == JsonNull }?.let { response[key] = it }
        }
        val choice = event.objectList("choices").firstOrNull() ?: return DecodeResult()
        val delta = choice["delta"] as? JsonObject
        if ((choice["server_tool_event"] as? JsonObject)?.string("type") == "step_reasoning_done") {
            chatReasoning = StringBuilder()
        }
        delta?.forEach { (key, value) ->
            if (value != JsonNull) when (key) {
                "reasoning_content" -> chatReasoning.append(delta.string(key).orEmpty())
                "content", "refusal" -> chatMessage[key] = JsonPrimitive(
                    (chatMessage[key] as? JsonPrimitive)?.content.orEmpty() + delta.string(key).orEmpty()
                )
                "tool_calls" -> delta.objectList(key).forEach { addChatTool(it) }
                else -> chatMessage[key] = value
            }
        }
        choice.string("finish_reason")?.let { chatFinish = it }
        return DecodeResult()
    }

    private fun addChatTool(delta: JsonObject) {
        val index = (delta["index"] as? JsonPrimitive)?.intOrNull ?: return
        val previous = chatTools[index].orEmpty()
        val function = (previous["function"] as? JsonObject).orEmpty().toMutableMap()
        (delta["function"] as? JsonObject)?.forEach { (key, value) ->
            if (key == "arguments" || key == "name") {
                function[key] = JsonPrimitive((function[key] as? JsonPrimitive)?.content.orEmpty() +
                    (value as? JsonPrimitive)?.content.orEmpty())
            } else {
                function[key] = value
            }
        }
        chatTools[index] = JsonObject(previous + delta.filterKeys { it != "index" && it != "function" } +
            ("function" to JsonObject(function)))
    }

    private fun acceptResponses(event: JsonObject): DecodeResult = when (event.string("type")) {
        "response.completed", "response.incomplete", "response.failed" -> {
            val snapshot = event["response"] as? JsonObject ?: error("DeepSeek response snapshot is missing")
            finish(snapshot)
        }
        else -> DecodeResult()
    }

    private fun acceptMessages(event: JsonObject): DecodeResult {
        val index = (event["index"] as? JsonPrimitive)?.intOrNull
        when (event.string("type")) {
            "message_start" -> (event["message"] as? JsonObject)?.let { message ->
                response.putAll(message)
                message.objectList("content").forEachIndexed { position, block -> claudeBlocks[position] = block }
            }
            "content_block_start" -> if (index != null) {
                (event["content_block"] as? JsonObject)?.let { claudeBlocks[index] = it }
            }
            "content_block_delta" -> if (index != null) {
                (event["delta"] as? JsonObject)?.let { addClaudeDelta(index, it) }
            }
            "content_block_stop" -> if (index != null) {
                claudeToolInput.remove(index)?.toString()?.takeIf { it.isNotBlank() }?.let { input ->
                    val block = claudeBlocks[index] ?: error("Missing DeepSeek tool-use block")
                    claudeBlocks[index] = JsonObject(block + ("input" to json.parseToJsonElement(input)))
                }
            }
            "message_delta" -> {
                (event["delta"] as? JsonObject)?.let { response.putAll(it) }
                (event["usage"] as? JsonObject)?.let { usage ->
                    response["usage"] = JsonObject((response["usage"] as? JsonObject).orEmpty() + usage)
                }
            }
            "message_stop" -> {
                check(JsonObject(response).string("stop_reason") != null) {
                    "DeepSeek stream ended without a completion verdict"
                }
                response["content"] = JsonArray(claudeBlocks.values.toList())
                return finish(JsonObject(response))
            }
        }
        return DecodeResult()
    }

    private fun addClaudeDelta(index: Int, delta: JsonObject) {
        val block = claudeBlocks[index] ?: return
        when (delta.string("type")) {
            "text_delta" -> appendClaudeField(index, block, delta, "text")
            "thinking_delta" -> appendClaudeField(index, block, delta, "thinking")
            "signature_delta" -> appendClaudeField(index, block, delta, "signature")
            "citations_delta" -> delta["citation"]?.let { citation ->
                val citations = (block["citations"] as? JsonArray).orEmpty() + citation
                claudeBlocks[index] = JsonObject(block + ("citations" to JsonArray(citations)))
            }
            "input_json_delta" -> claudeToolInput.getOrPut(index, ::StringBuilder)
                .append(delta.string("partial_json").orEmpty())
        }
    }

    private fun appendClaudeField(index: Int, block: JsonObject, delta: JsonObject, name: String) {
        val text = block.string(name).orEmpty() + delta.string(name).orEmpty()
        claudeBlocks[index] = JsonObject(block + (name to JsonPrimitive(text)))
    }

    private fun finish(body: JsonObject): DecodeResult {
        val result = parseResult(body)
        completed = true
        return DecodeResult(result.toChunks(), completed = true)
    }
}

private fun TextGenerationResult.toChunks(): List<StreamChunk> = buildList {
    message.parts.forEachIndexed { index, part ->
        val id = "deepseek:$index"
        when (part) {
            is UIMessagePart.Text -> {
                add(StreamChunk.TextDelta(id, part.text, part.metadata))
                add(StreamChunk.TextEnd(id))
            }
            is UIMessagePart.Reasoning -> {
                add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata, part.reasoningType))
                add(StreamChunk.ReasoningEnd(id))
            }
            is UIMessagePart.ServerTool -> {
                add(StreamChunk.ServerToolStart(part.toolCallId, part.toolName, part.input, part.metadata))
                add(StreamChunk.ServerToolEnd(part.toolCallId, part.input, part.output, part.status, part.metadata))
            }
            is UIMessagePart.Tool -> {
                add(StreamChunk.ToolCallStart(part.toolCallId, part.toolName, part.metadata))
                add(StreamChunk.ToolCallDelta(part.toolCallId, inputDelta = part.input))
                add(StreamChunk.ToolCallEnd(part.toolCallId))
                part.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.let {
                    add(StreamChunk.ToolCallOutput(part.toolCallId, it.text))
                }
            }
            is UIMessagePart.Image -> add(StreamChunk.ImageDelta(id, part.url, part.metadata))
            else -> Unit
        }
    }
    if (message.annotations.isNotEmpty()) add(StreamChunk.Annotations(message.annotations))
    if (message.deepSeekReplay.isEmpty()) {
        message.deepSeekTitle?.let { add(StreamChunk.DeepSeekMetadata(title = it)) }
    } else {
        message.deepSeekReplay.forEachIndexed { index, replay ->
            add(StreamChunk.DeepSeekMetadata(message.deepSeekTitle.takeIf { index == 0 }, replay))
        }
    }
    usage?.let { add(StreamChunk.Usage(it)) }
    add(StreamChunk.Finish(finishReason, id, model))
}
