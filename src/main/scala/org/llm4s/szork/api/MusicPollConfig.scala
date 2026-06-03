package org.llm4s.szork.api

import org.llm4s.config.{ConfigReader, EnvLoader}
import scala.util.Try

/** Polling behaviour for music generation (Replicate prediction polling).
  *
  * Defaults equal the historical inline literals, so default behaviour is unchanged. Both values are env-overridable
  * for per-deployment tuning.
  */
case class MusicPollConfig(
  maxAttempts: Int = 30,
  pollIntervalMs: Int = 1000
)

object MusicPollConfig {
  lazy val instance: MusicPollConfig = load()

  def load(reader: ConfigReader = EnvLoader): MusicPollConfig = {
    val defaults = MusicPollConfig()

    val maxAttempts = reader
      .get("SZORK_MUSIC_POLL_MAX_ATTEMPTS")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(defaults.maxAttempts)

    val pollIntervalMs = reader
      .get("SZORK_MUSIC_POLL_INTERVAL_MS")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(defaults.pollIntervalMs)

    MusicPollConfig(
      maxAttempts = maxAttempts,
      pollIntervalMs = pollIntervalMs
    )
  }
}
