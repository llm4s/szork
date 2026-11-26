package org.llm4s.szork.spi

import org.llm4s.szork.error.ErrorHandling.*

trait Clock {
  def now(): Long
}
object SystemClock extends Clock { def now(): Long = System.currentTimeMillis() }

trait TTSClient {
  def synthesizeToBase64(text: String, voice: String): SzorkResult[String]
}

trait STTClient {
  def transcribeBytes(audioBytes: Array[Byte], filename: String = "audio.webm"): SzorkResult[String]
}

trait ImageClient {
  def generateScene(
    prompt: String,
    style: String,
    gameId: Option[String],
    locationId: Option[String]): SzorkResult[String]
}

trait MusicClient {
  def isAvailable: Boolean

  /** The track for `mood` (one of the Dungeon Master's music moods), as Base64 MP3. */
  def generate(mood: String): SzorkResult[String]
}
