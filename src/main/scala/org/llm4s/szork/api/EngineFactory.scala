package org.llm4s.szork.api

import org.llm4s.agent.ContextWindowConfig
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.llmconnect.LLMClient
import org.llm4s.szork.adapters.{DefaultMusicClient, DefaultTTSClient}
import org.llm4s.szork.game.{AdventureOutline, GameEngine, PlayerInputGuardrail}
import org.llm4s.szork.spi.{ImageClient, SystemClock}
import org.llm4s.trace.Tracing

/** Creates the engine for a new or loaded game, with the media clients `features` call for.
  *
  * The server asks for every feature it can provide, not just the ones the session starts with: a session's features
  * can change while it plays, and it only uses the clients its current features allow.
  */
trait EngineFactory {
  def create(
    sessionId: String,
    theme: Option[String],
    artStyle: Option[String],
    outline: Option[AdventureOutline],
    features: SessionFeatures
  ): Either[String, GameEngine]
}

object EngineFactory {

  /** Engines sharing the server's `llmClient`, `tracing`, `contextWindow` and scene `imageClient` (None: no images),
    * with the default speech and music clients. With `inputGuardrail`, engines check player commands with the
    * [[PlayerInputGuardrail]] (it is stateless, so they share it).
    */
  def default(
    llmClient: LLMClient,
    tracing: Option[Tracing],
    contextWindow: Option[ContextWindowConfig] = Some(GameEngine.DefaultContextWindow),
    imageClient: Option[ImageClient] = None,
    inputGuardrail: Boolean = true
  ): EngineFactory = {
    val guardrails: Seq[InputGuardrail] = if (inputGuardrail) PlayerInputGuardrail.default else Nil
    (sessionId, theme, artStyle, outline, features) =>
      Right(
        new GameEngine(
          sessionId = sessionId,
          theme = theme,
          artStyle = artStyle,
          adventureOutline = outline,
          clock = SystemClock,
          ttsClient = Option.when(features.tts)(new DefaultTTSClient()),
          imageClient = imageClient.filter(_ => features.image),
          musicClient = Option.when(features.music)(new DefaultMusicClient()),
          tracing = tracing,
          contextWindow = contextWindow,
          inputGuardrails = guardrails
        )(using llmClient))
  }
}
