package org.llm4s.szork

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import ujson._
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import org.slf4j.{Logger, LoggerFactory}
import scala.util.Try
import scala.jdk.CollectionConverters._
import scala.util.Using

case class ConversationEntry(
  role: String,
  content: String,
  timestamp: Long
)

// Cache entry for generated media (images/music) per location
case class MediaCacheEntry(
  locationId: String,
  imagePrompt: Option[String],
  imageCacheId: Option[String],
  musicPrompt: Option[String],
  musicCacheId: Option[String],
  generatedAt: Long
)

case class GameState(
  gameId: String,
  theme: Option[GameTheme],
  artStyle: Option[ArtStyle],
  adventureOutline: Option[AdventureOutline], // Full adventure design
  currentScene: Option[GameScene],
  visitedLocations: Set[String],
  conversationHistory: List[ConversationEntry], // Kept for backwards compatibility
  inventory: List[String],
  createdAt: Long,
  lastSaved: Long,
  lastPlayed: Long = System.currentTimeMillis(),
  totalPlayTime: Long = 0, // Total play time in milliseconds
  adventureTitle: Option[String] = None,
  // New fields for complete state persistence
  agentMessages: List[ujson.Value] = List.empty, // Full agent conversation as JSON
  mediaCache: Map[String, MediaCacheEntry] = Map.empty, // Media cache per location
  systemPrompt: Option[String] = None // Complete system prompt including adventure outline
)

case class GameMetadata(
  gameId: String,
  theme: String,
  artStyle: String,
  adventureTitle: String,
  createdAt: Long,
  lastSaved: Long,
  lastPlayed: Long,
  totalPlayTime: Long, // Total play time in milliseconds
  // New fields for step-based persistence
  currentStep: Int = 1,
  totalSteps: Int = 1
)

object GamePersistence {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val SAVE_DIR = "szork-saves"

