package org.llm4s.szork.stubs

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.error.LLMError

/** Deterministic LLM stub that returns compact, schema-conforming messages so GameEngine can be tested without network.
  */
class FakeLLMClient extends LLMClient {
  private def replyFor(conversation: Conversation): Completion = {
    val lastUser = conversation.messages.reverse.collectFirst { case UserMessage(c) => c }.getOrElse("")
    val (narration, json) =
      if (lastUser
          .toLowerCase()
          .startsWith("look") || lastUser.toLowerCase().contains("start adventure") || lastUser.isEmpty) {
        (
          "You are at the cavern entrance.",
          ujson.Obj(
            "responseType" -> "fullScene",
            "locationId" -> "cavern_entrance",
            "locationName" -> "Cavern Entrance",
            "imageDescription" -> "A rough cavern mouth",
            "musicDescription" -> "Calm ambient",
            "musicMood" -> "exploration",
            "exits" -> ujson.Arr(ujson.Obj("direction" -> "north", "locationId" -> "hall")),
            "items" -> ujson.Arr(),
            "npcs" -> ujson.Arr()
          )
        )
      } else if (lastUser.toLowerCase().startsWith("go")) {
        (
          "You walk north into a small hall.",
          ujson.Obj(
            "responseType" -> "fullScene",
            "locationId" -> "hall",
            "locationName" -> "Small Hall",
            "imageDescription" -> "A dim hall",
            "musicDescription" -> "Eerie",
            "musicMood" -> "mystery",
            "exits" -> ujson.Arr(ujson.Obj("direction" -> "south", "locationId" -> "cavern_entrance")),
            "items" -> ujson.Arr(),
            "npcs" -> ujson.Arr()
          )
        )
      } else {
        (
          "You examine it closely.",
          ujson.Obj(
            "responseType" -> "simple",
            "locationId" -> "cavern_entrance",
            "actionTaken" -> "examine"
          )
        )
      }

    val content = s"$narration\n<<<JSON>>>\n${ujson.write(json)}"
    Completion(
      id = java.util.UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      message = AssistantMessage(Some(content)),
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )
  }

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    Right(replyFor(conversation))

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit): Result[Completion] = Right(replyFor(conversation))
}
