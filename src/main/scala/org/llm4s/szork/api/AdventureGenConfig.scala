package org.llm4s.szork.api

import org.llm4s.config.{ConfigReader, EnvLoader}
import scala.util.Try

/** Retry/back-off behaviour for adventure-outline generation.
  *
  * Defaults equal the historical inline literals, so default behaviour is unchanged. Both values are env-overridable
  * for per-deployment tuning.
  */
case class AdventureGenConfig(
  maxRetries: Int = 2,
  retryBackoffMs: Int = 500
)

object AdventureGenConfig {
  lazy val instance: AdventureGenConfig = load()

  /** Loads the adventure-generation retry configuration from the environment, falling back to the historical inline
    * defaults when an override is absent or unparseable.
    *
    * @param reader
    *   the configuration source to read environment overrides from; defaults to the process environment loader
    * @return
    *   a fully-resolved AdventureGenConfig whose fields equal the environment overrides where present and the
    *   historical defaults otherwise
    */
  def load(reader: ConfigReader = EnvLoader): AdventureGenConfig = {
    val defaults = AdventureGenConfig()

    val maxRetries = reader
      .get("SZORK_ADVENTURE_GEN_MAX_RETRIES")
      .flatMap(v => Try(v.toInt).toOption)
      .filter(_ >= 0)
      .getOrElse(defaults.maxRetries)

    val retryBackoffMs = reader
      .get("SZORK_ADVENTURE_GEN_RETRY_BACKOFF_MS")
      .flatMap(v => Try(v.toInt).toOption)
      .filter(_ >= 0)
      .getOrElse(defaults.retryBackoffMs)

    AdventureGenConfig(
      maxRetries = maxRetries,
      retryBackoffMs = retryBackoffMs
    )
  }
}
