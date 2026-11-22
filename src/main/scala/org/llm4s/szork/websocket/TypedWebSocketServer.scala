package org.llm4s.szork.websocket

import java.net.InetSocketAddress
import java.util.concurrent.{Executor, Executors, ScheduledExecutorService, TimeUnit}
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.{ClientHandshake, ServerHandshakeBuilder}
import org.java_websocket.server.WebSocketServer
import org.slf4j.LoggerFactory
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import org.llm4s.szork.websocket.protocol._
import org.llm4s.szork.api._
import org.llm4s.szork.game._
import org.llm4s.szork.persistence.{ConversationEntry => _, GameResponse => _, _}
import org.llm4s.szork.spi.STTClient

/** WebSocket server for gameplay.
  *
  * Each game is a [[GameSession]] whose work - turns, saves - runs one task at a time on the session's own serial
  * executor, never on the WebSocket I/O thread. A session outlives its connection: a client that reconnects re-attaches
  * with `resumeSession`, and sessions with no connection expire after `sessionTtlMillis`. A game has at most one
  * session, so loading a game that is already open joins its session rather than starting a second writer to its saves;
  * the connection that played it is told with `sessionTakenOver`. Media generation runs on `mediaEC` and `musicEC` and
  * is delivered to whichever connection the session has when it finishes.
  *
  * @param address
  *   Address to listen on
  * @param workPool
  *   Runs session work (each session's tasks in order) and other blocking handlers
  * @param mediaEC
  *   Runs image generation
  * @param musicEC
  *   Runs music generation, which blocks while it polls the provider, so slow music cannot hold up images
  * @param allowedOrigins
  *   Browser origins allowed to connect (None allows any). A handshake with another Origin header is refused, so web
  *   pages on other sites cannot drive the game (and its API credits); clients that send no Origin are allowed.
  */
