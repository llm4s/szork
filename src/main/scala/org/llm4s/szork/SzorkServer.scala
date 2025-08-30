package org.llm4s.szork

import cask._
import scala.collection.mutable
import org.slf4j.LoggerFactory
import org.llm4s.config.EnvLoader
import java.util.Base64
import scala.concurrent.{Future, ExecutionContext}
import java.util.concurrent.Executors

case class GameSession(
  id: String,
  gameId: String,  // Unique game ID for persistence
  engine: GameEngine,
  theme: Option[GameTheme] = None,
  artStyle: Option[ArtStyle] = None,
  pendingImages: mutable.Map[Int, Option[String]] = mutable.Map.empty,
  pendingMusic: mutable.Map[Int, Option[(String, String)]] = mutable.Map.empty,  // (base64, mood)
  autoSaveEnabled: Boolean = true,
  imageGenerationEnabled: Boolean = true
)

case class GameTheme(
  id: String,
  name: String,
  prompt: String
)

case class ArtStyle(
  id: String,
  name: String
)

case class CommandRequest(command: String)
case class CommandResponse(response: String, sessionId: String)

object SzorkServer extends cask.Main with cask.Routes {
  
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  
  // Load and validate configuration
  private val config = SzorkConfig.instance
  
  // Log the configuration 
  config.logConfiguration(logger)
  logger.info("Hot reload is enabled - file changes will trigger restart")
  
  // Validate configuration
  config.validate() match {
    case Left(errors) =>
      logger.error("Configuration validation failed:")
      errors.split("\n").foreach(error => logger.error(s"  - $error"))
      logger.warn("Server will continue but some features may not work")
    case Right(_) =>
      logger.info("Configuration validation successful")
  }
  
  // Verify LLM client can be created
  config.llmConfig match {
    case Some(_) =>
      try {
        import org.llm4s.llmconnect.LLMConnect
        LLMConnect.getClient(EnvLoader)
        logger.info(s"LLM client initialized successfully")
      } catch {
        case e: Exception =>
          logger.error(s"Failed to initialize LLM client: ${e.getMessage}")
          logger.error("Server will continue but LLM features will not work")
      }
    case None =>
      logger.error("No LLM configuration found - text generation will not work")
  }
  
