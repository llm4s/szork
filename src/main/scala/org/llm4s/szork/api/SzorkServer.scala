package org.llm4s.szork.api

import cask._
import scala.collection.concurrent.TrieMap
import org.slf4j.LoggerFactory
import org.llm4s.config.EnvLoader
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import org.llm4s.szork.game._
import org.llm4s.szork.websocket.TypedWebSocketServer
import org.llm4s.szork.persistence.{GamePersistence, StepPersistence, GameMetadataHelper}
import org.llm4s.szork.media.MediaCache

/** Active game session with engine and configuration.
  *
  * @param id Session identifier
  * @param gameId Unique game ID for persistence
  * @param engine GameEngine instance managing the game
  * @param theme Game theme configuration
  * @param artStyle Visual art style for images
  * @param pendingImages Images being generated (step number -> base64)
  * @param pendingMusic Music being generated (step number -> (base64, mood))
  * @param autoSaveEnabled Whether to auto-save after each command
  * @param imageGenerationEnabled Whether image generation is enabled
  * @param ttsEnabled Whether text-to-speech is enabled
  * @param sttEnabled Whether speech-to-text is enabled
  * @param musicEnabled Whether music generation is enabled
  */
case class GameSession(
  id: String,
  gameId: String,
  engine: GameEngine,
  theme: Option[GameTheme] = None,
  artStyle: Option[ArtStyle] = None,
  pendingImages: TrieMap[Int, Option[String]] = TrieMap.empty,
  pendingMusic: TrieMap[Int, Option[(String, String)]] = TrieMap.empty,
  autoSaveEnabled: Boolean = true,
  imageGenerationEnabled: Boolean = true,
  ttsEnabled: Boolean = true,
  sttEnabled: Boolean = true,
  musicEnabled: Boolean = true
)

/** Game theme configuration with prompt text.
  *
  * @param id Theme identifier (e.g., "fantasy", "sci-fi")
  * @param name Display name
  * @param prompt Description text for AI generation
  */
case class GameTheme(
  id: String,
  name: String,
  prompt: String
)

/** Visual art style for generated images.
  *
  * @param id Style identifier (e.g., "pixel", "painting")
  * @param name Display name
  */
case class ArtStyle(
  id: String,
  name: String
)

/** Player command request. */
case class CommandRequest(command: String)

/** Response to a command with session ID. */
case class CommandResponse(response: String, sessionId: String)

/** Main HTTP server providing REST API and WebSocket endpoints.
  *
  * Serves the Szork web interface and manages game sessions. Integrates with
  * TypedWebSocketServer for real-time gameplay communication.
  */
object SzorkServer extends cask.Main with cask.Routes {

  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  // Load and validate configuration
  private val config = SzorkConfig.instance

  // Log the configuration
  config.logConfiguration(logger)
  logger.info("Hot reload is enabled - file changes will trigger restart")

  // Feature availability summary based on environment/config
  private val openAIKeyPresent = EnvLoader.get("OPENAI_API_KEY").exists(_.nonEmpty)
  private val replicateKeyPresent = EnvLoader.get("REPLICATE_API_KEY").exists(_.nonEmpty)

  private val ttsRequested = config.ttsEnabled
  private val sttRequested = config.sttEnabled
  private val musicRequested = config.musicEnabled

  private val imageRequested = config.imageGenerationEnabled && config.imageProvider != ImageProvider.None
  private val imageKeysPresent: Boolean = config.imageProvider match {
    case ImageProvider.HuggingFace | ImageProvider.HuggingFaceSDXL =>
      EnvLoader
        .get("HUGGINGFACE_API_KEY")
        .orElse(EnvLoader.get("HF_API_KEY"))
        .orElse(EnvLoader.get("HUGGINGFACE_TOKEN"))
        .exists(_.nonEmpty)
    case ImageProvider.OpenAIDalle2 | ImageProvider.OpenAIDalle3 => openAIKeyPresent
    case ImageProvider.LocalStableDiffusion => true
    case ImageProvider.None => false
  }

