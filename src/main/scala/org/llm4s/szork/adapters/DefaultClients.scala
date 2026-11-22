package org.llm4s.szork.adapters

import org.llm4s.szork.media.{ImageGeneration, MusicGeneration, TextToSpeech, SpeechToText}
import org.llm4s.szork.spi._
import org.llm4s.szork.error.ErrorHandling._

class DefaultTTSClient extends TTSClient {
  // A missing key is reported on use, as an error, rather than thrown here
  private val impl = TextToSpeech.create()
  override def synthesizeToBase64(text: String, voice: String): SzorkResult[String] =
    impl.flatMap(_.synthesizeToBase64(text, voice))
}

class DefaultSTTClient extends STTClient {
  private val impl = SpeechToText.create()
  override def transcribeBytes(audioBytes: Array[Byte], filename: String): SzorkResult[String] =
    impl.flatMap(_.transcribeBytes(audioBytes, filename))
}

class DefaultImageClient extends ImageClient {
  private val impl = ImageGeneration()
  override def generateScene(
    prompt: String,
    style: String,
    gameId: Option[String],
    locationId: Option[String]): SzorkResult[String] =
    // The engine sends a fully styled prompt; `style` (the art style id) is not appended to it again
    impl.generateSceneWithCache(prompt, "", gameId, locationId)
}

class DefaultMusicClient extends MusicClient {
  private val impl = MusicGeneration()
  override def isAvailable: Boolean = impl.isAvailable
  override def generate(
    mood: String,
    context: String,
    gameId: Option[String],
    locationId: Option[String]): SzorkResult[String] =
    // Scenes name one of the Dungeon Master's moods; anything else plays the default mood
    impl.generateMusicWithCache(MusicGeneration.MusicMoods.orDefault(mood), context, gameId, locationId)
}

object DefaultClients {
  def createTTSClient(): Option[TTSClient] =
    Some(new DefaultTTSClient())

  def createSTTClient(): Option[STTClient] =
    Some(new DefaultSTTClient())

  def createImageClient(): Option[ImageClient] =
    Some(new DefaultImageClient())

  def createMusicClient(): Option[MusicClient] =
    Some(new DefaultMusicClient())
}
