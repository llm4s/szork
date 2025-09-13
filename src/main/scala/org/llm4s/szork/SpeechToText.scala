package org.llm4s.szork
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

import org.slf4j.LoggerFactory
import requests._
import org.llm4s.config.EnvLoader
import java.io.File
import java.nio.file.Files

class SpeechToText {
  private implicit val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val apiKey = EnvLoader
    .get("OPENAI_API_KEY")
    .getOrElse(
      throw new IllegalStateException("OPENAI_API_KEY not found in environment")
    )

  def transcribe(audioFile: File): SzorkResult[String] = {
    logger.info(s"Transcribing audio file: ${audioFile.getName}")

    try {
      val response = post(
        "https://api.openai.com/v1/audio/transcriptions",
        headers = Map(
          "Authorization" -> s"Bearer $apiKey"
        ),
        data = MultiPart(
          MultiItem("file", audioFile, audioFile.getName),
          MultiItem("model", "whisper-1")
        ),
        readTimeout = 30000,
        connectTimeout = 10000
      )

      if (response.statusCode == 200) {
        val json = ujson.read(response.text())
        val transcription = json("text").str
        logger.info(s"Transcription successful: $transcription")
        Right(transcription)
      } else {
        val errorText = response.text()
        val error = s"Transcription failed with status ${response.statusCode}: $errorText"
        logger.error(error)
        logger.error(
          s"File info - name: ${audioFile.getName}, size: ${audioFile.length()} bytes, exists: ${audioFile.exists()}")
        Left(NetworkError(error, "openai", retryable = response.statusCode >= 500))
      }
    } catch {
      case e: Exception =>
        logger.error("Error during transcription", e)
        Left(AudioGenerationError(s"Transcription error: ${e.getMessage}", Some(e), retryable = true))
    }
  }

  def transcribeBytes(audioBytes: Array[Byte], filename: String = "audio.webm"): SzorkResult[String] = {
    // Check if audio is empty
    if (audioBytes.isEmpty) {
      logger.warn("Received empty audio data")
      return Left(ValidationError(List("No audio data received. Please hold the record button to record audio.")))
    }

    // Create temp file with proper extension
    val extension = if (filename.contains(".")) filename.substring(filename.lastIndexOf(".")) else ".webm"
    val tempFile = Files.createTempFile("szork_audio_", extension)
    try {
      Files.write(tempFile, audioBytes)
      logger.info(s"Created temp file: ${tempFile.toString}, size: ${audioBytes.length} bytes")

      // Debug: Check file magic bytes
      if (audioBytes.length > 4) {
        val magic = audioBytes.take(4).map(b => f"$b%02X").mkString(" ")
        logger.info(s"File magic bytes: $magic")
      }

      transcribe(tempFile.toFile)
    } finally {
      val keep = EnvLoader.get("SZORK_DEBUG_STT_TMP").exists(_.toLowerCase == "true")
      if (keep) {
        logger.info(s"Keeping temp file for debugging: ${tempFile.toString}")
      } else {
        try Files.deleteIfExists(tempFile)
        catch { case e: Exception => logger.warn(s"Failed to delete temp file ${tempFile.toString}: ${e.getMessage}") }
      }
    }
  }
}

object SpeechToText {
  def apply(): SpeechToText = new SpeechToText()
}
