package org.llm4s.szork.stubs

import org.llm4s.szork.spi._

class FakeTTSClient extends TTSClient {
  override def synthesizeToBase64(text: String, voice: String): Either[String, String] = Right("FAKE_AUDIO_BASE64")
}

class FakeSTTClient extends STTClient {
  override def transcribeBytes(audioBytes: Array[Byte], filename: String): Either[String, String] = Right("north")
}

class FakeImageClient extends ImageClient {
  override def generateScene(prompt: String, style: String, gameId: Option[String], locationId: Option[String]): Either[String, String] = Right("FAKE_IMAGE_BASE64")
}

class FakeMusicClient extends MusicClient {
  override def isAvailable: Boolean = true
  override def generate(mood: String, context: String, gameId: Option[String], locationId: Option[String]): Either[String, String] = Right("FAKE_MUSIC_BASE64")
}

