package org.llm4s.szork.media

import java.nio.file.Files
import org.slf4j.{Logger, LoggerFactory}
import org.llm4s.config.{ConfigReader, EnvLoader}
import org.llm4s.security.Secret
import org.llm4s.speech.AudioInput
import org.llm4s.speech.config.STTConfig
import org.llm4s.speech.stt.provider.OpenAISTTClient
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

/** Voice commands via llm4s-speech's OpenAI speech-to-text (Whisper) client.
  *
  * Configured by OPENAI_API_KEY and SZORK_STT_MODEL (default whisper-1).
  */
final class SpeechToText private (client: OpenAISTTClient) {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** Transcribes a browser recording. The bytes are uploaded as a file named like `filename`, so Whisper reads the
    * recording's real format (usually webm) rather than treating it as WAV.
    */
  def transcribeBytes(audioBytes: Array[Byte], filename: String = "audio.webm"): SzorkResult[String] =
    if (audioBytes.isEmpty)
      Left(ValidationError(List("No audio data received. Please hold the record button to record audio.")))
    else {
      val extension = filename.lastIndexOf('.') match {
        case -1 => ".webm"
        case at => filename.substring(at)
      }
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

object SpeechToText {

  /** A client from configuration, or a ConfigurationError if OPENAI_API_KEY is not set. */
  def create(reader: ConfigReader = EnvLoader): SzorkResult[SpeechToText] =
    reader.get("OPENAI_API_KEY").filter(_.nonEmpty).toRight(ConfigurationError("OPENAI_API_KEY is not set")).map {
      key =>
        val config = STTConfig(
          provider = "openai",
          model = reader.get("SZORK_STT_MODEL").filter(_.nonEmpty).getOrElse(STTConfig.DEFAULT_OPENAI_MODEL),
          apiKey = Secret(key),
          baseUrl = STTConfig.DEFAULT_OPENAI_BASE_URL
        )
        new SpeechToText(new OpenAISTTClient(config))
    }
}
