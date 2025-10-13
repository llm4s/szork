package org.llm4s.szork.spi

import org.llm4s.szork.error.ErrorHandling._

trait Clock {
  def now(): Long
}
object SystemClock extends Clock { def now(): Long = System.currentTimeMillis() }

trait Rng {
  def uuid(): String
}
object JavaRng extends Rng { def uuid(): String = java.util.UUID.randomUUID().toString }

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
  def generate(
    mood: String,
    context: String,
    gameId: Option[String],
    locationId: Option[String]): SzorkResult[String]
}

trait GameStore {
  def save(state: Any): SzorkResult[Unit]
  def load(id: String): SzorkResult[Any]
}
