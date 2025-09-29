package org.llm4s.szork

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import ujson._
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory
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
  totalPlayTime: Long // Total play time in milliseconds
)

object GamePersistence {
  private implicit val logger = LoggerFactory.getLogger(getClass.getSimpleName)
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

      // Helper function to handle timestamps that might be stored as strings or numbers
      def readTimestamp(value: ujson.Value): Long = value match {
        case ujson.Str(s) => s.toLong
        case ujson.Num(n) => n.toLong
        case _ => throw new Exception(s"Invalid timestamp format: $value")
      }

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
}