  logger.info("=== Feature Availability ===")
  logger.info(s"LLM Text Generation: ${config.llmConfig.map(_.provider).getOrElse("Unavailable")}")
  logger.info(
    s"Images: ${if (imageRequested && imageKeysPresent) s"Enabled (${ImageProvider.toString(config.imageProvider)})"
      else if (imageRequested) s"Disabled (missing credentials for ${ImageProvider.toString(config.imageProvider)})"
      else "Disabled"}")
  logger.info(s"Music: ${if (musicRequested && replicateKeyPresent) "Enabled (Replicate)"
    else if (musicRequested) "Disabled (REPLICATE_API_KEY not set)"
    else "Disabled (by config)"}")
  logger.info(s"Speech-to-Text: ${if (sttRequested && openAIKeyPresent) "Enabled (OpenAI Whisper)"
    else if (sttRequested) "Disabled (OPENAI_API_KEY not set)"
    else "Disabled (by config)"}")
  logger.info(s"Text-to-Speech: ${if (ttsRequested && openAIKeyPresent) "Enabled (OpenAI TTS)"
    else if (ttsRequested) "Disabled (OPENAI_API_KEY not set)"
    else "Disabled (by config)"}")
  logger.info("===========================")

  @get("/api/feature-flags")
  def featureFlags(): ujson.Value = {
    val imageRequested = config.imageGenerationEnabled && config.imageProvider != ImageProvider.None
    val imageCreds = config.imageProvider match {
      case ImageProvider.HuggingFace | ImageProvider.HuggingFaceSDXL =>
        EnvLoader
          .get("HUGGINGFACE_API_KEY")
          .orElse(EnvLoader.get("HF_API_KEY"))
          .orElse(EnvLoader.get("HUGGINGFACE_TOKEN"))
          .exists(_.nonEmpty)
      case ImageProvider.OpenAIDalle2 | ImageProvider.OpenAIDalle3 => openAIKeyPresent
      case ImageProvider.LocalStableDiffusion => true
      case ImageProvider.None => false
    }
    ujson.Obj(
      "llm" -> ujson.Obj(
        "provider" -> config.llmConfig.map(_.provider).getOrElse(""),
        "available" -> config.llmConfig.isDefined
      ),
      "image" -> ujson.Obj(
        "enabled" -> config.imageGenerationEnabled,
        "provider" -> ImageProvider.toString(config.imageProvider),
        "available" -> (imageRequested && imageCreds)
      ),
      "music" -> ujson.Obj(
        "enabled" -> config.musicEnabled,
        "available" -> (config.musicEnabled && replicateKeyPresent)
      ),
      "tts" -> ujson.Obj(
        "enabled" -> config.ttsEnabled,
        "available" -> (config.ttsEnabled && openAIKeyPresent)
      ),
      "stt" -> ujson.Obj(
        "enabled" -> config.sttEnabled,
        "available" -> (config.sttEnabled && openAIKeyPresent)
      )
    )
  }

  // Validate configuration
  config.validate() match {
    case Left(error) =>
      logger.error("Configuration validation failed:")
      error.message.split("\n").foreach(line => logger.error(s"  - $line"))
      logger.warn("Server will continue but some features may not work")
    case Right(_) =>
      logger.info("Configuration validation successful")
  }

  // Verify LLM client can be created; fail fast if unavailable
  config.llmConfig match {
    case Some(_) =>
      try {
        import org.llm4s.llmconnect.LLMConnect
        LLMConnect.getClient(EnvLoader)
        logger.info("LLM client initialized successfully")
      } catch {
        case e: Exception =>
          logger.error(s"Failed to initialize LLM client: ${e.getMessage}")
          throw new IllegalStateException("LLM initialization failed; refusing to start without a working LLM", e)
      }
    case None =>
      logger.error("No LLM configuration found - text generation will not work")
      throw new IllegalStateException("No LLM configured; set OPENAI_API_KEY, ANTHROPIC_API_KEY, or LLAMA_BASE_URL")
  }

