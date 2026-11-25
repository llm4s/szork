package org.llm4s.szork.adapters

import org.llm4s.szork.api.{MediaNetworkConfig, MusicPollConfig, SpeechConfig}
import org.llm4s.szork.media.{MusicGeneration, TextToSpeech, SpeechToText}
import org.llm4s.szork.spi._
import org.llm4s.szork.error.ErrorHandling._

/** Narration through llm4s-speech. One client serves every game: build it once. */
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

/** Mood tracks from Replicate. One client serves every game: build it once. */
class DefaultMusicClient(net: MediaNetworkConfig, poll: MusicPollConfig) extends MusicClient {
  private val impl = MusicGeneration(net, poll)
  override def isAvailable: Boolean = impl.isAvailable
  // Scenes name one of the Dungeon Master's moods; anything else plays the default mood
  override def generate(mood: String): SzorkResult[String] = impl.generate(MusicGeneration.MusicMoods.orDefault(mood))
}
