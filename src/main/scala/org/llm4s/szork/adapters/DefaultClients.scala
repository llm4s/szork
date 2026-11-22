package org.llm4s.szork.adapters

import org.llm4s.szork.api.{MediaNetworkConfig, MusicPollConfig, SpeechConfig}
import org.llm4s.szork.media.{MusicGeneration, TextToSpeech, SpeechToText}
import org.llm4s.szork.spi._
import org.llm4s.szork.error.ErrorHandling._

class DefaultTTSClient(speech: SpeechConfig) extends TTSClient {
  // A missing key is reported on use, as an error, rather than thrown here
  private val impl = TextToSpeech.create(speech)
  override def synthesizeToBase64(text: String, voice: String): SzorkResult[String] =
    impl.flatMap(_.synthesizeToBase64(text, voice))
}

class DefaultSTTClient(speech: SpeechConfig) extends STTClient {
  private val impl = SpeechToText.create(speech)
  override def transcribeBytes(audioBytes: Array[Byte], filename: String): SzorkResult[String] =
    impl.flatMap(_.transcribeBytes(audioBytes, filename))
}

class DefaultMusicClient(net: MediaNetworkConfig, poll: MusicPollConfig) extends MusicClient {
  private val impl = MusicGeneration(net, poll)
  override def isAvailable: Boolean = impl.isAvailable
  override def generate(
    mood: String,
    context: String,
    gameId: Option[String],
    locationId: Option[String]): SzorkResult[String] =
    // Scenes name one of the Dungeon Master's moods; anything else plays the default mood
    impl.generateMusicWithCache(MusicGeneration.MusicMoods.orDefault(mood), context, gameId, locationId)
}
