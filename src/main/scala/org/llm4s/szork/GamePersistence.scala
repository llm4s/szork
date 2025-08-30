package org.llm4s.szork

import ujson._
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory
import scala.util.Try
import scala.jdk.CollectionConverters._
import org.llm4s.llmconnect.model.{Message, UserMessage, AssistantMessage, SystemMessage, ToolMessage}
import org.llm4s.agent.AgentState

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
  adventureOutline: Option[AdventureOutline],  // Full adventure design
  currentScene: Option[GameScene],
  visitedLocations: Set[String],
  conversationHistory: List[ConversationEntry],  // Kept for backwards compatibility
  inventory: List[String],
  createdAt: Long,
  lastSaved: Long,
  lastPlayed: Long = System.currentTimeMillis(),
  totalPlayTime: Long = 0,  // Total play time in milliseconds
  adventureTitle: Option[String] = None,
  // New fields for complete state persistence
  agentMessages: List[ujson.Value] = List.empty,  // Full agent conversation as JSON
  mediaCache: Map[String, MediaCacheEntry] = Map.empty,  // Media cache per location
  systemPrompt: Option[String] = None  // Complete system prompt including adventure outline
)

case class GameMetadata(
  gameId: String,
  theme: String,
  artStyle: String,
  adventureTitle: String,
  createdAt: Long,
  lastSaved: Long,
  lastPlayed: Long,
  totalPlayTime: Long  // Total play time in milliseconds
)

object GamePersistence {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
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
  
  def saveGame(state: GameState): Either[String, Unit] = {
    try {
      val saveDir = ensureSaveDir()
      val filePath = saveDir.resolve(s"${state.gameId}.json")
      
      // Convert GameState to JSON
      val json = Obj(
        "gameId" -> state.gameId,
        "theme" -> state.theme.map(t => Obj(
          "id" -> t.id,
          "name" -> t.name,
          "prompt" -> t.prompt
        )).getOrElse(Null),
        "artStyle" -> state.artStyle.map(a => Obj(
          "id" -> a.id,
          "name" -> a.name
        )).getOrElse(Null),
        "currentScene" -> state.currentScene.map(scene => Obj(
          "locationId" -> scene.locationId,
          "locationName" -> scene.locationName,
          "narrationText" -> scene.narrationText,
          "imageDescription" -> scene.imageDescription,
          "musicDescription" -> scene.musicDescription,
          "musicMood" -> scene.musicMood,
          "exits" -> scene.exits.map(exit => {
            val exitObj = Obj(
              "direction" -> exit.direction,
              "locationId" -> exit.locationId
            )
            exit.description.foreach(desc => exitObj("description") = desc)
            exitObj
          }),
          "items" -> scene.items,
          "npcs" -> scene.npcs
        )).getOrElse(Null),
        "visitedLocations" -> state.visitedLocations.toList,
        "conversationHistory" -> state.conversationHistory.map(entry => Obj(
          "role" -> entry.role,
          "content" -> entry.content,
          "timestamp" -> ujson.Num(entry.timestamp)
        )),
        "inventory" -> state.inventory,
        "createdAt" -> ujson.Num(state.createdAt),
        "lastSaved" -> ujson.Num(state.lastSaved),
        "lastPlayed" -> ujson.Num(state.lastPlayed),
        "totalPlayTime" -> ujson.Num(state.totalPlayTime),
        "adventureTitle" -> state.adventureTitle.map(ujson.Str(_)).getOrElse(ujson.Null),
        // New fields for enhanced state
        "adventureOutline" -> state.adventureOutline.map(outline => 
          AdventureGenerator.outlineToJson(outline)
        ).getOrElse(Null),
        "agentMessages" -> state.agentMessages,
        "mediaCache" -> Obj.from(state.mediaCache.map { case (locationId, entry) =>
          locationId -> Obj(
            "locationId" -> entry.locationId,
            "imagePrompt" -> entry.imagePrompt.map(ujson.Str(_)).getOrElse(Null),
            "imageCacheId" -> entry.imageCacheId.map(ujson.Str(_)).getOrElse(Null),
            "musicPrompt" -> entry.musicPrompt.map(ujson.Str(_)).getOrElse(Null),
            "musicCacheId" -> entry.musicCacheId.map(ujson.Str(_)).getOrElse(Null),
            "generatedAt" -> ujson.Num(entry.generatedAt)
          )
        }),
        "systemPrompt" -> state.systemPrompt.map(ujson.Str(_)).getOrElse(Null)
      )
      
      // Write to file with pretty formatting
      val jsonString = ujson.write(json, indent = 2)
      Files.write(filePath, jsonString.getBytes(StandardCharsets.UTF_8))
      
      logger.info(s"Saved game ${state.gameId} to ${filePath.toString}")
      Right(())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to save game ${state.gameId}", e)
        Left(s"Failed to save game: ${e.getMessage}")
    }
  }
  
