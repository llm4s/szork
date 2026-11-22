package org.llm4s.szork.api

import org.slf4j.LoggerFactory
import org.llm4s.config.{EnvLoader, Llm4sConfig}
import org.llm4s.trace.Tracing

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
      // llm4s reads its settings through Typesafe config, which sees the process environment and system
      // properties but not Szork's .env file; values from .env reach it as system properties.
      variables.foreach { case (key, value) =>
        if (Prefixes.exists(key.startsWith) && sys.env.get(key).isEmpty && sys.props.get(key).isEmpty)
          sys.props(key) = value
      }
      com.typesafe.config.ConfigFactory.invalidateCaches()
      Llm4sConfig.tracing() match {
        case Right(settings) =>
          logger.info(s"Agent tracing enabled: $mode")
          Some(Tracing.create(settings))
        case Left(error) =>
          logger.warn(s"Agent tracing disabled: ${error.message}")
          None
      }
    }
}
