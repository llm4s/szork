package org.llm4s.szork.api

import cask._
import org.slf4j.LoggerFactory
import org.llm4s.config.EnvLoader
import org.llm4s.llmconnect.LLMClient
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try
import java.util.concurrent.Executors
import org.llm4s.szork.game._
import org.llm4s.szork.websocket.TypedWebSocketServer
import org.llm4s.szork.persistence.GamePersistence
import org.llm4s.szork.error.PersistenceError
import org.llm4s.szork.media.MediaCache

/** Game theme configuration with prompt text.
  *
  * @param id
  *   Theme identifier (e.g., "fantasy", "sci-fi")
  * @param name
  *   Display name
  * @param prompt
  *   Description text for AI generation
  */
case class GameTheme(
  id: String,
  name: String,
  prompt: String
)

/** Visual art style for generated images.
  *
  * @param id
  *   Style identifier (e.g., "pixel", "painting")
  * @param name
  *   Display name
  */
case class ArtStyle(
  id: String,
  name: String
)

/** Main HTTP server providing REST API and WebSocket endpoints.
  *
  * Serves the Szork web interface and manages game sessions. Integrates with TypedWebSocketServer for real-time
  * gameplay communication.
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
  private val imageKeysPresent: Boolean = ImageProvider.imageCredsAvailable(config.imageProvider)

  logger.info("=== Feature Availability ===")
  logger.info(s"LLM Text Generation: ${config.llmConfig.map(_.provider).getOrElse("Unavailable")}")
  logger.info(s"Images: ${
      if (imageRequested && imageKeysPresent) s"Enabled (${ImageProvider.toString(config.imageProvider)})"
      else if (imageRequested) s"Disabled (missing credentials for ${ImageProvider.toString(config.imageProvider)})"
      else "Disabled"
    }")
  logger.info(s"Music: ${
      if (musicRequested && replicateKeyPresent) "Enabled (Replicate)"
      else if (musicRequested) "Disabled (REPLICATE_API_KEY not set)"
      else "Disabled (by config)"
    }")
  logger.info(s"Speech-to-Text: ${
      if (sttRequested && openAIKeyPresent) "Enabled (OpenAI Whisper)"
      else if (sttRequested) "Disabled (OPENAI_API_KEY not set)"
      else "Disabled (by config)"
    }")
  logger.info(s"Text-to-Speech: ${
      if (ttsRequested && openAIKeyPresent) "Enabled (OpenAI TTS)"
      else if (ttsRequested) "Disabled (OPENAI_API_KEY not set)"
      else "Disabled (by config)"
    }")
  logger.info("===========================")

  @get("/api/feature-flags")
  def featureFlags(): ujson.Value = {
    val imageRequested = config.imageGenerationEnabled && config.imageProvider != ImageProvider.None
    val imageCreds = ImageProvider.imageCredsAvailable(config.imageProvider)
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

  // One LLM client, shared by every game and request; fail fast if it cannot be created
  private val llmClient: LLMClient = SzorkConfig.getLLMClient() match {
    case Right(client) =>
      logger.info("LLM client initialized successfully")
      client
    case Left(error) =>
      logger.error(s"Failed to initialize LLM client: $error")
      throw new IllegalStateException(s"LLM initialization failed: $error")
  }
  // The Dungeon Master agents' tracing (TRACING_MODE), shared by every game
  private val tracing = AgentTracing.fromEnvironment

  // Cask's debug mode sends exception details to clients in error responses
  override def debugMode: Boolean = false

  // POST endpoints accept only JSON bodies: a cross-site page can send a "simple" text/plain POST without a CORS
  // preflight, but not an application/json one, so this stops other sites spending the server's API credits.
  private def isJson(request: Request): Boolean =
    request.headers.get("content-type").exists(_.exists(_.toLowerCase.startsWith("application/json")))

  private val notJson: ujson.Value =
    ujson.Obj("status" -> "error", "message" -> "Content-Type must be application/json")

  @post("/api/game/validate-theme")
  def validateTheme(request: Request): ujson.Value = {
    if (!isJson(request)) return notJson
    scala.util.Try(ujson.read(request.text())("theme").str).toOption match {
      case None => ujson.Obj("valid" -> false, "message" -> "Request must be a JSON object with a \"theme\" string")
      case Some(themeDescription) => validateThemeDescription(themeDescription)
    }
  }

  private def validateThemeDescription(themeDescription: String): ujson.Value = {

    logger.info(s"Validating custom theme: ${themeDescription.take(100)}...")

    // Use LLM to validate and enhance the theme
    import org.llm4s.llmconnect.model.{SystemMessage, UserMessage, Conversation}

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
      val conversation = Conversation( // Conversation is used in client.complete below
        Seq(
          SystemMessage(
            "You are an expert game designer specializing in text adventure games. Evaluate theme ideas and enhance them."),
          UserMessage(validationPrompt)
        ))

      llmClient.complete(conversation) match {
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
                // The validator's answer was unreadable: let the player proceed with their theme
                ujson.Obj(
                  "validated" -> false,
                  "valid" -> true,
                  "message" -> "",
                  "enhancedTheme" -> themeDescription
                )
            }
          } else {
            // The validator gave no JSON: let the player proceed with their theme
            ujson.Obj(
              "validated" -> false,
              "valid" -> true,
              "message" -> "",
              "enhancedTheme" -> themeDescription
            )
          }
        case Left(error) =>
          logger.error(s"Failed to validate theme: $error")
          ujson.Obj(
            "validated" -> false,
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
  private val capabilities = Capabilities.from(config)

  private def daemonPool(name: String, threads: Int) = Executors.newFixedThreadPool(
    threads,
    { (r: Runnable) =>
      val t = new Thread(r, name)
      t.setDaemon(true)
      t
    })
  // Session work (turns, saves) runs here, one task at a time per session; media generation has its own pools so
  // slow image or music providers cannot hold up turns. Music has a pool of its own because each job blocks for up
  // to ~30s while it polls Replicate, which would otherwise hold up images.
  private val workPool = daemonPool("szork-work", 16)
  private val mediaPool = daemonPool("szork-media", 4)
  private val musicPool = daemonPool("szork-music", 4)

  // Start WebSocket server for all game communication
  private val wsPort = org.llm4s.config.EnvLoader
    .get("SZORK_WS_PORT")
    .flatMap(p => scala.util.Try(p.toInt).toOption)
    .getOrElse(9002) // Default WS port
  private val wsServer = new TypedWebSocketServer(
    address = new java.net.InetSocketAddress(config.host, wsPort),
    sessionManager = sessionManager,
    capabilities = capabilities,
    engineFactory = EngineFactory.default(llmClient, tracing, config.dmContextWindow),
    sttClient = Option.when(capabilities.stt)(new org.llm4s.szork.adapters.DefaultSTTClient()),
    workPool = workPool,
    mediaEC = ExecutionContext.fromExecutor(mediaPool),
    musicEC = ExecutionContext.fromExecutor(musicPool),
    autoSave = config.autoSaveEnabled,
    allowedOrigins = Some(config.allowedOrigins)
  )
  wsServer.start()
  sys.addShutdownHook {
    def quietly(what: String)(action: => Unit): Unit =
      try action
      catch { case e: Throwable => logger.warn(s"Shutdown: $what failed: ${e.getMessage}") }
    quietly("stopping the WebSocket server")(wsServer.stop(1000))
    quietly("stopping the thread pools") { workPool.shutdownNow(); mediaPool.shutdownNow(); musicPool.shutdownNow() }
    // Langfuse sends traces in batches: flush the last one
    quietly("shutting down tracing")(tracing.foreach(_.shutdown()))
    quietly("closing the LLM client")(llmClient.close())
  }
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
    if (!isJson(request)) return notJson
    if (config.llmConfig.isEmpty) {
      logger.error("Adventure generation requested but no LLM is configured")
      return ujson.Obj(
        "status" -> "error",
        "message" -> "LLM unavailable: server not configured for text generation"
      )
    }
    AdventureRequest.parse(request.text()) match {
      case Left(message) =>
        logger.warn(s"Rejected generate-adventure request: $message")
        ujson.Obj("status" -> "error", "message" -> message)
      case Right((theme, artStyle)) =>
        generateAdventureFor(theme, artStyle)
    }
  }

  private def generateAdventureFor(theme: Option[GameTheme], artStyle: Option[ArtStyle]): ujson.Value = {
    logger.info("Generating adventure outline")
    val themePrompt = theme.map(_.prompt).getOrElse("classic fantasy adventure")
    val artStyleId = artStyle.map(_.id).getOrElse("fantasy")

    logger.info(s"Generating adventure for theme: ${theme.map(_.name).getOrElse("default")}")

    given LLMClient = llmClient
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
          "message" -> error.userMessage
        )
    }
  }

  // All gameplay (new/load game, commands, media notifications) goes through the WebSocket server.

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

  override def port: Int = config.port
  override def host: String = config.host

  logger.info(s"Starting Szork Server on http://$host:$port")

  def allRoutes = Seq(this)

  @delete("/api/game/:gameId")
  def deleteGame(gameId: String): ujson.Value = {
    if (!IdGenerator.isValidGameId(gameId))
      return ujson.Obj("status" -> "error", "error" -> s"Invalid game id: ${gameId.take(40)}")
    logger.info(s"Deleting game: $gameId")

    // End any live session for the game first, so it cannot save the game again once its files are gone. The files
    // are deleted after the session's queued work (a turn in progress, say) has finished.
    val deleteResult =
      Try(Await.result(wsServer.endGame(gameId)(GamePersistence.deleteGame(gameId)), 2.minutes)).toEither.left
        .map(e => PersistenceError(s"Failed to delete game: ${e.getMessage}", Some(e), operation = "delete"))
        .flatten

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

  // Initialize routes
  initialize()
}
