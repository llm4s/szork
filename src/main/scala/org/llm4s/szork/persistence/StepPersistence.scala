package org.llm4s.szork.persistence

import org.llm4s.szork.game.{GameScene, AdventureGenerator}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import ujson._
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import org.slf4j.{Logger, LoggerFactory}
import scala.util.{Try, Using}
import scala.jdk.CollectionConverters._

/** Step-based game persistence.
  *
  * Unified persistence layer for both regular gameplay and debug sessions.
  * Each game is stored as a directory with numbered step subdirectories.
  *
  * Directory structure:
  * ```
  * szork-saves/{game-id}/
  *   game.json              # GameMetadata
  *   step-0001/
  *     metadata.json        # StepMetadata
  *     state.json           # GameState
  *     command.txt          # User command (if any)
  *     response.txt         # Narration text
  *     response.json        # Structured response
  *     messages.json        # Agent messages
  *     tool-calls.json      # Tool calls (if any)
  *     outline.json         # Adventure outline (step 1 only)
  *   step-0002/
  *     ...
  * ```
  */
object StepPersistence {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val SAVE_DIR = "szork-saves"

  /** Ensure the save directory exists. */
  private def ensureSaveDir(): Path = {
    val dir = Paths.get(SAVE_DIR)
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      logger.info(s"Created save directory: $SAVE_DIR")
    }
    dir
  }

  /** Get the game directory path. */
  private def getGameDir(gameId: String): Path = {
    ensureSaveDir()
    Paths.get(SAVE_DIR, gameId)
  }

  /** Get the step directory path. */
  private def getStepDir(gameId: String, stepNumber: Int): Path = {
    getGameDir(gameId).resolve(f"step-$stepNumber%04d")
  }

  /** Ensure game directory exists. */
  private def ensureGameDir(gameId: String): Path = {
    val dir = getGameDir(gameId)
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      logger.info(s"Created game directory: ${dir.toAbsolutePath}")
    }
    dir
  }

  /** Ensure step directory exists. */
  private def ensureStepDir(gameId: String, stepNumber: Int): Path = {
    ensureGameDir(gameId)
    val stepDir = getStepDir(gameId, stepNumber)
    if (!Files.exists(stepDir)) {
      Files.createDirectories(stepDir)
      logger.debug(s"Created step directory: ${stepDir.toAbsolutePath}")
    }
    stepDir
  }

  /** Save JSON with pretty printing. */
  private def saveJson(path: Path, data: Value): Unit = {
    val jsonString = ujson.write(data, indent = 2)
    Files.write(path, jsonString.getBytes(StandardCharsets.UTF_8))
  }

  /** Save text content. */
  private def saveText(path: Path, content: String): Unit = {
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
  }

  /** Load JSON from file. */
  private def loadJson(path: Path): Value = {
    val jsonString = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    ujson.read(jsonString)
  }

  /** Load text from file. */
  private def loadText(path: Path): String = {
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  /** Save game metadata (game.json). */
  def saveGameMetadata(metadata: GameMetadata): SzorkResult[Unit] = {
    try {
      val gameDir = ensureGameDir(metadata.gameId)
      val metadataPath = gameDir.resolve("game.json")

      logger.info(s"[PERSIST-SAVE] Saving metadata for ${metadata.gameId}: title='${metadata.adventureTitle}', theme=${metadata.theme}, artStyle=${metadata.artStyle}")
      val json = GameMetadataCodec.toJson(metadata)
      val jsonStr = json.toString
      logger.info(s"[PERSIST-SAVE] JSON to write: ${jsonStr.take(500)}")
      saveJson(metadataPath, json)

      logger.info(s"[PERSIST-SAVE] Successfully saved game metadata to ${metadataPath}")
      Right(())
    } catch {
      case e: Exception =>
        logger.error(s"[PERSIST-SAVE] Failed to save game metadata: ${metadata.gameId}", e)
        Left(PersistenceError(s"Failed to save game metadata: ${e.getMessage}", Some(e), operation = "save"))
    }
  }

  /** Load game metadata (game.json). */
  def loadGameMetadata(gameId: String): SzorkResult[GameMetadata] = {
    try {
      val metadataPath = getGameDir(gameId).resolve("game.json")

      if (!Files.exists(metadataPath)) {
        logger.warn(s"[PERSIST-LOAD] Game metadata not found: $gameId at $metadataPath")
        return Left(NotFoundError(s"Game not found: $gameId"))
      }

      val json = loadJson(metadataPath)
      val jsonStr = json.toString
      logger.info(s"[PERSIST-LOAD] Loaded JSON from $metadataPath: ${jsonStr.take(500)}")
      val metadata = GameMetadataCodec.fromJson(json)

      logger.info(s"[PERSIST-LOAD] Loaded game metadata: $gameId, title='${metadata.adventureTitle}', theme=${metadata.theme}, artStyle=${metadata.artStyle}")
      Right(metadata)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load game metadata: $gameId", e)
        Left(PersistenceError(s"Failed to load game metadata: ${e.getMessage}", Some(e), operation = "load"))
    }
  }

  /** Save a complete step. */
  def saveStep(stepData: StepData): SzorkResult[Unit] = {
    try {
      val stepDir = ensureStepDir(stepData.metadata.gameId, stepData.metadata.stepNumber)

      // Save step metadata
      saveJson(stepDir.resolve("metadata.json"), StepMetadataCodec.toJson(stepData.metadata))

      // Save game state
      saveJson(stepDir.resolve("state.json"), GameStateCodec.toJson(stepData.gameState))

      // Save user command (if present)
      stepData.userCommand.foreach(cmd => saveText(stepDir.resolve("command.txt"), cmd))

      // Save narration text
      saveText(stepDir.resolve("response.txt"), stepData.narrationText)

      // Save structured response (if present)
      stepData.response.foreach { resp =>
        val responseJson = resp match {
          case SceneResponse(scene) =>
            ujson.Obj(
              "type" -> "scene",
              "scene" -> ujson.read(GameScene.toJson(scene))
            )
          case ActionResponse(text, locId, action) =>
            ujson.Obj(
              "type" -> "action",
              "narrationText" -> text,
              "locationId" -> locId,
              "action" -> action
            )
        }
        saveJson(stepDir.resolve("response.json"), responseJson)
      }

      // Save tool calls (if any)
      if (stepData.toolCalls.nonEmpty) {
        val toolCallsJson = ujson.Arr(stepData.toolCalls.map(ToolCallCodec.toJson): _*)
        saveJson(stepDir.resolve("tool-calls.json"), toolCallsJson)
      }

      // Save agent messages
      val messagesJson = ujson.Arr(stepData.agentMessages.map(MessageCodec.toJson): _*)
      saveJson(stepDir.resolve("messages.json"), messagesJson)

      // Save adventure outline (step 1 only)
      stepData.outline.foreach { outline =>
        saveJson(stepDir.resolve("outline.json"), AdventureGenerator.outlineToJson(outline))
      }

      logger.info(s"Saved step ${stepData.metadata.stepNumber} for game ${stepData.metadata.gameId}")
      Right(())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to save step ${stepData.metadata.stepNumber} for game ${stepData.metadata.gameId}", e)
        Left(PersistenceError(s"Failed to save step: ${e.getMessage}", Some(e), operation = "save"))
    }
  }

  /** Load a specific step. */
  def loadStep(gameId: String, stepNumber: Int): SzorkResult[StepData] = {
    try {
      val stepDir = getStepDir(gameId, stepNumber)

      if (!Files.exists(stepDir)) {
        return Left(NotFoundError(s"Step $stepNumber not found for game $gameId"))
      }

      // Load step metadata
      val metadata = StepMetadataCodec.fromJson(loadJson(stepDir.resolve("metadata.json")))

      // Load game state
      val gameState = GameStateCodec.fromJson(loadJson(stepDir.resolve("state.json")))

      // Load user command (if present)
      val commandPath = stepDir.resolve("command.txt")
      val userCommand = if (Files.exists(commandPath)) Some(loadText(commandPath)) else None

      // Load narration text
      val narrationText = loadText(stepDir.resolve("response.txt"))

      // Load structured response (if present)
      val responsePath = stepDir.resolve("response.json")
      val response = if (Files.exists(responsePath)) {
        val json = loadJson(responsePath)
        json.obj.get("type").map(_.str) match {
          case Some("scene") =>
            // GameScene.fromJson returns a Result, handle it
            GameScene.fromJson(ujson.write(json("scene"))) match {
              case Right(scene) => Some(SceneResponse(scene))
              case Left(error) =>
                logger.warn(s"Failed to parse scene in step $stepNumber for game $gameId: ${error.message}")
                None
            }
          case Some("action") =>
            Some(ActionResponse(
              narrationText = json("narrationText").str,
              locationId = json("locationId").str,
              action = json("action").str
            ))
          case _ => None
        }
      } else None

      // Load tool calls (if present)
      val toolCallsPath = stepDir.resolve("tool-calls.json")
      val toolCalls = if (Files.exists(toolCallsPath)) {
        loadJson(toolCallsPath).arr.map(ToolCallCodec.fromJson).toList
      } else Nil

      // Load agent messages
      val agentMessages = loadJson(stepDir.resolve("messages.json")).arr.map(MessageCodec.fromJson).toList

      // Load adventure outline (if present)
      val outlinePath = stepDir.resolve("outline.json")
      val outline = if (Files.exists(outlinePath)) {
        Some(GamePersistence.parseAdventureOutlineFromJson(loadJson(outlinePath)))
      } else None

      logger.debug(s"Loaded step $stepNumber for game $gameId")
      Right(StepData(metadata, gameState, userCommand, narrationText, response, toolCalls, agentMessages, outline))
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load step $stepNumber for game $gameId", e)
        Left(PersistenceError(s"Failed to load step: ${e.getMessage}", Some(e), operation = "load"))
    }
  }

  /** Load the latest step for a game. */
  def loadLatestStep(gameId: String): SzorkResult[StepData] = {
    loadGameMetadata(gameId).flatMap { metadata =>
      loadStep(gameId, metadata.currentStep)
    }
  }

  /** List all step numbers for a game. */
  def listSteps(gameId: String): List[Int] = {
    try {
      val gameDir = getGameDir(gameId)
      if (!Files.exists(gameDir)) {
        return Nil
      }

      Using.resource(Files.newDirectoryStream(gameDir, "step-*")) { stream =>
        stream.iterator().asScala
          .map(_.getFileName.toString)
          .filter(_.startsWith("step-"))
          .flatMap { name =>
            Try(name.substring(5).toInt).toOption
          }
          .toList
          .sorted
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to list steps for game $gameId", e)
        Nil
    }
  }

  /** Check if a game exists. */
  def gameExists(gameId: String): Boolean = {
    Files.exists(getGameDir(gameId).resolve("game.json"))
  }

  /** Delete a game and all its steps. */
  def deleteGame(gameId: String): SzorkResult[Unit] = {
    try {
      val gameDir = getGameDir(gameId)
      if (!Files.exists(gameDir)) {
        return Left(NotFoundError(s"Game not found: $gameId"))
      }

      // Delete recursively
      deleteRecursively(gameDir)
      logger.info(s"Deleted game: $gameId")
      Right(())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to delete game $gameId", e)
        Left(PersistenceError(s"Failed to delete game: ${e.getMessage}", Some(e), operation = "delete"))
    }
  }

  /** Delete a directory recursively. */
  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      Using.resource(Files.newDirectoryStream(path)) { stream =>
        stream.forEach(deleteRecursively)
      }
    }
    Files.delete(path)
  }

  /** List all games. */
  def listGames(): List[GameMetadata] = {
    try {
      val saveDir = ensureSaveDir()
      Using.resource(Files.newDirectoryStream(saveDir)) { stream =>
        stream.iterator().asScala
          .filter(Files.isDirectory(_))
          .flatMap { gameDir =>
            val gameId = gameDir.getFileName.toString
            loadGameMetadata(gameId).toOption
          }
          .toList
          .sortBy(-_.lastPlayed) // Most recently played first
      }
    } catch {
      case e: Exception =>
        logger.error("Failed to list games", e)
        Nil
    }
  }
}
