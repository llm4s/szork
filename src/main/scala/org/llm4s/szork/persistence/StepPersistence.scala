package org.llm4s.szork.persistence

import org.llm4s.szork.game.{GameScene, AdventureGenerator}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import ujson._
import org.llm4s.szork.game.IdGenerator
import org.llm4s.szork.media.AtomicFiles
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.nio.charset.StandardCharsets
import org.slf4j.{Logger, LoggerFactory}
import scala.util.{Try, Using}
import scala.jdk.CollectionConverters._

/** Step-based game persistence.
  *
  * Unified persistence layer for both regular gameplay and debug sessions. Each game is stored as a directory with
  * numbered step subdirectories.
  *
  * Directory structure:
  * ```
  * szork-saves/{game-id}/
  *   game.json              # GameMetadata
  *   step-0001/
  *     metadata.json        # StepMetadata
  *     state.json           # GameState, with the agent's whole conversation (agentMessages)
  *     command.txt          # User command (if any)
  *     response.txt         # Narration text
  *     response.json        # The scene described (if any)
  *     tool-calls.json      # Tool calls (if any)
  *     outline.json         # Adventure outline (step 1 only)
  *   step-0002/
  *     ...
  * ```
  */
object StepPersistence {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)
  // Where games are saved: the server's SzorkConfig.savesDirectory, set at startup. Tests use a temporary directory.
  @volatile private var saveRoot: Path = Paths.get("szork-saves")

  /** Saves games under `dir` from now on. */
  private[szork] def useSaveDirectory(dir: Path): Unit = saveRoot = dir

  /** Ensure the save directory exists. */
  private def ensureSaveDir(): Path = {
    val dir = saveRoot
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      logger.info(s"Created save directory: $dir")
    }
    dir
  }

  /** Get the game directory path. */
  private def getGameDir(gameId: String): Path = {
    ensureSaveDir()
    GameDirs.resolve(saveRoot, gameId)
  }

  /** Get the step directory path. */
  private def getStepDir(gameId: String, stepNumber: Int): Path =
    getGameDir(gameId).resolve(f"step-$stepNumber%04d")

  /** Ensure game directory exists. */
  private def ensureGameDir(gameId: String): Path = {
    val dir = getGameDir(gameId)
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      logger.debug(s"Created game directory: ${dir.toAbsolutePath}")
    }
    dir
  }

  /** Writes a step directory whole: `write` fills a new directory, which then takes the place of `stepDir` and of
    * anything an earlier save of that step left there. The new directory's name starts with a dot, so it is never
    * listed as a step.
    */
  private def replaceStepDir(stepDir: Path)(write: Path => Unit): Unit = {
    val gameDir = stepDir.getParent
    val name = stepDir.getFileName.toString
    val fresh = Files.createTempDirectory(gameDir, s".$name.new-")
    try {
      write(fresh)
      if (Files.exists(stepDir)) {
        val old = gameDir.resolve(s".$name.old-${java.util.UUID.randomUUID()}")
        Files.move(stepDir, old, StandardCopyOption.ATOMIC_MOVE)
        Files.move(fresh, stepDir, StandardCopyOption.ATOMIC_MOVE)
        deleteRecursively(old)
      } else Files.move(fresh, stepDir, StandardCopyOption.ATOMIC_MOVE)
    } finally if (Files.exists(fresh)) deleteRecursively(fresh)
  }

  /** Save JSON with pretty printing. Like every save, it is atomic: a crash leaves the old file or the new one. */
  private def saveJson(path: Path, data: Value): Unit =
    saveText(path, ujson.write(data, indent = 2))

  /** Save text content, atomically. */
  private def saveText(path: Path, content: String): Unit =
    AtomicFiles.write(path, content.getBytes(StandardCharsets.UTF_8))

  /** Load JSON from file. */
  private def loadJson(path: Path): Value = {
    val jsonString = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    ujson.read(jsonString)
  }

  /** Load text from file. */
  private def loadText(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  /** Save game metadata (game.json). */
  def saveGameMetadata(metadata: GameMetadata): SzorkResult[Unit] =
    try {
      val gameDir = ensureGameDir(metadata.gameId)
      val metadataPath = gameDir.resolve("game.json")

      val json = GameMetadataCodec.toJson(metadata)
      logger.debug(s"Saving game metadata to $metadataPath: ${json.toString.take(500)}")
      saveJson(metadataPath, json)
      Right(())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to save game metadata: ${metadata.gameId}", e)
        Left(PersistenceError(s"Failed to save game metadata: ${e.getMessage}", Some(e), operation = "save"))
    }

  /** Load game metadata (game.json). */
  def loadGameMetadata(gameId: String): SzorkResult[GameMetadata] =
    try {
      val metadataPath = getGameDir(gameId).resolve("game.json")

      if (!Files.exists(metadataPath)) {
        logger.debug(s"Game metadata not found: $gameId at $metadataPath")
        return Left(NotFoundError(s"Game not found: $gameId"))
      }

      val json = loadJson(metadataPath)
      logger.debug(s"Loaded game metadata from $metadataPath: ${json.toString.take(500)}")
      Right(GameMetadataCodec.fromJson(json))
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load game metadata: $gameId", e)
        Left(PersistenceError(s"Failed to load game metadata: ${e.getMessage}", Some(e), operation = "load"))
    }

  /** Save a complete step. Callers write the game's game.json after it, so game.json never names a missing step. The
    * step's directory is written whole, so a step saved again (a turn played again after a load) keeps nothing of the
    * earlier save.
    */
  def saveStep(stepData: StepData): SzorkResult[Unit] =
    try {
      ensureGameDir(stepData.metadata.gameId)
      replaceStepDir(getStepDir(stepData.metadata.gameId, stepData.metadata.stepNumber)) { stepDir =>
        writeStep(stepDir, stepData)
      }
      logger.debug(s"Saved step ${stepData.metadata.stepNumber} for game ${stepData.metadata.gameId}")
      Right(())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to save step ${stepData.metadata.stepNumber} for game ${stepData.metadata.gameId}", e)
        Left(PersistenceError(s"Failed to save step: ${e.getMessage}", Some(e), operation = "save"))
    }

  /** Writes the files of `stepData` into the empty directory `stepDir`. */
  private def writeStep(stepDir: Path, stepData: StepData): Unit = {
    // Save step metadata
    saveJson(stepDir.resolve("metadata.json"), StepMetadataCodec.toJson(stepData.metadata))

    // Save game state
    saveJson(stepDir.resolve("state.json"), GameStateCodec.toJson(stepData.gameState))

    // Save user command (if present)
    stepData.userCommand.foreach(cmd => saveText(stepDir.resolve("command.txt"), cmd))

    // Save narration text
    saveText(stepDir.resolve("response.txt"), stepData.narrationText)

    // Save structured response (if present)
    stepData.response.foreach { response =>
      saveJson(
        stepDir.resolve("response.json"),
        ujson.Obj("type" -> "scene", "scene" -> ujson.read(GameScene.toJson(response.scene))))
    }

    // Save tool calls (if any)
    if (stepData.toolCalls.nonEmpty) {
      val toolCallsJson = ujson.Arr(stepData.toolCalls.map(ToolCallCodec.toJson)*)
      saveJson(stepDir.resolve("tool-calls.json"), toolCallsJson)
    }

    // Save adventure outline (step 1 only)
    stepData.outline.foreach { outline =>
      saveJson(stepDir.resolve("outline.json"), AdventureGenerator.outlineToJson(outline))
    }
  }

  /** Saves a new game's first step, then its game.json: a game is listed only once its first step is complete. */
  def saveNewGame(stepData: StepData, metadata: GameMetadata): SzorkResult[Unit] =
    saveStep(stepData).flatMap(_ => saveGameMetadata(metadata))

  /** Saves a later step, then records it in the game's game.json, adding `playTimeMs` to the game's play time. A game
    * whose game.json is gone (it was deleted) is not saved: nothing of it is written again.
    */
  def saveNextStep(stepData: StepData, playTimeMs: Long): SzorkResult[Unit] =
    for {
      metadata <- loadGameMetadata(stepData.metadata.gameId)
      _ <- saveStep(stepData)
      _ <- saveGameMetadata(GameMetadataHelper.afterStep(metadata, stepData.metadata.stepNumber, playTimeMs))
    } yield ()

  /** Load a specific step. */
  def loadStep(gameId: String, stepNumber: Int): SzorkResult[StepData] =
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
          case _ => None
        }
      } else None

      // Load tool calls (if present)
      val toolCallsPath = stepDir.resolve("tool-calls.json")
      val toolCalls = if (Files.exists(toolCallsPath)) {
        loadJson(toolCallsPath).arr.map(ToolCallCodec.fromJson).toList
      } else Nil

      // Load adventure outline (if present)
      val outlinePath = stepDir.resolve("outline.json")
      val outline = if (Files.exists(outlinePath)) {
        Some(AdventureGenerator.outlineFromJson(loadJson(outlinePath)))
      } else None

      logger.debug(s"Loaded step $stepNumber for game $gameId")
      Right(StepData(metadata, gameState, userCommand, narrationText, response, toolCalls, outline))
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load step $stepNumber for game $gameId", e)
        Left(PersistenceError(s"Failed to load step: ${e.getMessage}", Some(e), operation = "load"))
    }

  /** Load the latest step for a game: the step its game.json names, or, if that step cannot be read, the highest step
    * that can (the game then carries on from there).
    */
  def loadLatestStep(gameId: String): SzorkResult[StepData] =
    loadGameMetadata(gameId).flatMap { metadata =>
      loadStep(gameId, metadata.currentStep).left.flatMap { error =>
        val earlier = listSteps(gameId).filter(_ < metadata.currentStep).reverse
        earlier.iterator.map(step => step -> loadStep(gameId, step)).collectFirst { case (step, Right(data)) =>
          logger.warn(s"Step ${metadata.currentStep} of game $gameId cannot be read; loading step $step instead")
          data
        } match {
          case Some(data) => Right(data)
          case None => Left(error)
        }
      }
    }

  /** List all step numbers for a game. */
  def listSteps(gameId: String): List[Int] =
    try {
      val gameDir = getGameDir(gameId)
      if (!Files.exists(gameDir)) {
        return Nil
      }

      Using.resource(Files.newDirectoryStream(gameDir, "step-*")) { stream =>
        stream
          .iterator()
          .asScala
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

  /** Check if a game exists. */
  def gameExists(gameId: String): Boolean =
    IdGenerator.isValidGameId(gameId) && Files.exists(getGameDir(gameId).resolve("game.json"))

  /** Delete a game and all its steps. */
  def deleteGame(gameId: String): SzorkResult[Unit] =
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

  /** Delete a directory recursively. */
  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      Using.resource(Files.newDirectoryStream(path)) { stream =>
        stream.forEach(deleteRecursively)
      }
    }
    Files.delete(path)
  }

  /** List all games. Anything else in the save directory (a folder that is not a game, or a game whose first step is
    * still being saved) is skipped.
    */
  def listGames(): List[GameMetadata] =
    try {
      val saveDir = ensureSaveDir()
      Using.resource(Files.newDirectoryStream(saveDir)) { stream =>
        stream
          .iterator()
          .asScala
          .map(_.getFileName.toString)
          .filter(gameExists)
          .flatMap(gameId => loadGameMetadata(gameId).toOption)
          .toList
          .sortBy(-_.lastPlayed) // Most recently played first
      }
    } catch {
      case e: Exception =>
        logger.error("Failed to list games", e)
        Nil
    }
}
