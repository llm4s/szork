package org.llm4s.szork.stubs

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.error.LLMError

/** Deterministic LLM stub that returns compact, schema-conforming messages so GameEngine can be tested without network.
  */
class FakeLLMClient extends LLMClient {
  private def replyFor(conversation: Conversation): Completion = {
    val lastUser = conversation.messages.reverse.collectFirst { case u: UserMessage => u.content }.getOrElse("")
    val content =
      if (lastUser
          .toLowerCase()
          .startsWith("look") || lastUser.toLowerCase().contains("start adventure") || lastUser.isEmpty)
        DmAnswers.scene("You are at the cavern entrance.", "cavern_entrance", "Cavern Entrance", Seq("north" -> "hall"))
      else if (lastUser.toLowerCase().startsWith("go"))
        DmAnswers.scene(
          "You walk north into a small hall.",
          "hall",
          "Small Hall",
          Seq("south" -> "cavern_entrance"),
          mood = "mystery")
      else DmAnswers.simple("You examine it closely.", "cavern_entrance", "examine")

    Completion(
      id = java.util.UUID.randomUUID().toString,
      created = java.time.Instant.now(),
      content = content,
      model = "fake-llm",
      message = AssistantMessage(Some(content)),
      toolCalls = Nil,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )
  }

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    Right(replyFor(conversation))

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit): Result[Completion] = {
    // Stream the reply in small pieces, as a provider would, then return the whole completion
    val completion = replyFor(conversation)
    completion.content.grouped(8).foreach(piece => onChunk(StreamedChunk(completion.id, Some(piece))))
    Right(completion)
  }

  override def getContextWindow(): Int = 8000
  override def getReserveCompletion(): Int = 1000
}