  @post("/api/game/validate-theme")
  def validateTheme(request: Request) = {
    val json = ujson.read(request.text())
    val themeDescription = json("theme").str
    
    logger.info(s"Validating custom theme: ${themeDescription.take(100)}...")
    
    // Use LLM to validate and enhance the theme
    import org.llm4s.llmconnect.LLM
    import org.llm4s.llmconnect.model.{SystemMessage, UserMessage, Conversation}
    
    val client = LLM.client(EnvLoader)
    val validationPrompt = s"""Analyze this adventure game theme idea and determine if it would work well for a text-based adventure game:
    |
    |Theme: $themeDescription
    |
    |Please respond with a JSON object:
    |{
    |  "valid": true/false,
    |  "message": "Brief explanation if not valid",
    |  "enhancedTheme": "Enhanced version of the theme if valid, focusing on adventure elements"
    |}
    |
    |A good theme should have:
    |- Clear setting and atmosphere
    |- Potential for exploration and discovery
    |- Opportunities for puzzles, challenges, or mysteries
    |- Interesting locations to visit
    |""".stripMargin
    
    try {
      val conversation = Conversation(
        Seq(
          SystemMessage("You are an expert game designer specializing in text adventure games. Evaluate theme ideas and enhance them."),
          UserMessage(validationPrompt)
        )
      )
      
      client.complete(conversation) match {
        case Right(response) =>
          val responseText = response.message.content
          // Try to parse JSON from response
          val jsonStart = responseText.indexOf('{')
          val jsonEnd = responseText.lastIndexOf('}')
          if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val jsonStr = responseText.substring(jsonStart, jsonEnd + 1)
            try {
              val parsed = ujson.read(jsonStr)
              ujson.Obj(
                "valid" -> parsed("valid"),
                "message" -> parsed.obj.getOrElse("message", ujson.Str("")).str,
                "enhancedTheme" -> parsed.obj.getOrElse("enhancedTheme", ujson.Str(themeDescription)).str
              )
            } catch {
              case _: Exception =>
                // If parsing fails, assume it's valid
                ujson.Obj(
                  "valid" -> true,
                  "message" -> "",
                  "enhancedTheme" -> themeDescription
                )
            }
          } else {
            // Default to valid if no JSON found
            ujson.Obj(
              "valid" -> true,
              "message" -> "",
              "enhancedTheme" -> themeDescription
            )
          }
        case Left(error) =>
          logger.error(s"Failed to validate theme: $error")
          ujson.Obj(
            "valid" -> true,
            "message" -> "",
            "enhancedTheme" -> themeDescription
          )
      }
    } catch {
      case e: Exception =>
        logger.error(s"Error validating theme", e)
        ujson.Obj(
          "valid" -> true,
          "message" -> "",
          "enhancedTheme" -> themeDescription
        )
    }
  }
  
  private val sessionManager = new SessionManager()
  private val imageExecutor = Executors.newFixedThreadPool(4)
  private implicit val imageEC: ExecutionContext = ExecutionContext.fromExecutor(imageExecutor)
  
  // Start WebSocket server for all game communication
  private val wsPort = 9002 // WebSocket port (HTTP is on 9001)
  private val wsServer = new TypedWebSocketServer(wsPort, sessionManager)(imageEC)
  wsServer.start()
  logger.info(s"Type-safe WebSocket server started on port $wsPort for all game communication")


  @get("/api/health")
  def health() = {
    logger.debug("Health check requested")
    ujson.Obj(
      "status" -> "healthy",
      "service" -> "szork-server"
    )
  }
  
  @get("/api/games")
  def listSavedGames() = {
    logger.info("Listing saved games")
    val games = GamePersistence.listGames()
    val gamesJson = games.map { metadata =>
      ujson.Obj(
        "gameId" -> metadata.gameId,
        "title" -> metadata.adventureTitle,
        "theme" -> metadata.theme,
        "artStyle" -> metadata.artStyle,
        "createdAt" -> metadata.createdAt,
        "lastPlayed" -> metadata.lastPlayed,
        "totalPlayTime" -> metadata.totalPlayTime
      )
    }
    ujson.Obj(
      "status" -> "success",
      "games" -> gamesJson
    )
  }

  @post("/api/game/generate-adventure")
  def generateAdventure(request: Request) = {
    logger.info("Generating adventure outline")
    val json = ujson.read(request.text())
    
    val theme = json.obj.get("theme").map { t =>
      GameTheme(
        id = t("id").str,
        name = t("name").str,
        prompt = t("prompt").str
      )
    }
    
    val artStyle = json.obj.get("artStyle").map { s =>
      ArtStyle(
        id = s("id").str,
        name = s("name").str
      )
    }
    
    val themePrompt = theme.map(_.prompt).getOrElse("classic fantasy adventure")
    val artStyleId = artStyle.map(_.id).getOrElse("fantasy")
    
    logger.info(s"Generating adventure for theme: ${theme.map(_.name).getOrElse("default")}")
    
    AdventureGenerator.generateAdventureOutline(themePrompt, artStyleId) match {
      case Right(outline) =>
        logger.info(s"Adventure outline generated: ${outline.title}")
        ujson.Obj(
          "status" -> "success",
          "outline" -> AdventureGenerator.outlineToJson(outline)
        )
      case Left(error) =>
        logger.error(s"Failed to generate adventure outline: $error")
        ujson.Obj(
          "status" -> "error",
          "message" -> error
        )
    }
  }
  
  // Game start endpoint has been removed - use WebSocket newGame message instead

  // SSE and HTTP polling endpoints have been removed - all game communication now goes through WebSocket
  // The WebSocket server handles all real-time game communication including:
  // - Starting new games
  // - Loading saved games
  // - Processing commands (regular and streaming)
  // - Audio commands with transcription
  // - Image and music generation notifications
  // See TypedWebSocketServer.scala for the WebSocket implementation
  
  @get("/api/game/session/:sessionId")
  def getSession(sessionId: String) = {
    logger.debug(s"Getting session info for: $sessionId")
    sessionManager.getSession(sessionId) match {
      case Some(session) =>
        ujson.Obj(
          "sessionId" -> sessionId,
          "messageCount" -> session.engine.getMessageCount,
          "status" -> "active"
        )
      case None =>
        logger.warn(s"Session not found: $sessionId")
        ujson.Obj(
          "error" -> "Session not found",
          "status" -> "error"
        )
    }
  }

  @get("/") 
  def serveApp() = {
    """<!DOCTYPE html>
      |<html>
      |<head><title>Szork</title></head>
      |<body>
      |<h1>Szork Server</h1>
      |<p>Use the frontend at http://localhost:3090 or the API endpoints directly.</p>
      |</body>
      |</html>""".stripMargin
  }

  // Initialize music generation if configured
  // private val musicGen = MusicGeneration() // Commented out - not currently used

  override def port: Int = config.port
  override def host: String = config.host

  logger.info(s"Starting Szork Server on http://$host:$port")

  
  def allRoutes = Seq(this)
  
  @post("/api/game/save/:sessionId")
  def saveGame(sessionId: String) = {
    logger.info(s"Saving game for session: $sessionId")
    
    sessionManager.getSession(sessionId) match {
      case Some(session) =>
        val gameState = session.engine.getGameState(session.gameId, session.theme, session.artStyle)
        GamePersistence.saveGame(gameState) match {
          case Right(_) =>
            logger.info(s"Game saved successfully: ${session.gameId}")
            ujson.Obj(
              "status" -> "success",
              "gameId" -> session.gameId
            )
          case Left(error) =>
            logger.error(s"Failed to save game: $error")
            ujson.Obj(
              "status" -> "error",
              "error" -> error
            )
        }
      case None =>
        logger.warn(s"Session not found for save: $sessionId")
        ujson.Obj(
          "status" -> "error",
          "error" -> "Session not found"
        )
    }
  }
  
  @get("/api/game/load/:gameId")
  def loadGame(gameId: String) = {
    logger.info(s"Loading game: $gameId")
    
    GamePersistence.loadGame(gameId) match {
      case Right(gameState) =>
        // Create new session for loaded game
        val sessionId = IdGenerator.sessionId()
        val engine = GameEngine.create(sessionId, gameState.theme.map(_.prompt), gameState.artStyle.map(_.id), None)
        
        // Restore the game state
        engine.restoreGameState(gameState)
        
        val session = GameSession(
          id = sessionId,
          gameId = gameId,
          engine = engine,
          theme = gameState.theme,
          artStyle = gameState.artStyle
        )
        
        sessionManager.createSession(session)
        logger.info(s"Game loaded successfully: $gameId -> session $sessionId")
        
        // Check for cached image for current scene
        val cachedImage = gameState.currentScene.flatMap { scene =>
          gameState.artStyle.flatMap { artStyle =>
            MediaCache.getCachedImage(
              gameId, 
              scene.locationId, 
              scene.imageDescription, 
              artStyle.id
            )
          }
        }
        
        ujson.Obj(
          "status" -> "success",
          "sessionId" -> sessionId,
          "gameId" -> gameId,
          "adventureTitle" -> gameState.adventureTitle.map(ujson.Str(_)).getOrElse(ujson.Null),
          "scene" -> gameState.currentScene.map { scene =>
            ujson.Obj(
              "locationId" -> scene.locationId,
              "locationName" -> scene.locationName,
              "narrationText" -> scene.narrationText,
              "exits" -> scene.exits.map(exit => ujson.Obj(
                "direction" -> exit.direction,
                "description" -> ujson.Str(exit.description.getOrElse(""))
              )),
              "items" -> scene.items,
              "npcs" -> scene.npcs,
              "imageDescription" -> scene.imageDescription,
              "cachedImage" -> (cachedImage match {
                case Some(img) => ujson.Str(img)
                case None => ujson.Null
              })
            )
          }.getOrElse(ujson.Null),
          "conversationHistory" -> gameState.conversationHistory.map { entry =>
            ujson.Obj(
              "role" -> entry.role,
              "content" -> entry.content,
              "timestamp" -> entry.timestamp
            )
          }
        )
      case Left(error) =>
        logger.error(s"Failed to load game: $error")
        ujson.Obj(
          "status" -> "error",
          "error" -> error
        )
    }
  }
  
  @get("/api/game/list")
  def listGames() = {
    logger.info("Listing saved games")
    
    val games = GamePersistence.listGames()
    ujson.Obj(
      "status" -> "success",
      "games" -> games.map { game =>
        ujson.Obj(
          "gameId" -> game.gameId,
          "theme" -> game.theme,
          "artStyle" -> game.artStyle,
          "createdAt" -> game.createdAt,
          "lastSaved" -> game.lastSaved
        )
      }
    )
  }
  
  @get("/api/game/cache/:gameId")
  def getCacheStats(gameId: String) = {
    logger.info(s"Getting cache stats for game: $gameId")
    
    val stats = MediaCache.getCacheStats(gameId)
    ujson.Obj(
      "status" -> "success",
      "cache" -> ujson.Obj(
        "gameId" -> stats.getOrElse("gameId", "unknown").toString,
        "exists" -> (stats.getOrElse("exists", false) match {
          case b: Boolean => b
          case _ => false
        }),
        "imageCount" -> (stats.getOrElse("imageCount", 0) match {
          case l: Long => l.toInt
          case i: Int => i
          case _ => 0
        }),
        "musicCount" -> (stats.getOrElse("musicCount", 0) match {
          case l: Long => l.toInt
          case i: Int => i
          case _ => 0
        }),
        "totalSizeBytes" -> (stats.getOrElse("totalSizeBytes", 0L) match {
          case l: Long => l
          case i: Int => i.toLong
          case _ => 0L
        })
      )
    )
  }
  
  @delete("/api/game/:gameId")
  def deleteGame(gameId: String) = {
    logger.info(s"Deleting game: $gameId")
    
    // Delete the game save file
    val deleteResult = GamePersistence.deleteGame(gameId)
    
    // Also clear the media cache for this game
    val cacheResult = MediaCache.clearGameCache(gameId)
    
    deleteResult match {
      case Right(_) =>
        logger.info(s"Successfully deleted game: $gameId")
        // Log cache clearing result but don't fail if cache clearing fails
        cacheResult match {
          case Left(error) => logger.warn(s"Failed to clear cache for deleted game $gameId: $error")
          case _ => logger.info(s"Also cleared cache for deleted game: $gameId")
        }
        
        ujson.Obj(
          "status" -> "success",
          "message" -> s"Game deleted: $gameId"
        )
      case Left(error) =>
        logger.error(s"Failed to delete game $gameId: $error")
        ujson.Obj(
          "status" -> "error",
          "error" -> error
        )
    }
  }
  
  @delete("/api/game/cache/:gameId")  
  def clearGameCache(gameId: String) = {
    logger.info(s"Clearing cache for game: $gameId")
    
    MediaCache.clearGameCache(gameId) match {
      case Right(_) =>
        ujson.Obj(
          "status" -> "success",
          "message" -> s"Cache cleared for game: $gameId"
        )
      case Left(error) =>
        ujson.Obj(
          "status" -> "error",
          "error" -> error
        )
    }
  }
  
  // Initialize routes
  initialize()
}