package org.llm4s.szork.api

import org.llm4s.szork.config.ConfigReader

/** How long music generation waits for a Replicate prediction: up to `maxAttempts` polls, `pollIntervalMs` apart. Part
  * of [[SzorkConfig]]: SZORK_MUSIC_POLL_MAX_ATTEMPTS and SZORK_MUSIC_POLL_INTERVAL_MS.
  */
case class MusicPollConfig(
  maxAttempts: Int = 30,
  pollIntervalMs: Int = 1000
)

object MusicPollConfig {

  /** The polling settings in `reader`; a value that is not a number is ignored. */
  def load(reader: ConfigReader): MusicPollConfig = {
    val defaults = MusicPollConfig()
    MusicPollConfig(
      maxAttempts =
        reader.get("SZORK_MUSIC_POLL_MAX_ATTEMPTS").flatMap(_.trim.toIntOption).getOrElse(defaults.maxAttempts),
      pollIntervalMs =
        reader.get("SZORK_MUSIC_POLL_INTERVAL_MS").flatMap(_.trim.toIntOption).getOrElse(defaults.pollIntervalMs)
    )
  }
}
