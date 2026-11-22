package org.llm4s.szork.api

import org.llm4s.config.ConfigReader
import org.llm4s.speech.config.{STTConfig, TTSConfig}

/** The OpenAI speech models and narration voice. Part of [[SzorkConfig]]: SZORK_TTS_MODEL, SZORK_TTS_VOICE and
  * SZORK_STT_MODEL.
  */
case class SpeechConfig(
  ttsModel: String = TTSConfig.DEFAULT_OPENAI_MODEL,
  ttsVoice: String = SpeechConfig.DefaultVoice,
  sttModel: String = STTConfig.DEFAULT_OPENAI_MODEL
)

object SpeechConfig {
  val DefaultVoice: String = "nova"

  /** The speech settings in `reader`; an empty value is ignored. */
  def load(reader: ConfigReader): SpeechConfig = {
    def setting(key: String): Option[String] = reader.get(key).map(_.trim).filter(_.nonEmpty)
    val defaults = SpeechConfig()
    SpeechConfig(
      ttsModel = setting("SZORK_TTS_MODEL").getOrElse(defaults.ttsModel),
      ttsVoice = setting("SZORK_TTS_VOICE").getOrElse(defaults.ttsVoice),
      sttModel = setting("SZORK_STT_MODEL").getOrElse(defaults.sttModel)
    )
  }
}
