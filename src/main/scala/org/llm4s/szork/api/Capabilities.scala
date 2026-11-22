package org.llm4s.szork.api

import org.llm4s.config.{ConfigReader, EnvLoader}

/** The optional features this server can provide: enabled by configuration and with the credentials they need. */
final case class Capabilities(tts: Boolean, stt: Boolean, image: Boolean, music: Boolean)

object Capabilities {
  def from(config: SzorkConfig, reader: ConfigReader = EnvLoader): Capabilities = {
    val openAIKey = reader.get("OPENAI_API_KEY").exists(_.nonEmpty)
    val replicateKey = reader.get("REPLICATE_API_KEY").exists(_.nonEmpty)
    Capabilities(
      tts = config.ttsEnabled && openAIKey,
      stt = config.sttEnabled && openAIKey,
      image = config.imageGenerationEnabled && config.imageProvider != ImageProvider.None &&
        ImageProvider.imageCredsAvailable(config.imageProvider, reader),
      music = config.musicEnabled && replicateKey
    )
  }
}
