package org.llm4s.szork

import org.llm4s.config.ConfigReader
import org.llm4s.szork.api.MusicPollConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MusicPollConfigSpec extends AnyFunSuite with Matchers {

  /** A deterministic in-memory ConfigReader backed by a Map. */
  private final case class FakeReader(values: Map[String, String]) extends ConfigReader {
    override def get(key: String): Option[String] = values.get(key)
  }

  test("load with no relevant env vars returns the defaults") {
    val config = MusicPollConfig.load(FakeReader(Map.empty))
    config.maxAttempts shouldBe 30
    config.pollIntervalMs shouldBe 1000
  }

  test("load uses parsed values when both env vars supply valid integers") {
    val reader = FakeReader(
      Map(
        "SZORK_MUSIC_POLL_MAX_ATTEMPTS" -> "7",
        "SZORK_MUSIC_POLL_INTERVAL_MS" -> "250"
      ))
    val config = MusicPollConfig.load(reader)
    config.maxAttempts shouldBe 7
    config.pollIntervalMs shouldBe 250
  }

  test("load falls back to the maxAttempts default when its value is unparseable") {
    val reader = FakeReader(
      Map(
        "SZORK_MUSIC_POLL_MAX_ATTEMPTS" -> "abc",
        "SZORK_MUSIC_POLL_INTERVAL_MS" -> "250"
      ))
    val config = MusicPollConfig.load(reader)
    config.maxAttempts shouldBe 30
    config.pollIntervalMs shouldBe 250
  }

  test("load falls back to the pollIntervalMs default when its value is unparseable") {
    val reader = FakeReader(
      Map(
        "SZORK_MUSIC_POLL_MAX_ATTEMPTS" -> "7",
        "SZORK_MUSIC_POLL_INTERVAL_MS" -> "xyz"
      ))
    val config = MusicPollConfig.load(reader)
    config.maxAttempts shouldBe 7
    config.pollIntervalMs shouldBe 1000
  }

  test("default case class fields equal the historical literals") {
    val config = MusicPollConfig()
    config.maxAttempts shouldBe 30
    config.pollIntervalMs shouldBe 1000
  }
}
