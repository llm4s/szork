package org.llm4s.szork.api

import org.llm4s.config.ConfigReader

/** Network timeouts for the media HTTP calls Szork makes itself (music generation on Replicate); the llm4s speech and
  * image clients use their own. Part of [[SzorkConfig]]: SZORK_MEDIA_CONNECT_TIMEOUT_MS and
  * SZORK_MEDIA_READ_TIMEOUT_MS.
  */
case class MediaNetworkConfig(
  connectTimeoutMs: Int = 10000,
  readTimeoutMs: Int = 30000
)

object MediaNetworkConfig {

  /** The timeouts in `reader`; a value that is not a number is ignored. */
  def load(reader: ConfigReader): MediaNetworkConfig = {
    val defaults = MediaNetworkConfig()
    MediaNetworkConfig(
      connectTimeoutMs =
        reader.get("SZORK_MEDIA_CONNECT_TIMEOUT_MS").flatMap(_.trim.toIntOption).getOrElse(defaults.connectTimeoutMs),
      readTimeoutMs =
        reader.get("SZORK_MEDIA_READ_TIMEOUT_MS").flatMap(_.trim.toIntOption).getOrElse(defaults.readTimeoutMs)
    )
  }
}
