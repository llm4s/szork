package org.llm4s.szork

import org.llm4s.config.ConfigReader
import org.llm4s.szork.api.MediaNetworkConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MediaNetworkConfigSpec extends AnyFunSuite with Matchers {

  /** A deterministic in-memory ConfigReader backed by a Map. */
  private final case class FakeReader(values: Map[String, String]) extends ConfigReader {
    override def get(key: String): Option[String] = values.get(key)
  }

  test("load with no relevant env vars returns the defaults") {
    val config = MediaNetworkConfig.load(FakeReader(Map.empty))
    config.connectTimeoutMs shouldBe 10000
    config.readTimeoutMs shouldBe 30000
  }

  test("load uses parsed values when both env vars supply valid integers") {
    val reader = FakeReader(
      Map(
        "SZORK_MEDIA_CONNECT_TIMEOUT_MS" -> "1234",
        "SZORK_MEDIA_READ_TIMEOUT_MS" -> "56789"
      ))
    val config = MediaNetworkConfig.load(reader)
    config.connectTimeoutMs shouldBe 1234
    config.readTimeoutMs shouldBe 56789
  }

  test("load falls back to the connect default when connect value is unparseable") {
    val reader = FakeReader(
      Map(
        "SZORK_MEDIA_CONNECT_TIMEOUT_MS" -> "abc",
        "SZORK_MEDIA_READ_TIMEOUT_MS" -> "56789"
      ))
    val config = MediaNetworkConfig.load(reader)
    config.connectTimeoutMs shouldBe 10000
    config.readTimeoutMs shouldBe 56789
  }

  test("load falls back to the read default when read value is unparseable") {
    val reader = FakeReader(
      Map(
        "SZORK_MEDIA_CONNECT_TIMEOUT_MS" -> "1234",
        "SZORK_MEDIA_READ_TIMEOUT_MS" -> "xyz"
      ))
    val config = MediaNetworkConfig.load(reader)
    config.connectTimeoutMs shouldBe 1234
    config.readTimeoutMs shouldBe 30000
  }

  test("default case class fields equal the historical literals") {
    val config = MediaNetworkConfig()
    config.connectTimeoutMs shouldBe 10000
    config.readTimeoutMs shouldBe 30000
  }
}