  @post("/api/game/validate-theme")
  def validateTheme(request: Request): ujson.Value = {
    val json = ujson.read(request.text())
    val themeDescription = json("theme").str

    logger.info(s"Validating custom theme: ${themeDescription.take(100)}...")

    // Use LLM to validate and enhance the theme
    import org.llm4s.llmconnect.LLMConnect
    import org.llm4s.llmconnect.model.{SystemMessage, UserMessage, Conversation}

    val clientResult = LLMConnect.getClient(EnvLoader)
    val client = clientResult match {
      case Right(c) => c
      case Left(error) =>
        logger.error(s"Failed to get LLM client: ${error.message}")
        return ujson.Obj(
          "valid" -> false,
          "error" -> "LLM service unavailable"
        )
    }
    val validationPrompt =
      s"""Analyze this adventure game theme idea and determine if it would work well for a text-based adventure game:
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
      val conversation = Conversation(  // Conversation is used in client.complete below
        Seq(
          SystemMessage(
            "You are an expert game designer specializing in text adventure games. Evaluate theme ideas and enhance them."),
          UserMessage(validationPrompt)
        )
      )

      client.complete(conversation) match {
        case Right(response) =>
          val responseText = response.content
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
  // Ensure executor is shut down on JVM exit
  sys.addShutdownHook {
    try imageExecutor.shutdownNow()
    catch { case _: Throwable => () }
  }

  // Start WebSocket server for all game communication
  private val wsPort = org.llm4s.config.EnvLoader
    .get("SZORK_WS_PORT")
    .flatMap(p => scala.util.Try(p.toInt).toOption)
    .getOrElse(9002) // Default WS port
  private val wsServer = new TypedWebSocketServer(wsPort, sessionManager)(imageEC)
  wsServer.start()
  logger.info(s"Type-safe WebSocket server started on port $wsPort for all game communication")
  if (wsPort == config.port) {
    logger.warn(
      s"Configured WebSocket port ($wsPort) equals HTTP port (${config.port}). WebSocket uses a separate TCP server; ensure no port conflicts or use a reverse proxy.")
  }

  // Log a clear network summary block
  private def logNetworkSummary(): Unit = {
    logger.info("=== Network Endpoints ===")
    logger.info(f"HTTP        : http://${config.host}:${config.port}")
    logger.info(f"WebSocket   : ws://${config.host}:${wsPort}")
    logger.info("Env Overrides: SZORK_HOST, SZORK_PORT, SZORK_WS_PORT")
    logger.info("Dev Helper  : Vite proxy ws://localhost:3090/ws -> ws://localhost:" + wsPort)
    logger.info("==========================")
  }
  logNetworkSummary()

  @get("/api/health")
  def health(): ujson.Value = {
    logger.debug("Health check requested")
    ujson.Obj(
      "status" -> "healthy",
      "service" -> "szork-server"
    )
  }

  @get("/api/games")
  def listSavedGames(): ujson.Value = {
    logger.debug("Listing saved games")
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
  def generateAdventure(request: Request): ujson.Value = {
    if (config.llmConfig.isEmpty) {
      logger.error("Adventure generation requested but no LLM is configured")
      return ujson.Obj(
        "status" -> "error",
        "message" -> "LLM unavailable: server not configured for text generation"
      )
    }
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

    val llmClientResult = org.llm4s.llmconnect.LLMConnect.getClient(EnvLoader)
    val llmClient = llmClientResult match {
      case Right(c) => c
      case Left(error) =>
        logger.error(s"Failed to get LLM client: ${error.message}")
        return ujson.Obj(
          "success" -> false,
          "error" -> "LLM service unavailable"
        )
    }
    AdventureGenerator.generateAdventureOutline(themePrompt, artStyleId)(llmClient) match {
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
          "message" -> error.userMessage
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
  def getSession(sessionId: String): ujson.Value = {
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
  def serveApp(): String =
    """<!DOCTYPE html>
      |<html>
      |<head><title>Szork</title></head>
      |<body>
      |<h1>Szork Server</h1>
      |<p>Use the frontend at http://localhost:3090 or the API endpoints directly.</p>
      |</body>
      |</html>""".stripMargin

  // Initialize music generation if configured
  // private val musicGen = MusicGeneration() // Commented out - not currently used

  override def port: Int = config.port
  override def host: String = config.host

  logger.info(s"Starting Szork Server on http://$host:$port")

  def allRoutes = Seq(this)

  @post("/api/game/save/:sessionId")
  def saveGame(sessionId: String): ujson.Value = {
    logger.info(s"Saving game for session: $sessionId")

    sessionManager.getSession(sessionId) match {
      case Some(session) =>
        try {
          // Increment step number for manual save
          val stepNumber = session.engine.incrementStepNumber()

          // Create step data for manual save
          val stepData = session.engine.createStepData(
            gameId = session.gameId,
            gameTheme = session.theme,
            gameArtStyle = session.artStyle,
            userCommand = Some("[manual save]"),
            narrationText = "",
            response = None,
            executionTimeMs = 0L
          )

          StepPersistence.saveStep(stepData) match {
            case Right(_) =>
              // Update game metadata
              StepPersistence.loadGameMetadata(session.gameId) match {
                case Right(metadata) =>
                  val updated = GameMetadataHelper.afterStep(metadata, stepNumber, 0L)
                  StepPersistence.saveGameMetadata(updated) match {
                    case Right(_) =>
                      logger.info(s"Game saved successfully: ${session.gameId} (step $stepNumber)")
                      ujson.Obj(
                        "status" -> "success",
                        "gameId" -> session.gameId
                      )
                    case Left(error) =>
                      logger.error(s"Failed to update metadata: ${error.message}")
                      ujson.Obj(
                        "status" -> "error",
                        "error" -> error.userMessage
                      )
                  }
                case Left(error) =>
                  logger.error(s"Failed to load metadata: ${error.message}")
                  ujson.Obj(
                    "status" -> "error",
                    "error" -> error.userMessage
                  )
              }
            case Left(error) =>
              logger.error(s"Failed to save game: ${error.message}")
              ujson.Obj(
                "status" -> "error",
                "error" -> error.userMessage
              )
          }
        } catch {
          case e: Exception =>
            logger.error(s"Error saving game", e)
            ujson.Obj(
              "status" -> "error",
              "error" -> s"Save failed: ${e.getMessage}"
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
  def loadGame(gameId: String): ujson.Value = {
    logger.info(s"Loading game: $gameId")

    // Load game from step-based persistence
    GamePersistence.loadGame(gameId) match {
      case Right(gameState) =>
        // Load metadata to get current step
        val metadata = StepPersistence.loadGameMetadata(gameId) match {
          case Right(meta) => Some(meta)
          case Left(error) =>
            logger.warn(s"Could not load metadata for $gameId: ${error.message}")
            None
        }

        // Create new session for loaded game
        val sessionId = IdGenerator.sessionId()
        val llmClientResult = org.llm4s.llmconnect.LLMConnect.getClient(EnvLoader)
        val llmClient = llmClientResult match {
          case Right(c) => c
          case Left(error) =>
            logger.error(s"Failed to get LLM client: ${error.message}")
            return ujson.Obj(
              "status" -> "error",
              "error" -> "LLM service unavailable"
            )
        }
        val engine =
          GameEngine.create(llmClient, sessionId, gameState.theme.map(_.prompt), gameState.artStyle.map(_.id), None)

        // Restore the game state
        engine.restoreGameState(gameState)

        // Restore step number from metadata
        metadata.foreach { meta =>
          engine.setStepNumber(meta.currentStep)
          logger.debug(s"Restored game at step ${meta.currentStep}")
        }

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
          "scene" -> gameState.currentScene
            .map { scene =>
              ujson.Obj(
                "locationId" -> scene.locationId,
                "locationName" -> scene.locationName,
                "narrationText" -> scene.narrationText,
                "exits" -> scene.exits.map(exit =>
                  ujson.Obj(
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
            }
            .getOrElse(ujson.Null),
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
          "error" -> error.userMessage
        )
    }
  }

  @get("/api/game/list")
  def listGames(): ujson.Value = {
    logger.debug("Listing saved games")

    val games = GamePersistence.listGames()
    ujson.Obj(
      "status" -> "success",
      "games" -> games.map { game =>
        ujson.Obj(
          "gameId" -> game.gameId,
          "theme" -> game.theme,
          "artStyle" -> game.artStyle,
          "adventureTitle" -> game.adventureTitle,
          "createdAt" -> game.createdAt,
          "lastSaved" -> game.lastSaved,
          "lastPlayed" -> game.lastPlayed,
          "totalPlayTime" -> game.totalPlayTime,
          "currentStep" -> game.currentStep,
          "totalSteps" -> game.totalSteps
        )
      }
    )
  }

  @get("/api/game/cache/:gameId")
  def getCacheStats(gameId: String): ujson.Value = {
    logger.debug(s"Getting cache stats for game: $gameId")
    
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
  def deleteGame(gameId: String): ujson.Value = {
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
          "error" -> error.userMessage
        )
    }
  }

  @delete("/api/game/cache/:gameId")
  def clearGameCache(gameId: String): ujson.Value = {
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
          "error" -> error.userMessage
        )
    }
  }

  // Initialize routes
  initialize()
}
