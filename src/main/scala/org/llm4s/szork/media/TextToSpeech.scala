package org.llm4s.szork.media

import java.util.Base64
import org.slf4j.{Logger, LoggerFactory}
import org.llm4s.config.{ConfigReader, EnvLoader}
import org.llm4s.security.Secret
import org.llm4s.speech.AudioFormat
import org.llm4s.speech.config.TTSConfig
import org.llm4s.szork.api.SpeechConfig
import org.llm4s.speech.tts.TTSOptions
import org.llm4s.speech.tts.provider.OpenAITTSClient
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

/** Narration via llm4s-speech's OpenAI text-to-speech client.
  *
  * Configured by OPENAI_API_KEY and the [[SpeechConfig]] model and voice.
  */
final class TextToSpeech private (client: OpenAITTSClient, val defaultVoice: String) {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** Speaks `text` as MP3. */
  def synthesize(text: String, voice: String = defaultVoice): SzorkResult[Array[Byte]] = {
    logger.info(s"Synthesizing speech (${text.length} chars, voice $voice)")
    client
      .synthesize(text, TTSOptions(voice = Some(voice), outputFormat = AudioFormat.Mp3))
      .map(_.data)
      .left
      .map(error => AudioGenerationError(s"TTS failed: ${error.message}", retryable = true))
  }

  def synthesizeToBase64(text: String, voice: String = defaultVoice): SzorkResult[String] =
    synthesize(text, voice).map(Base64.getEncoder.encodeToString)
}

object TextToSpeech {

  /** A client for `speech`'s model and voice, or a ConfigurationError if OPENAI_API_KEY is not set in `reader`. */
  def create(speech: SpeechConfig, reader: ConfigReader = EnvLoader): SzorkResult[TextToSpeech] =
    reader.get("OPENAI_API_KEY").filter(_.nonEmpty).toRight(ConfigurationError("OPENAI_API_KEY is not set")).map {
      key =>
        val config = TTSConfig(
          provider = "openai",
          model = speech.ttsModel,
          voice = speech.ttsVoice,
          apiKey = Secret(key),
          baseUrl = TTSConfig.DEFAULT_OPENAI_BASE_URL
        )
        new TextToSpeech(new OpenAITTSClient(config), speech.ttsVoice)
    }
}
