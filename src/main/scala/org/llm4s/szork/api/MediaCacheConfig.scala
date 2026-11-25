package org.llm4s.szork.api

import org.llm4s.config.ConfigReader

/** The media cache ([[org.llm4s.szork.media.MediaCache]]): whether it is used, where it lives, and how it is pruned
  * (files unused for `ttlMs` are dropped, then the least recently used until it fits in `maxBytes`). Part of
  * [[SzorkConfig]]: SZORK_CACHE_ENABLED, SZORK_CACHE_DIR, SZORK_CACHE_TTL_MS and SZORK_CACHE_MAX_BYTES.
  */
case class MediaCacheConfig(
  enabled: Boolean = true,
  directory: String = "szork-cache",
  ttlMs: Long = MediaCacheConfig.DefaultTtlMs,
  maxBytes: Long = MediaCacheConfig.DefaultMaxBytes
)

object MediaCacheConfig {
  val DefaultTtlMs: Long = 7L * 24 * 60 * 60 * 1000 // 7 days
  val DefaultMaxBytes: Long = 500L * 1024 * 1024 // 500MB, for the whole cache

  /** The cache settings in `reader`; a size or age that is not a number is ignored. */
  def load(reader: ConfigReader): MediaCacheConfig = {
    val defaults = MediaCacheConfig()
    MediaCacheConfig(
      enabled = SzorkConfig.flag(reader, "SZORK_CACHE_ENABLED"),
      directory = reader.get("SZORK_CACHE_DIR").map(_.trim).filter(_.nonEmpty).getOrElse(defaults.directory),
      ttlMs = reader.get("SZORK_CACHE_TTL_MS").flatMap(_.trim.toLongOption).getOrElse(defaults.ttlMs),
      maxBytes = reader.get("SZORK_CACHE_MAX_BYTES").flatMap(_.trim.toLongOption).getOrElse(defaults.maxBytes)
    )
  }
}
