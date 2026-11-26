package org.llm4s.szork.api

import org.slf4j.LoggerFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.szork.config.EnvLoader
import org.llm4s.trace.Tracing
import com.typesafe.config.ConfigFactory
import pureconfig.ConfigSource
import scala.jdk.CollectionConverters.*

/** Tracing for the Dungeon Master agent, configured the llm4s way.
  *
  * Off unless TRACING_MODE is set: `console` prints traces to stdout, and `langfuse` (with LANGFUSE_PUBLIC_KEY,
  * LANGFUSE_SECRET_KEY and LANGFUSE_URL) sends them to Langfuse via llm4s-observability.
  */
object AgentTracing {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val Prefixes = List("TRACING_", "LANGFUSE_", "OTEL_")

  /** Tracing from the server's environment (.env and the process environment). */
  lazy val fromEnvironment: Option[Tracing] = create(EnvLoader.entries)

  /** Tracing for `variables`, or None when TRACING_MODE is unset or `none`. */
  def create(variables: Map[String, String]): Option[Tracing] =
    variables.get("TRACING_MODE").map(_.trim.toLowerCase).filter(m => m.nonEmpty && m != "none").flatMap { mode =>
      // llm4s's reference.conf reads TRACING_MODE, LANGFUSE_* and OTEL_* as top-level substitutions, so the
      // variables go in as the application config and no global state (system properties) is touched.
      val settings = ConfigFactory.parseMap(variables.filter((key, _) => Prefixes.exists(key.startsWith)).asJava)
      Llm4sConfig.tracing(ConfigSource.fromConfig(ConfigFactory.load(settings))).flatMap(Tracing.fromSettings) match {
        case Right(tracing) =>
          logger.info(s"Agent tracing enabled: $mode")
          Some(tracing)
        case Left(error) =>
          logger.warn(s"Agent tracing disabled: ${error.message}")
          None
      }
    }
}
