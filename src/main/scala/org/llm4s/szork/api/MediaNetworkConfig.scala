package org.llm4s.szork.api

import org.llm4s.config.{ConfigReader, EnvLoader}
import scala.util.Try

/** Network timeouts shared by the media HTTP clients (TTS, STT, music).
  *
  * Defaults equal the historical inline literals, so default behaviour is
  * unchanged. Both values are env-overridable for per-deployment tuning.
  */
case class MediaNetworkConfig(
  connectTimeoutMs: Int = 10000,
  readTimeoutMs: Int = 30000
)

object MediaNetworkConfig {
  lazy val instance: MediaNetworkConfig = load()

  def load(reader: ConfigReader = EnvLoader): MediaNetworkConfig = {
    val defaults = MediaNetworkConfig()

    val connectTimeoutMs = reader
      .get("SZORK_MEDIA_CONNECT_TIMEOUT_MS")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(defaults.connectTimeoutMs)

    val readTimeoutMs = reader
      .get("SZORK_MEDIA_READ_TIMEOUT_MS")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(defaults.readTimeoutMs)

    MediaNetworkConfig(
      connectTimeoutMs = connectTimeoutMs,
      readTimeoutMs = readTimeoutMs
    )
  }
}
