package org.llm4s.szork.api

import org.llm4s.szork.config.ConfigReader
import org.llm4s.speech.config.{STTConfig, TTSConfig}
import org.llm4s.speech.stt.STTOptions

/** The OpenAI speech models, the narration voice and the language voice commands are spoken in. Part of
  * [[SzorkConfig]]: SZORK_TTS_MODEL, SZORK_TTS_VOICE, SZORK_STT_MODEL and SZORK_STT_LANGUAGE.
  *
  * @param sttLanguage
  *   the BCP 47 tag the transcriber is told (`en`, `fr-FR`), or None to let it guess from each recording - which, on a
  *   short or noisy one, can be a language nobody spoke
  */
case class SpeechConfig(
  ttsModel: String = TTSConfig.DEFAULT_OPENAI_MODEL,
  ttsVoice: String = SpeechConfig.DefaultVoice,
  sttModel: String = STTConfig.DEFAULT_OPENAI_MODEL,
  sttLanguage: Option[String] = Some(SpeechConfig.DefaultLanguage)
)

object SpeechConfig {
  val DefaultVoice: String = "nova"
  val DefaultLanguage: String = "en"

  /** The SZORK_STT_LANGUAGE value that leaves the language to the transcriber. */
  val DetectLanguage: String = "auto"

  /** Why `reader`'s SZORK_STT_LANGUAGE cannot be used, if it cannot: it is neither `auto` nor a BCP 47 tag. */
  def languageProblem(reader: ConfigReader): Option[String] =
    reader
      .get("SZORK_STT_LANGUAGE")
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(_.equalsIgnoreCase(DetectLanguage))
      .flatMap { tag =>
        STTOptions.validateBcp47(tag).left.toOption.map { _ =>
          s"SZORK_STT_LANGUAGE '$tag' is not a language tag: use one such as en or fr-FR, or $DetectLanguage"
        }
      }

  /** The speech settings in `reader`; an empty value is ignored. */
  def load(reader: ConfigReader): SpeechConfig = {
    def setting(key: String): Option[String] = reader.get(key).map(_.trim).filter(_.nonEmpty)
    val defaults = SpeechConfig()
    SpeechConfig(
      ttsModel = setting("SZORK_TTS_MODEL").getOrElse(defaults.ttsModel),
      ttsVoice = setting("SZORK_TTS_VOICE").getOrElse(defaults.ttsVoice),
      sttModel = setting("SZORK_STT_MODEL").getOrElse(defaults.sttModel),
      sttLanguage = setting("SZORK_STT_LANGUAGE").fold(defaults.sttLanguage) { tag =>
        Option.unless(tag.equalsIgnoreCase(DetectLanguage))(tag)
      }
    )
  }
}