  def loadGame(gameId: String): Either[String, GameState] = {
    try {
      val saveDir = ensureSaveDir()
      val filePath = saveDir.resolve(s"$gameId.json")
      
      if (!Files.exists(filePath)) {
        logger.warn(s"Game save not found: $gameId")
        return Left(s"Game not found: $gameId")
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
      val state = GameState(
        gameId = json("gameId").str,
        theme = json("theme") match {
          case Null => None
          case t => Some(GameTheme(
            id = t("id").str,
            name = t("name").str,
            prompt = t("prompt").str
          ))
        },
        artStyle = json("artStyle") match {
          case Null => None
          case a => Some(ArtStyle(
            id = a("id").str,
            name = a("name").str
          ))
        },
        currentScene = json("currentScene") match {
          case Null => None
          case s => Some(GameScene(
            locationId = s("locationId").str,
            locationName = s("locationName").str,
            narrationText = s("narrationText").str,
            imageDescription = s("imageDescription").str,
            musicDescription = s("musicDescription").str,
            musicMood = s("musicMood").str,
            exits = s("exits").arr.map(e => Exit(
              direction = e("direction").str,
              locationId = e("locationId").str,
              description = e("description") match {
                case Null => None
                case d => Some(d.str)
              }
            )).toList,
            items = s("items").arr.map(_.str).toList,
            npcs = s("npcs").arr.map(_.str).toList
          ))
        },
        visitedLocations = json("visitedLocations").arr.map(_.str).toSet,
        conversationHistory = json("conversationHistory").arr.map(e => ConversationEntry(
          role = e("role").str,
          content = e("content").str,
          timestamp = readTimestamp(e("timestamp"))
        )).toList,
        inventory = json.obj.get("inventory").map(_.arr.map(_.str).toList).getOrElse(List.empty),
        createdAt = readTimestamp(json("createdAt")),
        lastSaved = readTimestamp(json("lastSaved")),
        lastPlayed = json.obj.get("lastPlayed").map(readTimestamp).getOrElse(readTimestamp(json("lastSaved"))),
        totalPlayTime = json.obj.get("totalPlayTime").map(readTimestamp).getOrElse(0L),
        adventureTitle = json.obj.get("adventureTitle").flatMap {
          case ujson.Null => None
          case s => Some(s.str)
        }.filter(_.nonEmpty),
        // Load new enhanced state fields
        adventureOutline = json.obj.get("adventureOutline").flatMap {
          case Null => None
          case outline => Some(parseAdventureOutlineFromJson(outline))
        },
        agentMessages = json.obj.get("agentMessages").map(_.arr.toList).getOrElse(List.empty),
        mediaCache = json.obj.get("mediaCache").map { cacheObj =>
          cacheObj.obj.map { case (locationId, entry) =>
            locationId -> MediaCacheEntry(
              locationId = entry("locationId").str,
              imagePrompt = entry("imagePrompt") match {
                case Null => None
                case s => Some(s.str)
              },
              imageCacheId = entry("imageCacheId") match {
                case Null => None
                case s => Some(s.str)
              },
              musicPrompt = entry("musicPrompt") match {
                case Null => None
                case s => Some(s.str)
              },
              musicCacheId = entry("musicCacheId") match {
                case Null => None
                case s => Some(s.str)
              },
              generatedAt = readTimestamp(entry("generatedAt"))
            )
          }.toMap
        }.getOrElse(Map.empty),
        systemPrompt = json.obj.get("systemPrompt").flatMap {
          case Null => None
          case s => Some(s.str)
        }
      )
      
      logger.info(s"Loaded game $gameId from ${filePath.toString}")
      Right(state)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load game $gameId", e)
        Left(s"Failed to load game: ${e.getMessage}")
    }
  }
  
  // Helper function to parse AdventureOutline from JSON
  private def parseAdventureOutlineFromJson(json: ujson.Value): AdventureOutline = {
    AdventureOutline(
      title = json("title").str,
      tagline = json.obj.get("tagline").flatMap {
        case Null => None
        case s => Some(s.str)
      },
      mainQuest = json("mainQuest").str,
      subQuests = json("subQuests").arr.map(_.str).toList,
      keyLocations = json("keyLocations").arr.map(loc => LocationOutline(
        id = loc("id").str,
        name = loc("name").str,
        description = loc("description").str,
        significance = loc("significance").str
      )).toList,
      importantItems = json("importantItems").arr.map(item => ItemOutline(
        name = item("name").str,
        description = item("description").str,
        purpose = item("purpose").str
      )).toList,
      keyCharacters = json("keyCharacters").arr.map(char => CharacterOutline(
        name = char("name").str,
        role = char("role").str,
        description = char("description").str
      )).toList,
      adventureArc = json("adventureArc").str,
      specialMechanics = json.obj.get("specialMechanics").flatMap {
        case Null => None
        case s => Some(s.str)
      }
    )
  }
  
  def listGames(): List[GameMetadata] = {
    try {
      val saveDir = ensureSaveDir()
      logger.info(s"Listing games from directory: ${saveDir.toAbsolutePath}")
      
      val files = Files.list(saveDir)
        .iterator()
        .asScala
        .filter(path => path.toString.endsWith(".json"))
        .toList
      
      logger.info(s"Found ${files.size} save files")
      
      val games = files.flatMap { path =>
        Try {
          logger.debug(s"Reading save file: ${path.getFileName}")
          val jsonString = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
          val json = read(jsonString)
          
          // Handle timestamps that might be stored as strings or numbers
          def readTimestamp(value: ujson.Value): Long = value match {
            case ujson.Str(s) => s.toLong
            case ujson.Num(n) => n.toLong
            case _ => throw new Exception(s"Invalid timestamp format: $value")
          }
          
          val metadata = GameMetadata(
            gameId = json("gameId").str,
            theme = json("theme") match {
              case Null => "Unknown"
              case t => t("name").str
            },
            artStyle = json("artStyle") match {
              case Null => "Unknown"
              case a => a("name").str
            },
            adventureTitle = json.obj.get("adventureTitle").flatMap {
              case ujson.Null => None
              case s => Some(s.str)
            }.filter(_.nonEmpty).getOrElse("Untitled Adventure"),
            createdAt = readTimestamp(json("createdAt")),
            lastSaved = readTimestamp(json("lastSaved")),
            lastPlayed = json.obj.get("lastPlayed").map(readTimestamp).getOrElse(readTimestamp(json("lastSaved"))),
            totalPlayTime = json.obj.get("totalPlayTime").map(readTimestamp).getOrElse(0L)
          )
          logger.debug(s"Successfully parsed game metadata: ${metadata.gameId} - ${metadata.adventureTitle}")
          metadata
        }.recover {
          case e: Exception =>
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
  }
  
  def deleteGame(gameId: String): Either[String, Unit] = {
    try {
      val saveDir = ensureSaveDir()
      val filePath = saveDir.resolve(s"$gameId.json")
      
      if (Files.exists(filePath)) {
        Files.delete(filePath)
        logger.info(s"Deleted game save: $gameId")
        Right(())
      } else {
        Left(s"Game not found: $gameId")
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to delete game $gameId", e)
        Left(s"Failed to delete game: ${e.getMessage}")
    }
  }
}