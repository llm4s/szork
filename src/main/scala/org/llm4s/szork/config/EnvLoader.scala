package org.llm4s.szork.config

import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Where Szork reads its settings from. Not part of llm4s (whose own settings are `org.llm4s.config.Llm4sConfig`). */
trait ConfigReader {
  def get(key: String): Option[String]
  def getOrElse(key: String, default: String): String = get(key).getOrElse(default)
}

/** Reads `.env` (from the working directory or its parent) over the process environment. */
object EnvLoader extends ConfigReader {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass.getSimpleName)

  private lazy val dotenv: Dotenv = {
    val currentDir = Paths.get("").toAbsolutePath
    // The filesystem root has no parent
    val possiblePaths: List[Path] =
      List(currentDir, currentDir.getParent).filter(_ != null).map(_.resolve(".env"))

    val loaded = possiblePaths.find(Files.exists(_)) match {
      case Some(path) =>
        logger.info(s"Loading .env file from: ${path.toAbsolutePath}")
        Dotenv.configure().directory(path.getParent.toString).ignoreIfMissing().load()
      case None =>
        logger.warn(s"No .env file found in any of: ${possiblePaths.mkString(", ")}")
        logger.warn("Falling back to system environment variables only")
        Dotenv.configure().ignoreIfMissing().load()
    }
    logger.info("Environment variables loaded")
    loaded
  }

  def get(key: String): Option[String] =
    Option(dotenv.get(key))

  /** Every variable: those in .env and the process environment. */
  def entries: Map[String, String] =
    dotenv.entries().asScala.map(e => e.getKey -> e.getValue).toMap
}
