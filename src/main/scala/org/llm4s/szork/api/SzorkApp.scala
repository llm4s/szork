package org.llm4s.szork.api

import io.undertow.{Undertow, UndertowOptions}
import io.undertow.server.HttpHandler
import io.undertow.util.Headers
import org.slf4j.{Logger, LoggerFactory}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.trace.Tracing
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.net.InetSocketAddress
import java.util.concurrent.{ExecutorService, Executors}
import org.llm4s.szork.adapters.DefaultSTTClient
import org.llm4s.szork.websocket.TypedWebSocketServer
import org.llm4s.szork.media.SceneImages
import org.llm4s.szork.spi.STTClient
import org.llm4s.szork.adventures.AdventureLibrary

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
  tracing: Option[Tracing],
  adventures: Option[AdventureLibrary] = None,
  deleteWait: FiniteDuration = SzorkRoutes.DeleteWait
) {
  import SzorkConfig.ConfigOps

  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  config.useStorage()

  /** The adventure library: the one the engines were given, or the one at `config.adventuresDirectory`. */
  val library: AdventureLibrary = adventures.getOrElse(AdventureLibrary.at(config))

  private def daemonPool(name: String, threads: Int): ExecutorService = {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    Executors.newFixedThreadPool(
      threads,
      { (r: Runnable) =>
        val t = new Thread(r, s"$name-${count.incrementAndGet()}")
        t.setDaemon(true)
        t
      })
  }
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
    allowedOrigins = Some(config.allowedOrigins),
    library = Some(library)
  )

  val routes: SzorkRoutes = new SzorkRoutes(config, capabilities, llmClient, tracing, wsServer, library, deleteWait)

  private val caskMain = new cask.Main {
    override def allRoutes: Seq[cask.Routes] = Seq(routes)
    // Cask's debug mode sends exception details to clients in error responses
    override def debugMode: Boolean = false
  }
  // Requests are checked before Cask sees them (see SzorkApp.refusal). Undertow also cuts off a body larger than the
  // limit that does not declare its length.
  private val guarded: HttpHandler = { exchange =>
    def header(name: io.undertow.util.HttpString) =
      Option(exchange.getRequestHeaders.getFirst(name)).map(_.trim).filter(_.nonEmpty)
    SzorkApp.refusal(config, header(Headers.HOST), header(Headers.ORIGIN), exchange.getRequestContentLength) match {
      case Some((status, message)) =>
        logger.warn(s"Refused ${exchange.getRequestMethod} ${exchange.getRequestPath}: $message")
        exchange.setStatusCode(status)
        exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, "application/json")
        exchange.getResponseSender.send(ujson.write(ujson.Obj("status" -> "error", "message" -> message)))
      case None => caskMain.defaultHandler.handleRequest(exchange)
    }
  }
  private val http =
    Undertow.builder
      .addHttpListener(config.port, config.host)
      .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, java.lang.Long.valueOf(SzorkConfig.MaxRequestBytes))
      .setHandler(guarded)
      .build

  def start(): Unit = {
    wsServer.setReuseAddr(true)
    wsServer.start()
    http.start()
    SzorkApp.logFeatures(logger, config, capabilities)
    SzorkApp.warnIfExposed(logger, config)
    logger.info(s"Adventure Library: ${library.list.size} adventures in ${config.adventuresDirectory}")
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
    quietly("shutting down tracing")(tracing.foreach(_.close()))
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
      // The library's pictures and mood tracks play with no provider at all
      val library = AdventureLibrary.at(config)
      val provided = Capabilities.from(config)
      val capabilities = provided.copy(
        image = sceneImages.isDefined || (config.imageGenerationEnabled && library.hasImages),
        music = provided.music || (config.musicEnabled && library.hasMusic)
      )
      new SzorkApp(
        config,
        llmClient,
        capabilities,
        EngineFactory.default(llmClient, tracing, config, sceneImages, Some(library)),
        Option.when(capabilities.stt)(new DefaultSTTClient(config.speech)),
        tracing,
        // The library the engines keep pictures and tracks in is the one the routes serve them from
        adventures = Some(library)
      )
    }

  /** Why an HTTP request is refused, as its status and message, if it is: a Host header that is not one of
    * [[SzorkConfig.httpHosts]] (which defeats DNS rebinding), an Origin header that is not one of the allowed origins
    * (requests without one, such as the browser's same-origin GETs and its image loads, pass), or a body larger than
    * [[SzorkConfig.MaxRequestBytes]] (`contentLength` is -1 when the request does not declare it).
    *
    * The Vite dev server's proxy sends the backend's own address as the Host and passes the page's Origin (the dev
    * frontend, allowed by default) through.
    */
  private[szork] def refusal(
    config: SzorkConfig,
    host: Option[String],
    origin: Option[String],
    contentLength: Long): Option[(Int, String)] =
    if (!config.httpHosts.forall(allowed => host.exists(hostAllowed(_, allowed))))
      Some(403 -> s"This server does not answer to the host ${host.getOrElse("(none)").take(100)}.")
    else if (!TypedWebSocketServer.originAllowed(origin, Some(config.allowedOrigins)))
      Some(403 -> s"Requests from ${origin.getOrElse("").take(100)} are not allowed (see SZORK_ALLOWED_ORIGINS).")
    else
      Option.when(contentLength > SzorkConfig.MaxRequestBytes)(
        413 -> s"The request is too large: at most ${SzorkConfig.MaxRequestBytes / 1024} KB.")

  /** Whether the Host header `host` is one of `allowed`: a `name:port` entry matches exactly, a bare name on any port.
    */
  private def hostAllowed(host: String, allowed: Set[String]): Boolean = {
    val h = host.trim.toLowerCase
    val withPort = """(.*):\d+""".r
    val name = h match {
      case withPort(n) => n
      case _ => h
    }
    allowed.contains(h) || allowed.contains(name)
  }

  /** Warns, loudly, that a server listening beyond this machine can be played (on its API keys) by anyone who reaches
    * it.
    */
  private[api] def warnIfExposed(logger: Logger, config: SzorkConfig): Unit =
    if (!SzorkConfig.isLoopback(config.host)) {
      val line = "!" * 100
      logger.warn(line)
      logger.warn(s"SZORK_HOST=${config.host}: the server is reachable from other machines, and anyone who reaches it")
      logger.warn("can play on this server's API keys. Szork has no login; expose it only on a network you trust.")
      if (config.httpHosts.isEmpty)
        logger.warn("The HTTP API's Host header is not checked: set SZORK_ALLOWED_HOSTS to the names it is reached by.")
      logger.warn(line)
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
