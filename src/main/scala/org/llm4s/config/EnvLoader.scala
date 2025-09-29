package org.llm4s.config

import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.{Files, Paths}

trait ConfigReader {
  def get(key: String): Option[String]
  def getOrElse(key: String, default: String): String = get(key).getOrElse(default)

  def require(key: String): Either[ConfigError, String] = {
    get(key) match {
      case Some(value) => Right(value)
      case None => Left(ConfigError(s"Required configuration key not found: $key"))
    }
  }
}

case class ConfigError(message: String)

object EnvLoader extends ConfigReader {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass.getSimpleName)

  private lazy val dotenv: Dotenv = {
    // Try to find .env file in multiple locations
    val currentDir = Paths.get("").toAbsolutePath
    val parentDir = currentDir.getParent

    val possiblePaths = List(
      currentDir.resolve(".env"), // Current directory
      parentDir.resolve(".env") // Parent directory (for when running from szork/)
    )

    val existingPath = possiblePaths.find(Files.exists(_))

    existingPath match {
      case Some(path) =>
        logger.info(s"Loading .env file from: ${path.toAbsolutePath}")
        Dotenv
          .configure()
          .directory(path.getParent.toString)
          .ignoreIfMissing()
          .load()
      case None =>
        logger.warn(s"No .env file found in any of: ${possiblePaths.map(_.toAbsolutePath).mkString(", ")}")
        logger.warn("Falling back to system environment variables only")
        Dotenv
          .configure()
          .ignoreIfMissing()
          .load()
    }
  }

  logger.info(s"Environment variables loaded")

  def get(key: String): Option[String] =
    Option(dotenv.get(key))

  override def getOrElse(key: String, default: String): String =
    get(key).getOrElse(default)
}
