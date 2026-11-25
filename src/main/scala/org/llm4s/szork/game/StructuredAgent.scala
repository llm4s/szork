package org.llm4s.szork.game

import org.llm4s.agent.{Agent, AgentStatus}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{CompletionOptions, ReasoningEffort}
import org.llm4s.toolapi.ObjectSchema
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import upickle.default.ReadWriter

/** One question to an llm4s agent that has no tools and answers in a schema: how the adventure designer
  * ([[AdventureGenerator]]) and the art director ([[ArtDirector]]) are run.
  *
  * The agent is built with `withOutput(schema)`: llm4s states the schema in the system prompt, checks the answer
  * against it (and that it reads as an `A`), and asks the model to correct an invalid answer once, within the same
  * step. So one step is one answer, and its correction if it needs one.
  */
private[game] object StructuredAgent {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** Model steps for one answer (llm4s's one correction is part of the same step). */
  val MaxModelCalls: Int = 1

  /** The answer of agent `id` to `request`, or None when it was cut off at `maxOutputTokens` (llm4s does not decode an
    * answer the model's output limit cut short). The agent's thread is forgotten once it has answered.
    */
  def ask[A: ReadWriter](
    id: String,
    systemPrompt: String,
    schema: ObjectSchema[A],
    request: String,
    maxOutputTokens: Int,
    tracing: Option[Tracing],
    reasoning: Option[ReasoningEffort]
  )(using client: LLMClient): Result[Option[A]] = {
    val builder = Agent
      .builder(id, client)
      .withSystemPrompt(systemPrompt)
      .withMaxSteps(MaxModelCalls)
      .withCompletionOptions(CompletionOptions().withMaxTokens(maxOutputTokens).withReasoning(reasoning))
      .withOutput(schema)
    for {
      agent <- tracing.fold(builder)(builder.withTracing).build()
      result <- agent.run(request)
      _ = logger.info(s"$id finished ${result.status.productPrefix}: ${result.runUsage}")
      _ = agent.forget(result.threadId)
      answer <- result.status match {
        case AgentStatus.Truncated(_) => Right(None)
        case _ => result.decodeOutput[A].map(Some(_))
      }
    } yield answer
  }
}
