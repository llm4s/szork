package org.llm4s.szork.adapters

import org.llm4s.szork.{ImageGeneration, MusicGeneration, TextToSpeech, SpeechToText}
import org.llm4s.szork.spi._
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

class DefaultTTSClient extends TTSClient {
  private val impl = TextToSpeech()
  override def synthesizeToBase64(text: String, voice: String): SzorkResult[String] =
    impl.synthesizeToBase64(text, voice) // Already returns SzorkResult
}

class DefaultSTTClient extends STTClient {
  private val impl = SpeechToText()
  override def transcribeBytes(audioBytes: Array[Byte], filename: String): SzorkResult[String] =
    impl.transcribeBytes(audioBytes, filename) // Already returns SzorkResult
}

class DefaultImageClient extends ImageClient {
  private val impl = ImageGeneration()
  override def generateScene(
    prompt: String,
    style: String,
    gameId: Option[String],
    locationId: Option[String]): SzorkResult[String] =
    impl.generateSceneWithCache(prompt, style, gameId, locationId)
}

class DefaultMusicClient extends MusicClient {
  private val impl = MusicGeneration()
  override def isAvailable: Boolean = impl.isAvailable
  override def generate(
    mood: String,
    context: String,
    gameId: Option[String],
    locationId: Option[String]): SzorkResult[String] = {
    // Map mood string into MusicGeneration's moods
    val m = impl.detectMoodFromText(mood) // fallback; actual engine passes structured mood
    impl.generateMusicWithCache(m, context, gameId, locationId) // Already returns SzorkResult
  }
}
