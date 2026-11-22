package org.llm4s.szork.api

import cask._
import io.undertow.Undertow
import org.slf4j.{Logger, LoggerFactory}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, SystemMessage, UserMessage}
import org.llm4s.trace.Tracing
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try
import java.net.InetSocketAddress
import java.util.concurrent.{ExecutorService, Executors}
import org.llm4s.szork.adapters.DefaultSTTClient
import org.llm4s.szork.game._
import org.llm4s.szork.websocket.TypedWebSocketServer
import org.llm4s.szork.persistence.GamePersistence
import org.llm4s.szork.error.PersistenceError
import org.llm4s.szork.media.{MediaCache, SceneImages}
import org.llm4s.szork.spi.STTClient

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

/** The Szork server: the HTTP API ([[SzorkRoutes]]) and the gameplay WebSocket server, from the configuration and
  * clients it is given. `start` opens both; `stop` closes them and the thread pools, the LLM client and tracing.
  *
  * The save directory and media cache are process-wide, so creating a server points them at `config`'s.
  */
final class SzorkApp(
  val config: SzorkConfig,
  llmClient: LLMClient,
  val capabilities: Capabilities,
  engineFactory: EngineFactory,
  sttClient: Option[STTClient],
  tracing: Option[Tracing]
) {
  import SzorkConfig.ConfigOps

  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  config.useStorage()

  private def daemonPool(name: String, threads: Int): ExecutorService = Executors.newFixedThreadPool(
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

  val wsServer: TypedWebSocketServer = new TypedWebSocketServer(
    address = new InetSocketAddress(config.host, config.wsPort),
    sessionManager = new SessionManager(),
    capabilities = capabilities,
    engineFactory = engineFactory,
    sttClient = sttClient,
    workPool = workPool,
    mediaEC = ExecutionContext.fromExecutor(mediaPool),
    musicEC = ExecutionContext.fromExecutor(musicPool),
    autoSave = config.autoSaveEnabled,
    sessionTtlMillis = config.sessionTtlMillis,
    allowedOrigins = Some(config.allowedOrigins)
  )

  val routes: SzorkRoutes = new SzorkRoutes(config, capabilities, llmClient, tracing, wsServer)

  private val caskMain = new cask.Main {
    override def allRoutes: Seq[cask.Routes] = Seq(routes)
    // Cask's debug mode sends exception details to clients in error responses
    override def debugMode: Boolean = false
  }
  private val http =
    Undertow.builder.addHttpListener(config.port, config.host).setHandler(caskMain.defaultHandler).build

  def start(): Unit = {
    wsServer.setReuseAddr(true)
    wsServer.start()
    http.start()
    SzorkApp.logFeatures(logger, config, capabilities)
    logger.info("=== Network Endpoints ===")
    logger.info(s"HTTP        : http://${config.host}:${config.port}")
    logger.info(s"WebSocket   : ws://${config.host}:${config.wsPort}")
    logger.info("Env Overrides: SZORK_HOST, SZORK_PORT, SZORK_WS_PORT")
    logger.info(s"Dev Helper  : Vite proxy ws://localhost:3090/ws -> ws://localhost:${config.wsPort}")
    logger.info("==========================")
  }

  def stop(): Unit = {
    def quietly(what: String)(action: => Unit): Unit =
      try action
      catch { case e: Throwable => logger.warn(s"Shutdown: $what failed: ${e.getMessage}") }
    quietly("stopping the HTTP server")(http.stop())
    quietly("stopping the WebSocket server")(wsServer.stop(1000))
    quietly("stopping the thread pools") {
      workPool.shutdownNow(); mediaPool.shutdownNow(); musicPool.shutdownNow(); caskMain.executionContext.shutdown()
    }
    // Langfuse sends traces in batches: flush the last one
    quietly("shutting down tracing")(tracing.foreach(_.shutdown()))
    quietly("closing the LLM client")(llmClient.close())
  }
}

object SzorkApp {

  /** A server with the real clients for `config`: its LLM, tracing (TRACING_MODE), scene images and speech. */
  def fromConfig(config: SzorkConfig): Either[String, SzorkApp] =
    SzorkConfig.getLLMClient(config).map { llmClient =>
      // The Dungeon Master agents' tracing, shared by every game
      val tracing = AgentTracing.fromEnvironment
      // One llm4s image client for every game's scene images, traced with the Dungeon Master (None: no scene images)
      val sceneImages = SceneImages.fromConfig(config, tracing)
      val capabilities = Capabilities.from(config).copy(image = sceneImages.isDefined)
      new SzorkApp(
        config,
        llmClient,
        capabilities,
        EngineFactory.default(llmClient, tracing, config, sceneImages),
        Option.when(capabilities.stt)(new DefaultSTTClient(config.speech)),
        tracing
      )
    }

  /** Logs which optional features the server provides, and why any it does not are off. */
  private[api] def logFeatures(logger: Logger, config: SzorkConfig, capabilities: Capabilities): Unit = {
    def feature(available: Boolean, enabled: Boolean, provider: String, missing: String): String =
      if (available) s"Enabled ($provider)"
      else if (enabled) s"Disabled ($missing not set)"
      else "Disabled (by config)"
    logger.info("=== Feature Availability ===")
    logger.info(s"LLM Text Generation: ${config.llmConfig.map(_.provider).getOrElse("Unavailable")}")
    logger.info(
      s"Images: ${SceneImages.config(config).fold(reason => s"Disabled ($reason)", c => s"Enabled (${c.provider} ${c.model})")}")
    logger.info(s"Music: ${feature(capabilities.music, config.musicEnabled, "Replicate", "REPLICATE_API_KEY")}")
    logger.info(s"Speech-to-Text: ${feature(capabilities.stt, config.sttEnabled, "OpenAI Whisper", "OPENAI_API_KEY")}")
    logger.info(s"Text-to-Speech: ${feature(capabilities.tts, config.ttsEnabled, "OpenAI TTS", "OPENAI_API_KEY")}")
    logger.info("===========================")
  }
}

/** The HTTP API: feature flags, saved games, theme validation and adventure generation. All gameplay goes through the
  * WebSocket server.
  */
final class SzorkRoutes(
  config: SzorkConfig,
  capabilities: Capabilities,
  llmClient: LLMClient,
  tracing: Option[Tracing],
  wsServer: TypedWebSocketServer
) extends cask.Routes {

  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  @get("/api/feature-flags")
  def featureFlags(): ujson.Value = {
    def flag(enabled: Boolean, available: Boolean) = ujson.Obj("enabled" -> enabled, "available" -> available)
    ujson.Obj(
      "llm" -> ujson.Obj(
        "provider" -> config.llmConfig.map(_.provider).getOrElse(""),
        "available" -> config.llmConfig.isDefined
      ),
      "image" -> ujson.Obj(
        "enabled" -> config.imageGenerationEnabled,
        "provider" -> ImageProvider.toString(config.imageProvider),
        "available" -> capabilities.image
      ),
      "music" -> flag(config.musicEnabled, capabilities.music),
      "tts" -> flag(config.ttsEnabled, capabilities.tts),
      "stt" -> flag(config.sttEnabled, capabilities.stt)
    )
  }

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

    // When the validator cannot be asked or its answer is unreadable, the player proceeds with their theme
    val unvalidated = ujson.Obj(
      "validated" -> false,
      "valid" -> true,
      "message" -> "",
      "enhancedTheme" -> themeDescription
    )

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
    val conversation = Conversation(
      Seq(
        SystemMessage(
          "You are an expert game designer specializing in text adventure games. Evaluate theme ideas and enhance them."),
        UserMessage(validationPrompt)
      ))

    Try(llmClient.complete(conversation)).toEither.left.map(_.getMessage).flatMap(_.left.map(_.message)) match {
      case Right(response) =>
        val text = response.content
        val (start, end) = (text.indexOf('{'), text.lastIndexOf('}'))
        Try(ujson.read(text.substring(start, end + 1))).filter(_ => start >= 0 && end > start).toOption match {
          case Some(parsed) =>
            Try(
              ujson.Obj(
                "valid" -> parsed("valid"),
                "message" -> parsed.obj.getOrElse("message", ujson.Str("")).str,
                "enhancedTheme" -> parsed.obj.getOrElse("enhancedTheme", ujson.Str(themeDescription)).str
              )).getOrElse(unvalidated)
          case None => unvalidated
        }
      case Left(error) =>
        logger.error(s"Failed to validate theme: $error")
        unvalidated
    }
  }

  @get("/api/health")
  def health(): ujson.Value =
    ujson.Obj(
      "status" -> "healthy",
      "service" -> "szork-server"
    )

  @get("/api/games")
  def listSavedGames(): ujson.Value = {
    val gamesJson = GamePersistence.listGames().map { metadata =>
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
    AdventureRequest.parse(request.text()) match {
      case Left(message) =>
        logger.warn(s"Rejected generate-adventure request: $message")
        ujson.Obj("status" -> "error", "message" -> message)
      case Right((theme, _)) =>
        generateAdventureFor(theme)
    }
  }

  private def generateAdventureFor(theme: Option[GameTheme]): ujson.Value = {
    val themePrompt = theme.map(_.prompt).getOrElse("classic fantasy adventure")
    logger.info(s"Generating adventure for theme: ${theme.map(_.name).getOrElse("default")}")

    AdventureGenerator.generateAdventureOutline(themePrompt, tracing)(using llmClient) match {
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

    deleteResult match {
      case Right(_) =>
        logger.info(s"Deleted game: $gameId")
        // Clearing the media cache is best effort: the game is gone either way
        MediaCache.clearGameCache(gameId).left.foreach { error =>
          logger.warn(s"Failed to clear cache for deleted game $gameId: $error")
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

  initialize()
}

/** Starts the server from the environment's configuration (see [[SzorkConfig.load]]). */
object SzorkServer {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  def main(args: Array[String]): Unit = {
    import SzorkConfig.ConfigOps
    val config = SzorkConfig.load()
    config.logConfiguration(logger)
    config.validate().left.foreach { error =>
      logger.error("Configuration validation failed:")
      error.message.split("\n").foreach(line => logger.error(s"  - $line"))
      logger.warn("Server will continue but some features may not work")
    }
    if (config.wsPort == config.port)
      logger.warn(s"The WebSocket port (${config.wsPort}) equals the HTTP port: set SZORK_WS_PORT to another port")

    SzorkApp.fromConfig(config) match {
      case Left(error) =>
        // Without an LLM there is no game to play
        logger.error(s"Failed to initialize LLM client: $error")
        sys.exit(1)
      case Right(app) =>
        app.start()
        sys.addShutdownHook(app.stop())
        logger.info(s"Szork server started on http://${config.host}:${config.port}")
    }
  }
}
