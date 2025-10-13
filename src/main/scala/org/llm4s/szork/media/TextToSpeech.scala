package org.llm4s.szork.media

import org.slf4j.LoggerFactory
import requests._
import org.llm4s.config.EnvLoader
import java.util.Base64
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

class TextToSpeech {
  private implicit val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val apiKey = EnvLoader
    .get("OPENAI_API_KEY")
    .getOrElse(
      throw new IllegalStateException("OPENAI_API_KEY not found in environment")
    )

  def synthesize(text: String, voice: String = "nova"): SzorkResult[Array[Byte]] = {
    logger.info(s"Synthesizing speech for text (${text.length} chars): ${text.take(50)}...")
    logger.debug(s"Using voice: $voice")

    try {
      val response = post(
        "https://api.openai.com/v1/audio/speech",
        headers = Map(
          "Authorization" -> s"Bearer $apiKey",
          "Content-Type" -> "application/json"
        ),
        data = ujson
          .Obj(
            "model" -> "tts-1",
            "input" -> text,
            "voice" -> voice,
            "response_format" -> "mp3"
          )
          .toString,
        readTimeout = 30000,
        connectTimeout = 10000
      )

      if (response.statusCode == 200) {
        logger.info(s"Speech synthesis successful, size: ${response.bytes.length} bytes")
        Right(response.bytes)
      } else {
        val errorMsg = s"TTS failed with status ${response.statusCode}: ${response.text()}"
        logger.error(errorMsg)
        Left(AudioGenerationError(errorMsg, retryable = response.statusCode >= 500))
      }
    } catch {
      case e: Exception =>
        logger.error("Error during text-to-speech", e)
        Left(AudioGenerationError(s"TTS error: ${e.getMessage}", Some(e), retryable = true))
    }
  }

  def synthesizeToBase64(text: String, voice: String = "nova"): SzorkResult[String] =
    synthesize(text, voice).map(bytes => Base64.getEncoder.encodeToString(bytes))
}

object TextToSpeech {
  def apply(): TextToSpeech = new TextToSpeech()

  // Available voices
  val VOICE_ALLOY = "alloy"
  val VOICE_ECHO = "echo"
  val VOICE_FABLE = "fable"
  val VOICE_ONYX = "onyx"
  val VOICE_NOVA = "nova"
  val VOICE_SHIMMER = "shimmer"
}
