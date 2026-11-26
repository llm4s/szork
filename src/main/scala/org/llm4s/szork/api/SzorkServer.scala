package org.llm4s.szork.api

import org.slf4j.LoggerFactory

/** Starts the server from the environment's configuration (see [[SzorkConfig.load]]). */
object SzorkServer {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  def main(args: Array[String]): Unit = {
    import SzorkConfig.ConfigOps
    val config = SzorkConfig.load()
    config.logConfiguration(logger)
    config.warnings.foreach(warning => logger.warn(warning))
    config.validate().left.foreach { error =>
      logger.error("The server cannot start with this configuration:")
      error.message.split("\n").foreach(line => logger.error(s"  - $line"))
      sys.exit(1)
    }

    SzorkApp.fromConfig(config) match {
      case Left(error) =>
        // Without an LLM there is no game to play
        logger.error(s"Failed to initialize LLM client: $error")
        sys.exit(1)
      case Right(app) =>
        app.start()
        sys.addShutdownHook(app.stop())
        logger.info(s"Szork server started on http://${config.host}:${config.port}")
    }
  }
}
