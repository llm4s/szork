package org.llm4s.szork.websocket

import java.net.InetSocketAddress
import java.util.concurrent.{Executor, Executors, ScheduledExecutorService, TimeUnit}
import org.java_websocket.WebSocket
import org.java_websocket.drafts.{Draft, Draft_6455}
import org.java_websocket.extensions.IExtension
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
import org.llm4s.szork.persistence._
import org.llm4s.szork.spi.STTClient
import org.llm4s.szork.adventures.{Adventure, AdventureLibrary}
import org.llm4s.szork.media.MediaRef

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
  allowedOrigins: Option[Set[String]] = None,
  library: Option[AdventureLibrary] = None
) extends WebSocketServer(
      address,
      // Messages larger than any the client sends close the connection rather than fill the server's memory
      java.util.List.of[Draft](new Draft_6455(java.util.List.of[IExtension](), SzorkConfig.MaxMessageBytes))
    ) {

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
    // A connection that closed before it was attached has had its onClose already: detach it here, or the session
    // would count as connected, and never expire
    if (!conn.isOpen) detach(conn)
  }

  /** Detaches `conn` from its session; the session stays until it expires or is resumed. */
  private def detach(conn: WebSocket): Unit =
    connectionSessions.remove(conn).foreach { sid =>
      sessionConnections.remove(sid, conn)
      sessionManager.getSession(sid).foreach(_.touch())
    }

  /** Removes sessions with no connection that have been idle for longer than `sessionTtlMillis`; runs every minute. */
  private[szork] def expireSessions(): Unit =
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

  private def dispatch(conn: WebSocket, message: ClientMessage): Unit =
    TypedWebSocketServer.overLimit(message) match {
      case Some(refusal) =>
        logger.warn(s"[WS-IN] Refused ${message.getClass.getSimpleName}: ${refusal.details.getOrElse("")}")
        send(conn, refusal)
      case None =>
        message match {
          case request: NewGameRequest => runForConnection(conn)(startNewGame(conn, request))
          case request: LoadGameRequest if !IdGenerator.isValidGameId(request.gameId) =>
            send(conn, ErrorMessage("Failed to load game", Some(s"Invalid game id: ${request.gameId.take(40)}")))
          case request: LoadGameRequest => runForConnection(conn)(loadGame(conn, request))
          case ResumeSessionRequest(sessionId) => resume(conn, sessionId)
          case CommandRequest(command) => withSession(conn)(s => runTurn(conn, s)(playCommand(s, command)))
          case request: StreamCommandRequest => withSession(conn)(s => runTurn(conn, s)(playStreamCommand(s, request)))
          case request: AudioCommandRequest => withSession(conn)(s => runTurn(conn, s)(playAudioCommand(s, request)))
          case PingMessage(_) => ()
        }
    }

  /** Runs a turn on the session, unless it already has [[SzorkConfig.MaxPendingTurns]] running or waiting: then the
    * command is refused, so a client cannot queue up turns (and model calls) without end.
    */
  private def runTurn(conn: WebSocket, session: GameSession)(body: => Unit): Unit =
    if (!session.beginTurn(SzorkConfig.MaxPendingTurns)) {
      logger.warn(s"[${session.id}] Refused a command: ${SzorkConfig.MaxPendingTurns} turns are already pending")
      send(conn, TypedWebSocketServer.TooManyCommands)
    } else
      // Counted out when the task has run, or been dropped, or failed
      runFor(session, session.endTurn())(body)

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

  /** Runs `body` on the session's serial executor, reporting a failure to the player (the details only to the log). A
    * task that comes to run after the session has ended (its game deleted while the task waited) does nothing.
    */
  private def runFor(session: GameSession, after: => Unit = ())(body: => Unit): Unit =
    Future {
      try
        if (session.ended) logger.info(s"[${session.id}] Dropped a task queued before the session ended")
        else { session.touch(); body }
      finally after
    }(using session.executor).onComplete {
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

  /** Ends the session playing `gameId`, if there is one, and runs `body` once the task in progress on that session (a
    * turn and its save, say) is done; tasks still queued behind it are dropped, so the session saves nothing after
    * `body`. The session's connection is detached.
    */
  def endGame[A](gameId: String)(body: => A): Future[A] = endSession(gameId, notice = None)(body)

  /** Ends the game's session as [[endGame]] does, and runs `delete` when its turn in progress is done; the connection
    * playing the game is told with [[GameDeletedMessage]], so the player is not left sending commands to it.
    */
  def deleteGame[A](gameId: String)(delete: => A): Future[A] =
    endSession(gameId, notice = Some(GameDeletedMessage(gameId)))(delete)

  private def endSession[A](gameId: String, notice: Option[ServerMessage])(body: => A): Future[A] =
    sessionManager.removeGame(gameId) match {
      case None => Future(body)(using workEC)
      case Some(session) =>
        notice.foreach(sendTo(session, _))
        release(session)
        logger.info(s"[${session.id}] Session ended for game $gameId")
        Future(body)(using session.executor)
    }

  /** Detaches the session's connection, unless that connection has moved to another session meanwhile. */
  private def release(session: GameSession): Unit =
    sessionConnections.remove(session.id).foreach(conn => connectionSessions.remove(conn, session.id))

  // ============= Games =============

  private def startNewGame(conn: WebSocket, request: NewGameRequest): Unit =
    request.adventureId match {
      case None => startNewGame(conn, request, None)
      case Some(id) =>
        library.flatMap(_.load(id)) match {
          case Some(adventure) => startNewGame(conn, request, Some(adventure))
          case None => send(conn, ErrorMessage("Failed to start game", Some(s"No adventure '${id.take(60)}'")))
        }
    }

  /** Starts a game: of the library `adventure` if there is one (its outline and theme, and its art style unless the
    * request names another), otherwise from the request's theme, art style and outline.
    */
  private def startNewGame(conn: WebSocket, request: NewGameRequest, adventure: Option[Adventure]): Unit = {
    val sessionId = IdGenerator.sessionId()
    // A new game never takes the id of a saved game, whose saves it would overwrite
    val gameId = Iterator.continually(IdGenerator.gameId()).find(id => !StepPersistence.gameExists(id)).get
    val theme = adventure.fold(TypedWebSocketServer.themeOf(request))(_.theme)
    val artStyle = artStyleOf(request, adventure)
    val outline = adventure.map(_.outline).orElse(request.adventureOutline.flatMap(parseOutline))
    val features = SessionFeatures
      .requested(capabilities, request.tts, request.stt, request.image, request.music)
      .copy(autoSave = autoSave)

    engineFactory.create(sessionId, theme.map(_.prompt), artStyle.map(_.id), outline, engineFeatures) match {
      case Left(error) =>
        send(conn, ErrorMessage("Failed to start game", Some(error)))

      case Right(engine) =>
        adventure.foreach(a => engine.playAdventure(a.id))
        val session = makeSession(gameId, sessionId, engine, theme, artStyle, features)
        if (!sessionManager.createSession(session)) {
          logger.error(s"Session id $sessionId or game id $gameId is already in use; the game was not started")
          send(conn, ErrorMessage("Failed to start game", Some("Please start the game again.")))
        } else playOpening(conn, session, outline)
    }
  }

  /** The art style the request names (with the adventure's display name, if it is the adventure's), or else the
    * adventure's.
    */
  private def artStyleOf(request: NewGameRequest, adventure: Option[Adventure]): Option[ArtStyle] =
    request.artStyle match {
      case None => adventure.flatMap(_.artStyle)
      case Some(id) => adventure.flatMap(_.artStyle).filter(_.id == id).orElse(Some(ArtStyle(id, id)))
    }

  /** The outline a new game brought with it, with its map repaired; None (played without one) if it is not one. */
  private def parseOutline(json: ujson.Value): Option[AdventureOutline] =
    AdventureGenerator.parseOutline(json) match {
      case Right(parsed) => Some(AdventureGenerator.withSoundMap(parsed))
      case Left(error) =>
        logger.warn(s"Ignoring unparseable adventure outline: ${error.message}")
        None
    }

  /** Plays a new game's opening scene in its registered `session`, and saves it. */
  private def playOpening(conn: WebSocket, session: GameSession, outline: Option[AdventureOutline]): Unit = {
    val (engine, features, sessionId, gameId) = (session.engine, session.features, session.id, session.gameId)
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
              title = outline.map(_.title).getOrElse(GameLoadedMessage.Untitled),
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
          logger.warn(s"[${session.id}] Failed to start game: ${error.message}")
          sendTo(session, ErrorMessage("Failed to start game", Some(error.userMessage)))
          release(session)
          sessionManager.removeSession(session.id)
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
    StepPersistence.loadLatestStep(request.gameId).map(_.gameState) match {
      case Left(error) =>
        logger.warn(s"Failed to load game ${request.gameId}: ${error.message}")
        send(conn, ErrorMessage("Failed to load game", Some(error.userMessage)))

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
            else {
              send(conn, gameLoaded(session))
              sendEnding(session)
            }
        }
    }

  /** The game as `session` has it now, for the connection that loaded it. */
  private def gameLoaded(session: GameSession): GameLoadedMessage = {
    val (engine, features) = (session.engine, session.features)
    val state = engine.getGameState(session.gameId, session.theme, session.artStyle)
    GameLoadedMessage(
      sessionId = session.id,
      gameId = session.gameId,
      title = state.adventureTitle.getOrElse(GameLoadedMessage.Untitled),
      messageIndex = engine.getMessageCount,
      conversation = transcript(engine, session.gameId, features.image),
      currentLocation = state.currentScene.map(_.locationId),
      currentScene = state.currentScene.map(convertScene),
      ttsEnabled = features.tts,
      sttEnabled = features.stt,
      imageEnabled = features.image,
      musicEnabled = features.music
    )
  }

  /** Tells `session`'s connection that its adventure is over, if it is. */
  private def sendEnding(session: GameSession): Unit =
    session.engine.ending.foreach(outcome => sendTo(session, GameEndedMessage(session.engine.getMessageCount, outcome)))

  /** After a turn's response: tells the player the adventure is over if this turn ended it (`before` is `engine.ending`
    * as it was when the turn began).
    */
  private def sendEndingReached(session: GameSession, before: Option[String], messageIndex: Int): Unit =
    session.engine.ending.filter(_ => before.isEmpty).foreach { outcome =>
      logger.info(s"[${session.id}] The adventure is over: $outcome")
      sendTo(session, GameEndedMessage(messageIndex, outcome))
    }

  /** Switches a joined session to the `features` its new connection asked for, then sends the game as the session has
    * it to that connection. Both run after any turn in progress, so no turn sees the features change part-way; media
    * already under way for an earlier turn still arrives.
    */
  private def sendLiveGame(session: GameSession, features: SessionFeatures): Unit =
    runFor(session) {
      session.updateFeatures(features)
      logger.info(s"[${session.id}] Joined the live session of game ${session.gameId} with $features")
      sendTo(session, gameLoaded(session))
      sendEnding(session)
    }

  private def resume(conn: WebSocket, sessionId: String): Unit =
    // Attached while the session cannot expire, so a resume never re-attaches to a session being removed
    sessionManager.withSession(sessionId) { s =>
      attach(conn, s); s
    } match {
      case Some(session) =>
        // Read before the message index: a turn that ends in between has answered with a larger index, which tells
        // the client it missed the answer
        val turnInProgress = session.turnInProgress
        send(conn, SessionResumedMessage(session.id, session.gameId, session.engine.getMessageCount, turnInProgress))
        session.engine.ending.foreach(outcome => send(conn, GameEndedMessage(session.engine.getMessageCount, outcome)))
      case None =>
        send(conn, ErrorMessage("Session expired", Some(ErrorMessage.SessionExpired)))
    }

  // ============= Turns =============

  private def playCommand(session: GameSession, command: String, imageWanted: Boolean = true): Unit = {
    val start = System.currentTimeMillis()
    val endedBefore = session.engine.ending
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
        sendEndingReached(session, endedBefore, turn.messageIndex)
        afterTurn(session, turn, command, response, System.currentTimeMillis() - start)
      case Left(error) =>
        logger.warn(s"[${session.id}] Command failed: ${error.message}")
        sendTo(session, ErrorMessage("Command failed", Some(error.userMessage)))
    }
  }

  private def playStreamCommand(session: GameSession, request: StreamCommandRequest): Unit = {
    val start = System.currentTimeMillis()
    var chunkCount = 0
    val endedBefore = session.engine.ending
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
        sendEndingReached(session, endedBefore, turn.messageIndex)
        afterTurn(session, turn, request.command, response, duration)
      case Left(error) =>
        logger.warn(s"[${session.id}] Streaming command failed: ${error.message}")
        sendTo(session, ErrorMessage("Streaming failed", Some(error.userMessage)))
    }
  }

  private def playAudioCommand(session: GameSession, request: AudioCommandRequest): Unit =
    sttClient.filter(_ => session.features.stt) match {
      case None =>
        sendTo(session, ErrorMessage("Speech-to-Text is not available"))
      case Some(stt) =>
        Try(java.util.Base64.getDecoder.decode(request.audio)).toEither.left
          .map(_ => "The recording is not valid Base64.")
          .flatMap(bytes =>
            stt.transcribeBytes(bytes, TypedWebSocketServer.recordingFileName(request.mimeType)).left.map { error =>
              logger.warn(s"[${session.id}] Transcription failed: ${error.message}")
              error.userMessage
            }) match {
          case Right(transcription) if transcription.length > SzorkConfig.MaxCommandChars =>
            sendTo(session, TranscriptionMessage(transcription))
            sendTo(session, TypedWebSocketServer.overLimit(CommandRequest(transcription)).get)
          case Right(transcription) =>
            sendTo(session, TranscriptionMessage(transcription))
            playCommand(session, transcription, imageWanted = request.imageGeneration.getOrElse(true))
          case Left(error) =>
            sendTo(session, ErrorMessage("Failed to transcribe audio", Some(error)))
        }
    }

  /** A game's transcript for the browser; with `pictures`, each line that was shown with a picture carries the one its
    * location has now.
    */
  private def transcript(engine: GameEngine, gameId: String, pictures: Boolean): Seq[ConversationEntry] =
    engine.getTranscript.map { line =>
      line.pictured.filter(_ => pictures).flatMap(engine.pictureOf(_, Some(gameId))) match {
        case Some(MediaRef.Inline(image)) => ConversationEntry(line.role, line.content, image = Some(image))
        case Some(MediaRef.Stored(url)) => ConversationEntry(line.role, line.content, url = Some(url))
        case None => ConversationEntry(line.role, line.content)
      }
    }

  /** The media a completed turn announces, with the scene it is for, captured when the turn ends. */
  private final case class TurnMedia(
    messageIndex: Int,
    pictured: Option[GameScene],
    imageUpdate: Option[String],
    scored: Option[GameScene]) {
    def hasImage: Boolean = pictured.isDefined
    def hasMusic: Boolean = scored.isDefined
  }

  private def turnMedia(session: GameSession, imageWanted: Boolean): TurnMedia = {
    val engine = session.engine
    val moved = engine.locationChangedLastTurn
    // A place is pictured when the player arrives, and again only when the Dungeon Master says it looks different
    val update = engine.imageUpdateLastTurn
    val scene = engine.getCurrentScene
    TurnMedia(
      messageIndex = engine.getMessageCount,
      pictured = scene.filter(s =>
        (moved || update.isDefined) && session.features.image && imageWanted && engine.canPicture(s, update)),
      imageUpdate = update,
      scored = scene.filter(s => moved && session.features.music && engine.canPlayMusic(s))
    )
  }

  /** After a turn's response is sent: report validation issues, start its media, and save it. A refused command changed
    * nothing, so it is not saved.
    */
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
    if (session.features.autoSave && !response.refused) {
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

  private def startMedia(session: GameSession, turn: TurnMedia): Unit = {
    turn.pictured.foreach { scene =>
      deliverMedia(session, turn.messageIndex, MediaKind.Image, mediaEC) {
        session.engine.generateSceneImage(scene, Some(session.gameId), turn.imageUpdate).map {
          case MediaRef.Inline(image) => ImageReadyMessage(turn.messageIndex, image = Some(image))
          case MediaRef.Stored(url) => ImageReadyMessage(turn.messageIndex, url = Some(url))
        }
      }
    }
    turn.scored.foreach { scene =>
      deliverMedia(session, turn.messageIndex, MediaKind.Music, musicEC) {
        session.engine.generateBackgroundMusic(scene, Some(session.gameId)).map {
          case (MediaRef.Inline(music), mood) => MusicReadyMessage(turn.messageIndex, mood, music = Some(music))
          case (MediaRef.Stored(url), mood) => MusicReadyMessage(turn.messageIndex, mood, url = Some(url))
        }
      }
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
      theme = session.theme.map(_.name).getOrElse("default"),
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
    StepPersistence
      .saveNewGame(stepData, metadata)
      .left
      .foreach(error => logger.error(s"[${session.id}] Initial save failed: ${error.message}"))
  }

  private def saveStep(session: GameSession, stepData: StepData, durationMs: Long): Unit =
    StepPersistence.saveNextStep(stepData, durationMs) match {
      case Right(_) => logger.debug(s"[${session.id}] Saved step ${stepData.metadata.stepNumber}")
      case Left(error) => logger.warn(s"[${session.id}] Auto-save failed: ${error.message}")
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

  /** The answer to a command sent while [[SzorkConfig.MaxPendingTurns]] are already pending. */
  val TooManyCommands: ErrorMessage = ErrorMessage(
    "Too many commands",
    Some("The Dungeon Master is still answering your earlier commands. Wait for an answer, then send it again."))

  private def tooLongCommand: ErrorMessage =
    ErrorMessage("Command too long", Some(s"A command can be at most ${SzorkConfig.MaxCommandChars} characters."))

  /** The refusal of a message that is larger than [[SzorkConfig]]'s limits allow, if it is. */
  private[szork] def overLimit(message: ClientMessage): Option[ErrorMessage] = {
    import SzorkConfig._
    def tooLong(command: String) = Option.when(command.length > MaxCommandChars)(tooLongCommand)
    message match {
      case CommandRequest(command) => tooLong(command)
      case request: StreamCommandRequest => tooLong(request.command)
      // Base64 takes four characters for each three bytes
      case request: AudioCommandRequest if request.audio.length.toLong / 4 * 3 > MaxAudioBytes =>
        Some(
          ErrorMessage(
            "Recording too long",
            Some(s"A recording can be at most ${MaxAudioBytes / (1024 * 1024)} MB. Record a shorter command.")))
      case request: NewGameRequest if (request.theme ++ request.themeName).exists(_.length > MaxThemeChars) =>
        Some(ErrorMessage("Failed to start game", Some(s"The theme can be at most $MaxThemeChars characters.")))
      case request: NewGameRequest if request.adventureOutline.exists(ujson.write(_).length > MaxOutlineChars) =>
        Some(
          ErrorMessage(
            "Failed to start game",
            Some(s"The adventure outline is too large: at most ${MaxOutlineChars / 1024} KB of JSON.")))
      case _ => None
    }
  }

  /** The theme of a new game that is not of a library adventure: the request's prompt, listed by its name. */
  private[websocket] def themeOf(request: NewGameRequest): Option[GameTheme] =
    request.theme.map { prompt =>
      val name = request.themeName.getOrElse(prompt)
      GameTheme(id = name, name = name, prompt = prompt)
    }

  /** The recording formats the transcriber is told about, by MIME type (without parameters such as codecs). */
  private val RecordingExtensions: Map[String, String] = Map(
    "audio/webm" -> "webm",
    "audio/mp4" -> "mp4",
    "audio/m4a" -> "m4a",
    "audio/x-m4a" -> "m4a",
    "audio/ogg" -> "ogg",
    "audio/wav" -> "wav",
    "audio/x-wav" -> "wav",
    "audio/wave" -> "wav",
    "audio/mpeg" -> "mp3",
    "audio/mp3" -> "mp3"
  )

  /** The file name a recording of `mimeType` is uploaded to the transcriber as: its extension says the format (Safari
    * records MP4, Chrome and Firefox WebM or Ogg). An absent or unknown type is taken to be WebM.
    */
  private[szork] def recordingFileName(mimeType: Option[String]): String = {
    val essence = mimeType.map(_.takeWhile(_ != ';').trim.toLowerCase)
    s"recording.${essence.flatMap(RecordingExtensions.get).getOrElse("webm")}"
  }

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
    case m: ImageReadyMessage =>
      s"imageReady #${m.messageIndex} ${m.url.getOrElse(s"${m.image.fold(0)(_.length)} chars")}"
    case m: MusicReadyMessage => s"musicReady #${m.messageIndex} ${m.mood}"
    case m: GameEndedMessage => s"gameEnded #${m.messageIndex} ${m.outcome}"
    case m: ErrorMessage => s"error ${m.error}${m.details.fold("")(d => s": ${d.take(200)}")}"
    case m: ServerMessage => ProtocolCodec.typeOf(m)
    case m => m.getClass.getSimpleName
  }
}
