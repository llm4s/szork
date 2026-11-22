package org.llm4s.szork.api

import org.llm4s.szork.adapters.{DefaultImageClient, DefaultMusicClient, DefaultTTSClient}
import org.llm4s.szork.game.{AdventureOutline, GameEngine}
import org.llm4s.szork.spi.SystemClock

/** Creates the engine for a new or loaded game, with the media clients its session's features call for. */
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

  /** Engines using the configured LLM and the default media clients. */
  val default: EngineFactory = (sessionId, theme, artStyle, outline, features) =>
    SzorkConfig.getLLMClient().map { llmClient =>
      new GameEngine(
        sessionId = sessionId,
        theme = theme,
        artStyle = artStyle,
        adventureOutline = outline,
        clock = SystemClock,
        ttsClient = Option.when(features.tts)(new DefaultTTSClient()),
        imageClient = Option.when(features.image)(new DefaultImageClient()),
        musicClient = Option.when(features.music)(new DefaultMusicClient())
      )(llmClient)
    }
}
