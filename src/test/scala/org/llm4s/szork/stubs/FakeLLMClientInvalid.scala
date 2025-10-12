package org.llm4s.szork.stubs

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

/** Fake LLM that deliberately produces invalid exits to trigger validator errors.
  */
class FakeLLMClientInvalid extends LLMClient {
  private def invalidCompletion(narration: String): Completion = {
    val json = ujson.Obj(
      "responseType" -> "fullScene",
      "locationId" -> "invalid_room",
      "locationName" -> "Invalid Room",
      "imageDescription" -> "x",
      "musicDescription" -> "y",
      "musicMood" -> "exploration",
      "exits" -> ujson.Arr(ujson.Obj("direction" -> "north-east", "locationId" -> "next")),
      "items" -> ujson.Arr(),
      "npcs" -> ujson.Arr()
    )
    val content = s"$narration\n<<<JSON>>>\n${ujson.write(json)}"
    Completion(
      id = java.util.UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = content,
      model = "fake-llm-invalid",
      message = AssistantMessage(Some(content)),
      toolCalls = Nil,
      usage = Some(TokenUsage(promptTokens = 5, completionTokens = 5, totalTokens = 10))
    )
  }

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    Right(invalidCompletion("Invalid scene response."))

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit): Result[Completion] =
    Right(invalidCompletion("Invalid scene response (stream)."))

  override def getContextWindow(): Int = 8000
  override def getReserveCompletion(): Int = 1000
}