class TypedWebSocketServer(
  address: InetSocketAddress,
  sessionManager: SessionManager,
  capabilities: Capabilities,
  engineFactory: EngineFactory,
  sttClient: Option[STTClient],
  workPool: Executor,
  mediaEC: ExecutionContext,
  musicEC: ExecutionContext,
  autoSave: Boolean = true,
  sessionTtlMillis: Long = TypedWebSocketServer.DefaultSessionTtlMillis,
  allowedOrigins: Option[Set[String]] = None
) extends WebSocketServer(address) {

  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val workEC = ExecutionContext.fromExecutor(workPool, e => logger.error("Unhandled error in work pool", e))
  private val serverInstanceId = java.util.UUID.randomUUID().toString

  // Which session each connection plays, and the connection each session currently has
  private val connectionSessions = TrieMap[WebSocket, String]()
  private val sessionConnections = TrieMap[String, WebSocket]()

  // Every feature the server can provide: engines get the clients for all of them, and each session uses the ones its
  // current features allow
  private val engineFeatures = SessionFeatures.requested(capabilities, None, None, None, None)

  private val sweeper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r =>
    val t = new Thread(r, "szork-session-sweeper")
    t.setDaemon(true)
    t
  }

  // ============= Connection lifecycle =============

  override def onStart(): Unit = {
    logger.info(s"WebSocket server started on $address")
    sweeper.scheduleAtFixedRate(() => expireSessions(), 1, 1, TimeUnit.MINUTES)
  }

  override def stop(timeout: Int): Unit = {
    sweeper.shutdownNow()
    super.stop(timeout)
  }

  override def onWebsocketHandshakeReceivedAsServer(
    conn: WebSocket,
    draft: Draft,
    request: ClientHandshake): ServerHandshakeBuilder = {
    val builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    val origin = Option(request.getFieldValue("Origin")).map(_.trim).filter(_.nonEmpty)
    if (!TypedWebSocketServer.originAllowed(origin, allowedOrigins)) {
      logger.warn(s"Refusing WebSocket connection from origin ${origin.getOrElse("")}")
      throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Origin not allowed")
    }
    builder
  }

  override def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit = {
    logger.info(s"WebSocket connection opened from ${conn.getRemoteSocketAddress}")
    send(conn, ConnectedMessage("Connected to SZork WebSocket server", "1.0", serverInstanceId))
  }

  override def onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean): Unit = {
    logger.info(s"WebSocket connection closed ($code): $reason")
    detach(conn)
  }

  override def onError(conn: WebSocket, ex: Exception): Unit =
    logger.error("WebSocket error", ex)

  /** Makes `conn` the connection of `session`, taking it from any connection that had it; that connection is told with
    * [[SessionTakenOverMessage]], so it stops resuming the session. A session `conn` played before is left without a
    * connection, to expire unless it is resumed or its game is loaded again.
    */
  private def attach(conn: WebSocket, session: GameSession): Unit = {
    connectionSessions.put(conn, session.id).filter(_ != session.id).foreach { previous =>
      sessionConnections.remove(previous, conn)
      sessionManager.getSession(previous).foreach(_.touch())
      logger.info(s"Connection left session $previous for ${session.id}")
    }
    sessionConnections.put(session.id, conn).filter(_ != conn).foreach { displaced =>
      // Only if it still plays this session: it may have moved to another meanwhile
      if (connectionSessions.remove(displaced, session.id)) {
        logger.info(s"[${session.id}] Session taken over by another connection")
        send(displaced, SessionTakenOverMessage(session.id, session.gameId))
      }
    }
    session.touch()
  }

  /** Detaches `conn` from its session; the session stays until it expires or is resumed. */
  private def detach(conn: WebSocket): Unit =
    connectionSessions.remove(conn).foreach { sid =>
      sessionConnections.remove(sid, conn)
      sessionManager.getSession(sid).foreach(_.touch())
    }

  private def expireSessions(): Unit =
    try {
      val expired =
        sessionManager.expireIdle(System.currentTimeMillis(), sessionTtlMillis, sessionConnections.contains)
      if (expired.nonEmpty) logger.info(s"Expired idle sessions: ${expired.mkString(", ")}")
    } catch { case e: Throwable => logger.error("Session expiry failed", e) }

  // ============= Messaging =============

  override def onMessage(conn: WebSocket, text: String): Unit =
    ProtocolCodec.decode(text) match {
      case Left(error) =>
        logger.warn(s"[WS-IN] Rejected message: $error (${text.take(200)})")
        send(conn, ErrorMessage("Invalid message", Some(error)))

      case Right(message) =>
        val label = connectionSessions.getOrElse(conn, "no-session")
        message match {
          case PingMessage(timestamp) => send(conn, PongMessage(timestamp))
          case other =>
            logger.info(s"[WS-IN] Session: $label | ${TypedWebSocketServer.describe(other)}")
            dispatch(conn, other)
        }
    }

  private def dispatch(conn: WebSocket, message: ClientMessage): Unit = message match {
    case request: NewGameRequest => runForConnection(conn)(startNewGame(conn, request))
    case request: LoadGameRequest if !IdGenerator.isValidGameId(request.gameId) =>
      send(conn, ErrorMessage("Failed to load game", Some(s"Invalid game id: ${request.gameId.take(40)}")))
    case request: LoadGameRequest => runForConnection(conn)(loadGame(conn, request))
    case ResumeSessionRequest(sessionId) => resume(conn, sessionId)
    case ListGamesRequest() => runForConnection(conn)(listGames(conn))
    case CommandRequest(command) => withSession(conn)(s => runFor(s)(playCommand(s, command)))
    case request: StreamCommandRequest => withSession(conn)(s => runFor(s)(playStreamCommand(s, request)))
    case request: AudioCommandRequest => withSession(conn)(s => runFor(s)(playAudioCommand(s, request)))
    case PingMessage(_) => ()
  }

  private def send(conn: WebSocket, message: ServerMessage): Unit =
    if (conn.isOpen) {
      message match {
        case _: TextChunkMessage | _: PongMessage => logger.debug(s"[WS-OUT] ${ProtocolCodec.typeOf(message)}")
        case _ => logger.info(s"[WS-OUT] ${TypedWebSocketServer.describe(message)}")
      }
      try conn.send(ProtocolCodec.encode(message))
      catch { case e: Exception => logger.warn(s"Failed to send ${ProtocolCodec.typeOf(message)}: ${e.getMessage}") }
    }

  /** Sends to the session's current connection, if it has one. */
  private def sendTo(session: GameSession, message: ServerMessage): Unit =
    sessionConnections.get(session.id).foreach(send(_, message))

  private def withSession(conn: WebSocket)(f: GameSession => Unit): Unit =
    connectionSessions.get(conn).flatMap(sessionManager.getSession) match {
      case Some(session) => f(session)
      case None => send(conn, ErrorMessage("No active session"))
    }

  /** Runs `body` on the session's serial executor, reporting a failure to the player (the details only to the log). */
  private def runFor(session: GameSession)(body: => Unit): Unit =
    Future { session.touch(); body }(using session.executor).onComplete {
      case Failure(e) =>
        logger.error(s"[${session.id}] Session task failed", e)
        sendTo(session, ErrorMessage("Internal server error"))
      case Success(_) => session.touch()
    }(using ExecutionContext.parasitic)

  /** Runs `body` off the WebSocket thread, reporting a failure to `conn` (the details only to the log). */
  private def runForConnection(conn: WebSocket)(body: => Unit): Unit =
    Future(body)(using workEC).failed.foreach { e =>
      logger.error("Request failed", e)
      send(conn, ErrorMessage("Internal server error"))
    }(using ExecutionContext.parasitic)

  /** A session for `engine`, not yet registered. */
  private def makeSession(
    gameId: String,
    sessionId: String,
    engine: GameEngine,
    theme: Option[GameTheme],
    artStyle: Option[ArtStyle],
    features: SessionFeatures): GameSession = {
    val executor = new SerialExecutor(workPool, e => logger.error(s"[$sessionId] Session task error", e))
    new GameSession(sessionId, gameId, engine, theme, artStyle, features, executor)
  }

  /** Ends the session playing `gameId`, if there is one, and runs `body` once that session's queued work (a turn and
    * its save, say) is done, so the session saves nothing after `body`. The session's connection is detached.
    */
  def endGame[A](gameId: String)(body: => A): Future[A] =
    sessionManager.removeGame(gameId) match {
      case None => Future(body)(using workEC)
      case Some(session) =>
        sessionConnections.remove(session.id).foreach(connectionSessions.remove)
        logger.info(s"[${session.id}] Session ended for game $gameId")
        Future(body)(using session.executor)
    }

  // ============= Games =============

  private def startNewGame(conn: WebSocket, request: NewGameRequest): Unit = {
    val sessionId = IdGenerator.sessionId()
    val gameId = IdGenerator.gameId()
    val outline = request.adventureOutline.flatMap { json =>
      Try(AdventureGenerator.outlineFromJson(json)) match {
        case Success(parsed) => Some(parsed)
        case Failure(e) =>
          logger.warn(s"Ignoring unparseable adventure outline: ${e.getMessage}")
          None
      }
    }
    val features = SessionFeatures
      .requested(
        capabilities,
        request.tts,
        request.stt,
        request.image.orElse(Some(request.imageGeneration)),
        request.music)
      .copy(autoSave = autoSave)

    engineFactory.create(sessionId, request.theme, request.artStyle, outline, engineFeatures) match {
      case Left(error) =>
        send(conn, ErrorMessage("Failed to start game", Some(error)))

      case Right(engine) =>
        val theme = request.theme.map(t => GameTheme(t, t, t))
        val artStyle = request.artStyle.map(a => ArtStyle(a, a))
        val session = makeSession(gameId, sessionId, engine, theme, artStyle, features)
        sessionManager.createSession(session)
        attach(conn, session)

        runFor(session) {
          engine.initialize() match {
            case Right(text) =>
              val audio = if (features.tts) engine.narrate(text) else None
              val turn = turnMedia(session, imageWanted = true)
              sendTo(
                session,
                GameStartedMessage(
                  sessionId = sessionId,
                  gameId = gameId,
                  text = text,
                  messageIndex = turn.messageIndex,
                  scene = engine.getCurrentScene.map(convertScene),
                  audio = audio,
                  hasImage = turn.hasImage,
                  hasMusic = turn.hasMusic,
                  ttsEnabled = features.tts,
                  sttEnabled = features.stt,
                  imageEnabled = features.image,
                  musicEnabled = features.music
                )
              )
              startMedia(session, turn)
              if (features.autoSave) saveInitialStep(session, text, outline)

            case Left(error) =>
              sendTo(session, ErrorMessage("Failed to start game", Some(error.message)))
              sessionConnections.remove(session.id).foreach(connectionSessions.remove)
              sessionManager.removeSession(session.id)
          }
        }
    }
  }

  /** The features a load asks for, limited to what the server can provide. */
  private def requestedFeatures(request: LoadGameRequest): SessionFeatures =
    SessionFeatures
      .requested(capabilities, request.tts, request.stt, request.image, request.music)
      .copy(autoSave = autoSave)

  private def loadGame(conn: WebSocket, request: LoadGameRequest): Unit =
    sessionManager.joinGame(request.gameId) { live =>
      attach(conn, live); live
    } match {
      case Some(live) => sendLiveGame(live, requestedFeatures(request))
      case None => loadSavedGame(conn, request)
    }

  /** Loads a game that has no session from its save, and plays it in a new session. */
  private def loadSavedGame(conn: WebSocket, request: LoadGameRequest): Unit =
    GamePersistence.loadGame(request.gameId) match {
      case Left(error) =>
        send(conn, ErrorMessage("Failed to load game", Some(error.message)))

      case Right(gameState) =>
        val sessionId = IdGenerator.sessionId()
        val features = requestedFeatures(request)
        engineFactory.create(
          sessionId,
          gameState.theme.map(_.prompt),
          gameState.artStyle.map(_.id),
          gameState.adventureOutline,
          engineFeatures) match {
          case Left(error) =>
            send(conn, ErrorMessage("Failed to load game", Some(error)))

          case Right(engine) =>
            // The session is not visible to anyone else until it is registered below
            engine.restoreGameState(gameState)
            StepPersistence.loadGameMetadata(request.gameId).foreach(meta => engine.setStepNumber(meta.currentStep))

            val loaded = makeSession(request.gameId, sessionId, engine, gameState.theme, gameState.artStyle, features)
            // Another load of this game may have registered a session meanwhile; if so, this one joins it
            val session = sessionManager.registerOrJoin(loaded) { s =>
              attach(conn, s); s
            }
            if (session ne loaded) sendLiveGame(session, features)
            else
              send(
                conn,
                GameLoadedMessage(
                  sessionId = sessionId,
                  gameId = request.gameId,
                  messageIndex = engine.getMessageCount,
                  conversation = gameState.conversationHistory.map(e => ConversationEntry(e.role, e.content)),
                  currentLocation = gameState.currentScene.map(_.locationId),
                  currentScene = gameState.currentScene.map(convertScene),
                  ttsEnabled = features.tts,
                  sttEnabled = features.stt,
                  imageEnabled = features.image,
                  musicEnabled = features.music
                )
              )
        }
    }

  /** Switches a joined session to the `features` its new connection asked for, then sends the game as the session has
    * it to that connection. Both run after any turn in progress, so no turn sees the features change part-way; media
    * already under way for an earlier turn still arrives.
    */
  private def sendLiveGame(session: GameSession, features: SessionFeatures): Unit =
    runFor(session) {
      session.updateFeatures(features)
      val state = session.engine.getGameState(session.gameId, session.theme, session.artStyle)
      logger.info(s"[${session.id}] Joined the live session of game ${session.gameId} with $features")
      sendTo(
        session,
        GameLoadedMessage(
          sessionId = session.id,
          gameId = session.gameId,
          messageIndex = session.engine.getMessageCount,
          conversation = state.conversationHistory.map(e => ConversationEntry(e.role, e.content)),
          currentLocation = state.currentScene.map(_.locationId),
          currentScene = state.currentScene.map(convertScene),
          ttsEnabled = session.features.tts,
          sttEnabled = session.features.stt,
          imageEnabled = session.features.image,
          musicEnabled = session.features.music
        )
      )
    }

  private def resume(conn: WebSocket, sessionId: String): Unit =
    // Attached while the session cannot expire, so a resume never re-attaches to a session being removed
    sessionManager.withSession(sessionId) { s =>
      attach(conn, s); s
    } match {
      case Some(session) =>
        send(conn, SessionResumedMessage(session.id, session.gameId, session.engine.getMessageCount))
      case None =>
        send(conn, ErrorMessage("Session expired", Some(ErrorMessage.SessionExpired)))
    }

  private def listGames(conn: WebSocket): Unit = {
    val games = GamePersistence.listGames().map(g => GameInfo(g.gameId, g.theme, g.lastPlayed, g.adventureTitle))
    send(conn, GamesListMessage(games))
  }

  // ============= Turns =============

  private def playCommand(session: GameSession, command: String, imageWanted: Boolean = true): Unit = {
    val start = System.currentTimeMillis()
    session.engine.processCommand(command, generateAudio = session.features.tts) match {
      case Right(response) =>
        val turn = turnMedia(session, imageWanted)
        sendTo(
          session,
          CommandResponseMessage(
            text = response.text,
            messageIndex = turn.messageIndex,
            command = command,
            scene = response.scene.map(convertScene),
            audio = response.audioBase64,
            hasImage = turn.hasImage,
            hasMusic = turn.hasMusic
          )
        )
        afterTurn(session, turn, command, response, System.currentTimeMillis() - start)
      case Left(error) =>
        sendTo(session, ErrorMessage("Command failed", Some(error.message)))
    }
  }

  private def playStreamCommand(session: GameSession, request: StreamCommandRequest): Unit = {
    val start = System.currentTimeMillis()
    var chunkCount = 0
    session.engine.processCommand(
      request.command,
      generateAudio = session.features.tts,
      onTextChunk = Some { chunk =>
        chunkCount += 1
        sendTo(session, TextChunkMessage(chunk, chunkCount))
      }
    ) match {
      case Right(response) =>
        val duration = System.currentTimeMillis() - start
        val turn = turnMedia(session, imageWanted = request.imageGeneration.getOrElse(true))
        sendTo(
          session,
          StreamCompleteMessage(
            text = response.text,
            messageIndex = turn.messageIndex,
            totalChunks = chunkCount,
            duration = duration,
            scene = response.scene.map(convertScene),
            audio = response.audioBase64,
            hasImage = turn.hasImage,
            hasMusic = turn.hasMusic
          )
        )
        afterTurn(session, turn, request.command, response, duration)
      case Left(error) =>
        sendTo(session, ErrorMessage("Streaming failed", Some(error.message)))
    }
  }

  private def playAudioCommand(session: GameSession, request: AudioCommandRequest): Unit =
    sttClient.filter(_ => session.features.stt) match {
      case None =>
        sendTo(session, ErrorMessage("Speech-to-Text is not available"))
      case Some(stt) =>
        Try(java.util.Base64.getDecoder.decode(request.audio)).toEither.left
          .map(_ => "audio is not valid base64")
          .flatMap(bytes => stt.transcribeBytes(bytes).left.map(_.message)) match {
          case Right(transcription) =>
            sendTo(session, TranscriptionMessage(transcription))
            playCommand(session, transcription, imageWanted = request.imageGeneration.getOrElse(true))
          case Left(error) =>
            sendTo(session, ErrorMessage("Failed to transcribe audio", Some(error)))
        }
    }

  /** The media a completed turn announces, with the scene it is for, captured when the turn ends. */
  private final case class TurnMedia(messageIndex: Int, scene: Option[GameScene], hasImage: Boolean, hasMusic: Boolean)

  private def turnMedia(session: GameSession, imageWanted: Boolean): TurnMedia = {
    val scene = session.engine.getCurrentScene.filter(_ => session.engine.locationChangedLastTurn)
    TurnMedia(
      messageIndex = session.engine.getMessageCount,
      scene = scene,
      hasImage = scene.isDefined && session.features.image && imageWanted,
      hasMusic = scene.isDefined && session.features.music
    )
  }

  /** After a turn's response is sent: report validation issues, start its media, and save it. */
  private def afterTurn(
    session: GameSession,
    turn: TurnMedia,
    command: String,
    response: GameResponse,
    durationMs: Long): Unit = {
    session.engine.popValidationIssues().foreach { issues =>
      logger.warn(s"[${session.id}] Response issues: ${issues.mkString("; ")}")
    }
    startMedia(session, turn)
    if (session.features.autoSave) {
      session.engine.incrementStepNumber()
      val stepData = session.engine.createStepData(
        gameId = session.gameId,
        gameTheme = session.theme,
        gameArtStyle = session.artStyle,
        userCommand = Some(command),
        narrationText = response.text,
        response = Some(response),
        executionTimeMs = durationMs
      )
      saveStep(session, stepData, durationMs)
    }
  }

  // ============= Media =============

  private def startMedia(session: GameSession, turn: TurnMedia): Unit =
    turn.scene.foreach { scene =>
      if (turn.hasImage)
        deliverMedia(session, turn.messageIndex, MediaKind.Image, mediaEC) {
          session.engine.generateSceneImage(scene, Some(session.gameId)).map(ImageReadyMessage(turn.messageIndex, _))
        }
      if (turn.hasMusic)
        deliverMedia(session, turn.messageIndex, MediaKind.Music, musicEC) {
          session.engine
            .generateBackgroundMusic(scene, Some(session.gameId))
            .map { case (music, mood) => MusicReadyMessage(turn.messageIndex, music, mood) }
        }
    }

  /** Generates media on `ec`; the client gets the result, or a failure notice so it stops waiting. */
  private def deliverMedia(session: GameSession, messageIndex: Int, kind: MediaKind, ec: ExecutionContext)(
    generate: => Option[ServerMessage]): Unit =
    Future(generate)(using ec).onComplete { result =>
      result match {
        case Success(Some(message)) => sendTo(session, message)
        case Success(None) => sendTo(session, MediaFailedMessage(messageIndex, kind))
        case Failure(e) =>
          logger.error(s"[${session.id}] ${kind.wireName} generation failed", e)
          sendTo(session, MediaFailedMessage(messageIndex, kind))
      }
    }(using ExecutionContext.parasitic)

  // ============= Persistence =============

  private def saveInitialStep(session: GameSession, text: String, outline: Option[AdventureOutline]): Unit = {
    val metadata = GameMetadataHelper.initial(
      gameId = session.gameId,
      adventureTitle = outline.map(_.title).getOrElse("Untitled Adventure"),
      theme = session.theme.map(_.id).getOrElse("default"),
      artStyle = session.artStyle.map(_.id).getOrElse("default"),
      createdAt = System.currentTimeMillis()
    )
    val stepData = StepData.initialStep(
      gameId = session.gameId,
      gameState = session.engine.getGameState(session.gameId, session.theme, session.artStyle),
      narrationText = text,
      scene = session.engine.getCurrentScene,
      outline = outline,
      executionTimeMs = 0L
    )
    // game.json last: a game is listed only once its first step is complete
    val saved = StepPersistence.saveStep(stepData).flatMap(_ => StepPersistence.saveGameMetadata(metadata))
    saved.left.foreach(error => logger.error(s"[${session.id}] Initial save failed: ${error.message}"))
  }

  private def saveStep(session: GameSession, stepData: StepData, durationMs: Long): Unit = {
    val saved = for {
      _ <- StepPersistence.saveStep(stepData)
      metadata <- StepPersistence.loadGameMetadata(session.gameId)
      _ <- StepPersistence.saveGameMetadata(
        GameMetadataHelper.afterStep(metadata, stepData.metadata.stepNumber, durationMs))
    } yield ()
    saved match {
      case Right(_) => logger.debug(s"[${session.id}] Saved step ${stepData.metadata.stepNumber}")
      case Left(error) => logger.warn(s"[${session.id}] Auto-save failed: ${error.message}")
    }
  }

  private def convertScene(scene: GameScene): SceneData =
    SceneData(
      locationName = scene.locationName,
      exits = scene.exits.map(exit => ExitData(exit.direction, exit.description.getOrElse(""))),
      items = scene.items,
      npcs = scene.npcs
    )
}

