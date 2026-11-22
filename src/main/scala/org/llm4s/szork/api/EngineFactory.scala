package org.llm4s.szork.api

import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.llmconnect.LLMClient
import org.llm4s.szork.adapters.{DefaultMusicClient, DefaultTTSClient}
import org.llm4s.szork.game.{AdventureOutline, GameEngine, PlayerInputGuardrail, SubagentSettings}
import org.llm4s.szork.spi.{ImageClient, SystemClock}
import org.llm4s.trace.Tracing
import org.slf4j.LoggerFactory

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

  /** Engines sharing the server's `llmClient`, `tracing` and scene `imageClient` (None: no images), with the default
    * speech and music clients set up by `config`, and its Dungeon Master context window. With
    * `config.inputGuardrailEnabled`, engines check player commands with the [[PlayerInputGuardrail]] (it is stateless,
    * so they share it). Engines offer the Dungeon Master the subagents `config.subagents` turns on (see
    * [[subagentSettings]]).
    */
  def default(
    llmClient: LLMClient,
    tracing: Option[Tracing],
    config: SzorkConfig,
    imageClient: Option[ImageClient]
  ): EngineFactory = {
    val guardrails: Seq[InputGuardrail] = if (config.inputGuardrailEnabled) PlayerInputGuardrail.default else Nil
    val subagents = subagentSettings(config, llmClientFor = SzorkConfig.getLLMClient(_))
    (sessionId, theme, artStyle, outline, features) =>
      Right(
        new GameEngine(
          sessionId = sessionId,
          theme = theme,
          artStyle = artStyle,
          adventureOutline = outline,
          clock = SystemClock,
          ttsClient = Option.when(features.tts)(new DefaultTTSClient(config.speech)),
          imageClient = imageClient.filter(_ => features.image),
          musicClient = Option.when(features.music)(new DefaultMusicClient(config.mediaNetwork, config.musicPoll)),
          tracing = tracing,
          contextWindow = config.dmContextWindow,
          inputGuardrails = guardrails,
          subagents = subagents
        )(using llmClient))
  }

  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** The subagents `config.subagents` turns on. With a subagent model, they call a client for that model on the Dungeon
    * Master's provider, made with `llmClientFor`; if it cannot be made they use the Dungeon Master's client.
    */
  def subagentSettings(
    config: SzorkConfig,
    llmClientFor: SzorkConfig => Either[String, LLMClient]
  ): SubagentSettings = {
    val client = for {
      model <- config.subagents.model
      llm <- config.llmConfig
      client <- llmClientFor(config.copy(llmConfig = Some(llm.copy(model = Some(model))))) match {
        case Right(c) =>
          logger.info(s"Dungeon Master subagents use model $model")
          Some(c)
        case Left(error) =>
          logger.warn(s"Subagent model $model unavailable, the subagents use the Dungeon Master's: $error")
          None
      }
    } yield client
    SubagentSettings(npcDialogue = config.subagents.npcDialogue, hints = config.subagents.hints, client = client)
  }
}
