package org.llm4s.szork.media

import java.nio.file.Files
import org.slf4j.{Logger, LoggerFactory}
import org.llm4s.szork.config.{ConfigReader, EnvLoader}
import org.llm4s.security.Secret
import org.llm4s.speech.AudioInput
import org.llm4s.speech.config.STTConfig
import org.llm4s.szork.api.SpeechConfig
import org.llm4s.speech.SpeechProviderSelector
import org.llm4s.speech.stt.{SpeechToText => SttClient}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

/** Voice commands via llm4s-speech's speech-to-text client (OpenAI Whisper), chosen by `SpeechProviderSelector`.
  *
  * Configured by OPENAI_API_KEY and the [[SpeechConfig]] model.
  */
final class SpeechToText private (client: SttClient) {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** Transcribes a browser recording. The bytes are uploaded as a file named like `filename`, so Whisper reads the
    * recording's real format (usually webm) rather than treating it as WAV.
    */
  def transcribeBytes(audioBytes: Array[Byte], filename: String = "audio.webm"): SzorkResult[String] =
    if (audioBytes.isEmpty)
      Left(ValidationError(List("No audio data received. Please hold the record button to record audio.")))
    else {
      SpeechToText.extensionOf(filename).flatMap { extension =>
        val file = Files.createTempFile("szork-voice-", extension)
        try {
          Files.write(file, audioBytes)
          client
            .transcribe(AudioInput.FileAudio(file))
            .map(_.text.trim)
            .left
            .map(error => NetworkError(s"Transcription failed: ${error.message}", "openai", retryable = true))
            .map { text =>
              logger.info(s"Transcribed ${audioBytes.length} bytes: $text")
              text
            }
        } finally Files.deleteIfExists(file)
      }
    }
}

object SpeechToText {
  private val Extension = """\.[A-Za-z0-9]{1,10}""".r

  /** The temporary file's suffix for a recording called `filename` (".webm" when it has none), or a ValidationError if
    * the name's extension is not a plain one: it becomes part of a file name, so it may not hold a path separator.
    */
  private[media] def extensionOf(filename: String): SzorkResult[String] =
    filename.lastIndexOf('.') match {
      case -1 => Right(".webm")
      case at =>
        val extension = filename.substring(at)
        Either.cond(
          Extension.matches(extension),
          extension,
          ValidationError(List("The recording's file name has an unusable extension.")))
    }

  /** A client for `speech`'s model, or a ConfigurationError if OPENAI_API_KEY is not set in `reader`. */
  def create(speech: SpeechConfig, reader: ConfigReader = EnvLoader): SzorkResult[SpeechToText] =
    reader.get("OPENAI_API_KEY").filter(_.nonEmpty).toRight(ConfigurationError("OPENAI_API_KEY is not set")).flatMap {
      key =>
        val config = STTConfig(
          provider = "openai",
          model = speech.sttModel,
          apiKey = Secret(key),
          baseUrl = STTConfig.DEFAULT_OPENAI_BASE_URL
        )
        SpeechProviderSelector
          .getSTTClient(config)
          .left
          .map(error => ConfigurationError(s"Speech-to-text unavailable: ${error.message}"))
          .map(new SpeechToText(_))
    }
}