  // Ensure save directory exists
  private def ensureSaveDir(): Path = {
    val dir = Paths.get(SAVE_DIR)
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      logger.info(s"Created save directory: $SAVE_DIR")
    }
    dir
  }

  def saveGame(state: GameState): SzorkResult[Unit] =
    try {
      val saveDir = ensureSaveDir()
      val filePath = saveDir.resolve(s"${state.gameId}.json")

      // Convert GameState to JSON
      val json = GameStateCodec.toJson(state)
      // Write to file with pretty formatting
      val jsonString = ujson.write(json, indent = 2)
      Files.write(filePath, jsonString.getBytes(StandardCharsets.UTF_8))

      logger.info(s"Saved game ${state.gameId} to ${filePath.toString}")
      Right(())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to save game ${state.gameId}", e)
        Left(PersistenceError(s"Failed to save game: ${e.getMessage}", Some(e), retryable = false))
    }

  def loadGame(gameId: String): SzorkResult[GameState] =
    try {
      val saveDir = ensureSaveDir()
      val filePath = saveDir.resolve(s"$gameId.json")

      if (!Files.exists(filePath)) {
        logger.warn(s"Game save not found: $gameId")
        return Left(NotFoundError(s"Game not found: $gameId"))
      }

      // Read JSON from file
      val jsonString = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8)
      val json = read(jsonString)

      // Parse GameState from JSON
      val state = GameStateCodec.fromJson(json)

      logger.info(s"Loaded game $gameId from ${filePath.toString}")
      Right(state)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load game $gameId", e)
        Left(PersistenceError(s"Failed to load game: ${e.getMessage}", Some(e), retryable = false))
    }

  // Helper function to parse AdventureOutline from JSON
  private[szork] def parseAdventureOutlineFromJson(json: ujson.Value): AdventureOutline =
    AdventureOutline(
      title = json("title").str,
      tagline = json.obj.get("tagline").flatMap {
        case Null => None
        case s => Some(s.str)
      },
      mainQuest = json("mainQuest").str,
      subQuests = json("subQuests").arr.map(_.str).toList,
      keyLocations = json("keyLocations").arr
        .map(loc =>
          LocationOutline(
            id = loc("id").str,
            name = loc("name").str,
            description = loc("description").str,
            significance = loc("significance").str
          ))
        .toList,
      importantItems = json("importantItems").arr
        .map(item =>
          ItemOutline(
            name = item("name").str,
            description = item("description").str,
            purpose = item("purpose").str
          ))
        .toList,
      keyCharacters = json("keyCharacters").arr
        .map(char =>
          CharacterOutline(
            name = char("name").str,
            role = char("role").str,
            description = char("description").str
          ))
        .toList,
      adventureArc = json("adventureArc").str,
      specialMechanics = json.obj.get("specialMechanics").flatMap {
        case Null => None
        case s => Some(s.str)
      }
    )

  def listGames(): List[GameMetadata] =
    try {
      val saveDir = ensureSaveDir()
      logger.info(s"Listing games from directory: ${saveDir.toAbsolutePath}")

      val files = Using.resource(Files.list(saveDir)) { stream =>
        stream
          .iterator()
          .asScala
          .filter(path => path.toString.endsWith(".json"))
          .toList
      }

      logger.info(s"Found ${files.size} save files")

      val games = files.flatMap { path =>
        Try {
          logger.debug(s"Reading save file: ${path.getFileName}")
          val jsonString = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
          val json = read(jsonString)
          val metadata = GameStateCodec.metadataFromJson(json)
          logger.debug(s"Successfully parsed game metadata: ${metadata.gameId} - ${metadata.adventureTitle}")
          metadata
        }.recover { case e: Exception =>
          logger.error(s"Failed to parse save file: ${path.getFileName}", e)
          logger.error(s"Error details: ${e.getMessage}")
          // Log the first 500 chars of the file for debugging
          try {
            val content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            logger.error(s"File content preview: ${content.take(500)}")
          } catch {
            case _: Exception => logger.error("Could not read file content for debugging")
          }
          throw e
        }.toOption
      }

      logger.info(s"Successfully loaded ${games.size} games")
      games.sortBy(-_.lastPlayed) // Sort by most recently played first
    } catch {
      case e: Exception =>
        logger.error("Failed to list games", e)
        logger.error(s"Error details: ${e.getMessage}")
        List.empty
    }

  def deleteGame(gameId: String): SzorkResult[Unit] =
    try {
      val saveDir = ensureSaveDir()
      val filePath = saveDir.resolve(s"$gameId.json")

      if (Files.exists(filePath)) {
        Files.delete(filePath)
        logger.info(s"Deleted game save: $gameId")
        Right(())
      } else {
        Left(NotFoundError(s"Game not found: $gameId"))
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to delete game $gameId", e)
        Left(PersistenceError(s"Failed to delete game: ${e.getMessage}", Some(e), retryable = false))
    }

  /** Check if a save is in the old single-file format.
    *
    * @param gameId Game identifier
    * @return true if old format exists
    */
  def isOldFormat(gameId: String): Boolean = {
    val saveDir = ensureSaveDir()
    val oldFile = saveDir.resolve(s"$gameId.json")
    Files.exists(oldFile) && !StepPersistence.gameExists(gameId)
  }

  /** Migrate an old single-file save to the new step-based format.
    *
    * This converts a GameState JSON file to:
    * - game.json with GameMetadata
    * - step-0001/ directory with complete StepData
    *
    * The old file is deleted after successful migration.
    *
    * @param gameId Game identifier
    * @return Success or error
    */
  def migrateOldSaveToStepFormat(gameId: String): SzorkResult[Unit] = {
    try {
      val saveDir = ensureSaveDir()
      val oldFile = saveDir.resolve(s"$gameId.json")

      if (!Files.exists(oldFile)) {
        return Left(NotFoundError(s"Old format save not found: $gameId"))
      }

      // Read old GameState
      logger.info(s"Migrating old save format for game: $gameId")
      val jsonString = new String(Files.readAllBytes(oldFile), StandardCharsets.UTF_8)
      val json = read(jsonString)
      val state = GameStateCodec.fromJson(json)

      // Extract metadata from old GameState
      val metadata = GameStateCodec.metadataFromJson(json)

      // Convert to StepData (treating entire save as step 1)
      val narrationText = state.currentScene.map(_.narrationText).getOrElse("")
      val stepData = StepData(
        metadata = StepMetadata(
          gameId = gameId,
          stepNumber = 1,
          timestamp = state.lastSaved,
          userCommand = None, // No command for initial state
          responseLength = narrationText.length,
          toolCallCount = 0,
          messageCount = state.agentMessages.length,
          success = true,
          executionTimeMs = 0
        ),
        gameState = state,
        userCommand = None,
        narrationText = narrationText,
        response = state.currentScene.map(SceneResponse),
        toolCalls = Nil,
        agentMessages = state.agentMessages.map { msgJson =>
          MessageCodec.fromJson(msgJson)
        },
        outline = state.adventureOutline
      )

      // Save in new format
      for {
        _ <- StepPersistence.saveGameMetadata(metadata)
        _ <- StepPersistence.saveStep(stepData)
      } yield {
        // Delete old file after successful migration
        Files.delete(oldFile)
        logger.info(s"Successfully migrated game $gameId to step-based format")
        ()
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to migrate game $gameId", e)
        Left(PersistenceError(s"Failed to migrate game: ${e.getMessage}", Some(e), operation = "migrate"))
    }
  }

  /** Automatically migrate old saves when loading.
    *
    * If a game exists in old format but not new format, migrate it automatically.
    * Then load from the new format.
    *
    * @param gameId Game identifier
    * @return GameState or error
    */
  def loadGameWithMigration(gameId: String): SzorkResult[GameState] = {
    if (isOldFormat(gameId)) {
      logger.info(s"Detected old format save for $gameId, migrating...")
      migrateOldSaveToStepFormat(gameId).flatMap { _ =>
        StepPersistence.loadLatestStep(gameId).map(_.gameState)
      }
    } else if (StepPersistence.gameExists(gameId)) {
      StepPersistence.loadLatestStep(gameId).map(_.gameState)
    } else {
      loadGame(gameId) // Fallback to old loader
    }
  }
}