object TypedWebSocketServer {
  val DefaultSessionTtlMillis: Long = 30L * 60 * 1000

  /** Whether a handshake with `origin` may connect: always without an Origin header (non-browser clients). */
  def originAllowed(origin: Option[String], allowed: Option[Set[String]]): Boolean =
    origin.forall(o => allowed.forall(_.contains(o)))

  /** A short description of a message for logs, without large payloads. */
  private[websocket] def describe(message: WebSocketMessage): String = message match {
    case m: NewGameRequest => s"newGame theme=${m.theme.getOrElse("default")} outline=${m.adventureOutline.isDefined}"
    case m: LoadGameRequest => s"loadGame ${m.gameId}"
    case m: ResumeSessionRequest => s"resumeSession ${m.sessionId}"
    case m: CommandRequest => s"command '${m.command.take(50)}'"
    case m: StreamCommandRequest => s"streamCommand '${m.command.take(50)}'"
    case m: AudioCommandRequest => s"audioCommand ${m.audio.length} chars"
    case m: GameStartedMessage => s"gameStarted ${m.gameId} hasImage=${m.hasImage} hasMusic=${m.hasMusic}"
    case m: StreamCompleteMessage => s"streamComplete chunks=${m.totalChunks} ${m.duration}ms hasImage=${m.hasImage}"
    case m: ImageReadyMessage => s"imageReady #${m.messageIndex} ${m.image.length} chars"
    case m: MusicReadyMessage => s"musicReady #${m.messageIndex} ${m.mood}"
    case m: ErrorMessage => s"error ${m.error}${m.details.fold("")(d => s": ${d.take(200)}")}"
    case m: ServerMessage => ProtocolCodec.typeOf(m)
    case m => m.getClass.getSimpleName
  }
}
