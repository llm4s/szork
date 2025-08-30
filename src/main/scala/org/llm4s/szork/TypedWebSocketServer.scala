package org.llm4s.szork

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import org.slf4j.LoggerFactory
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.collection.concurrent.TrieMap
import upickle.default._
import org.llm4s.szork.protocol._

/**
 * Type-safe WebSocket server using case classes for all communication.
 */
class TypedWebSocketServer(
  port: Int, 
  sessionManager: SessionManager
)(implicit ec: ExecutionContext) extends WebSocketServer(new InetSocketAddress(port)) {
  
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  
  // Generate a unique server instance ID on server start
  private val serverInstanceId = java.util.UUID.randomUUID().toString
  
  // Map WebSocket connections to session IDs
  private val connectionSessions = TrieMap[WebSocket, String]()
  
  override def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit = {
    logger.info(s"WebSocket connection opened from: ${conn.getRemoteSocketAddress}")
    logger.info(s"Server instance ID: $serverInstanceId")
    // Send welcome message with server instance ID
    sendMessage(conn, ConnectedMessage(
      message = "Connected to SZork WebSocket server",
      version = "1.0",
      serverInstanceId = serverInstanceId
    ))
  }
  
  override def onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean): Unit = {
    logger.info(s"WebSocket connection closed: $reason")
    connectionSessions.remove(conn)
  }
  
  override def onMessage(conn: WebSocket, message: String): Unit = {
    try {
      // Parse the message and convert "type" field to the expected format
      val json = ujson.read(message)
      val clientMessage = parseClientMessage(json)
      val sessionId = connectionSessions.getOrElse(conn, "no-session")
      
      // Log received message with key details
      clientMessage match {
        case msg: NewGameRequest => 
          logger.info(s"[WS-IN] Session: $sessionId | Type: NewGameRequest | Theme: ${msg.theme.getOrElse("default")} | ArtStyle: ${msg.artStyle.getOrElse("default")} | ImageGen: ${msg.imageGeneration}")
          handleNewGame(conn, msg)
        case msg: LoadGameRequest => 
          logger.info(s"[WS-IN] Session: $sessionId | Type: LoadGameRequest | GameId: ${msg.gameId}")
          handleLoadGame(conn, msg)
        case msg: CommandRequest => 
          logger.info(s"[WS-IN] Session: $sessionId | Type: CommandRequest | Command: '${msg.command.take(50)}${if(msg.command.length > 50) "..." else ""}'")
          handleCommand(conn, msg)
        case msg: StreamCommandRequest => 
          logger.info(s"[WS-IN] Session: $sessionId | Type: StreamCommandRequest | Command: '${msg.command.take(50)}${if(msg.command.length > 50) "..." else ""}' | ImageGen: ${msg.imageGeneration.getOrElse(false)}")
          handleStreamCommand(conn, msg)
        case msg: AudioCommandRequest => 
          logger.info(s"[WS-IN] Session: $sessionId | Type: AudioCommandRequest | AudioLength: ${msg.audio.length} bytes")
          handleAudioCommand(conn, msg)
        case msg: GetImageRequest => 
          logger.info(s"[WS-IN] Session: $sessionId | Type: GetImageRequest | MessageIndex: ${msg.messageIndex}")
          handleGetImage(conn, msg)
        case msg: GetMusicRequest => 
          logger.info(s"[WS-IN] Session: $sessionId | Type: GetMusicRequest | MessageIndex: ${msg.messageIndex}")
          handleGetMusic(conn, msg)
        case _: ListGamesRequest => 
          logger.info(s"[WS-IN] Session: $sessionId | Type: ListGamesRequest")
          handleListGames(conn)
        case msg: PingMessage => 
          logger.debug(s"[WS-IN] Session: $sessionId | Type: PingMessage | Timestamp: ${msg.timestamp}")
          val pongMessage = PongMessage(msg.timestamp)
          sendMessage(conn, pongMessage)
      }
    } catch {
      case e: Exception =>
        logger.error(s"[WS-ERROR] Error processing WebSocket message: $message", e)
        sendMessage(conn, ErrorMessage(
          error = "Failed to process message",
          details = Some(e.getMessage)
        ))
    }
  }
  
  override def onError(conn: WebSocket, ex: Exception): Unit = {
    logger.error(s"WebSocket error", ex)
  }
  
  override def onStart(): Unit = {
    logger.info(s"Type-safe WebSocket server started on port $port")
  }
  
  // Helper method to parse client messages with "type" field
  private def parseClientMessage(json: ujson.Value): ClientMessage = {
    val obj = json.obj.clone() // Clone to avoid modification issues
    val msgType = obj.get("type").map(_.str).getOrElse(
      throw new Exception("Missing 'type' field in client message")
    )
    
    // Convert "type" to "$type" for uPickle and set the class name
    obj.remove("type")
    val className = msgType match {
      case "newGame" => "NewGameRequest"
      case "loadGame" => "LoadGameRequest"
      case "command" => "CommandRequest"
      case "streamCommand" => "StreamCommandRequest"
      case "audioCommand" => "AudioCommandRequest"
      case "getImage" => "GetImageRequest"
      case "getMusic" => "GetMusicRequest"
      case "listGames" => "ListGamesRequest"
      case "ping" => "PingMessage"
      case other => throw new Exception(s"Unknown client message type: $other")
    }
    obj("$type") = className
    
    // Now uPickle can deserialize it properly
    read[ClientMessage](ujson.Value(obj))
  }
  
  // Helper method to send typed messages with "type" field
  private def sendMessage(conn: WebSocket, message: ServerMessage): Unit = {
    val sessionId = connectionSessions.getOrElse(conn, "no-session")
    val baseJson = write[ServerMessage](message)
    val obj = ujson.read(baseJson).obj
    
    // Extract the message type from the class name
    val msgType = message.getClass.getSimpleName.replace("Message", "")
    val typeValue = msgType.take(1).toLowerCase + msgType.drop(1)
    
    // Create the properly formatted message
    val result = ujson.Obj()
    result("type") = typeValue
    
    // Wrap non-type fields in "data" object
    val dataObj = ujson.Obj()
    obj.foreach { case (k, v) =>
      if (!k.startsWith("$")) { // Skip internal fields like $type
        dataObj(k) = v
      }
    }
    
    if (dataObj.value.nonEmpty) {
      result("data") = dataObj
    }
    
    // Log outgoing message with key details
    val logMessage = message match {
      case msg: ConnectedMessage =>
        s"[WS-OUT] Session: $sessionId | Type: ConnectedMessage | Version: ${msg.version} | ServerInstance: ${msg.serverInstanceId}"
      case msg: GameStartedMessage =>
        s"[WS-OUT] Session: $sessionId | Type: GameStartedMessage | GameId: ${msg.gameId} | MsgIdx: ${msg.messageIndex} | TextLen: ${msg.text.length} | HasAudio: ${msg.audio.isDefined} | HasImage: ${msg.hasImage} | HasMusic: ${msg.hasMusic}"
      case msg: GameLoadedMessage =>
        s"[WS-OUT] Session: $sessionId | Type: GameLoadedMessage | GameId: ${msg.gameId} | Messages: ${msg.conversation.length}"
      case msg: CommandResponseMessage =>
        s"[WS-OUT] Session: $sessionId | Type: CommandResponseMessage | MsgIdx: ${msg.messageIndex} | TextLen: ${msg.text.length} | HasImage: ${msg.hasImage}"
      case msg: TextChunkMessage =>
        s"[WS-OUT] Session: $sessionId | Type: TextChunkMessage | ChunkNum: ${msg.chunkNumber} | TextLen: ${msg.text.length}"
      case msg: StreamCompleteMessage =>
        s"[WS-OUT] Session: $sessionId | Type: StreamCompleteMessage | MsgIdx: ${msg.messageIndex} | Chunks: ${msg.totalChunks} | Duration: ${msg.duration}ms | HasImage: ${msg.hasImage}"
      case msg: TranscriptionMessage =>
        s"[WS-OUT] Session: $sessionId | Type: TranscriptionMessage | TextLen: ${msg.text.length}"
      case msg: ImageReadyMessage =>
        s"[WS-OUT] Session: $sessionId | Type: ImageReadyMessage | MsgIdx: ${msg.messageIndex} | ImageLen: ${msg.image.length} bytes"
      case msg: MusicReadyMessage =>
        s"[WS-OUT] Session: $sessionId | Type: MusicReadyMessage | MsgIdx: ${msg.messageIndex} | Mood: ${msg.mood} | MusicLen: ${msg.music.length} bytes"
      case msg: ImageDataMessage =>
        s"[WS-OUT] Session: $sessionId | Type: ImageDataMessage | MsgIdx: ${msg.messageIndex} | Status: ${msg.status}"
      case msg: MusicDataMessage =>
        s"[WS-OUT] Session: $sessionId | Type: MusicDataMessage | MsgIdx: ${msg.messageIndex} | Status: ${msg.status}"
      case msg: GamesListMessage =>
        s"[WS-OUT] Session: $sessionId | Type: GamesListMessage | Games: ${msg.games.length}"
      case msg: ErrorMessage =>
        s"[WS-OUT] Session: $sessionId | Type: ErrorMessage | Error: ${msg.error}"
      case msg: PongMessage =>
        s"[WS-DEBUG] Session: $sessionId | Type: PongMessage | Timestamp: ${msg.timestamp}"
    }
    
    // Use debug level for ping/pong, info for everything else
    if (message.isInstanceOf[PongMessage]) {
      logger.debug(logMessage)
    } else {
      logger.info(logMessage)
    }
    
    val finalMessage = ujson.write(result)
    conn.send(finalMessage)
  }
  
  // Handle new game creation
  private def handleNewGame(conn: WebSocket, request: NewGameRequest): Unit = {
    logger.info(s"Creating new game with theme: ${request.theme.getOrElse("default")}, artStyle: ${request.artStyle.getOrElse("default")}")
    
    val sessionId = IdGenerator.sessionId()
    val gameId = IdGenerator.gameId()
    logger.info(s"Generated IDs - Session: $sessionId, Game: $gameId")
    
    val themeObj = request.theme.map(t => GameTheme(t, t, t))
    val artStyleObj = request.artStyle.map(a => ArtStyle(a, a))
    
    // Parse adventure outline from JSON if provided
    val adventureOutline = request.adventureOutline.flatMap { json =>
      try {
        Some(parseAdventureOutlineFromJson(json))
      } catch {
        case e: Exception =>
          logger.error("Failed to parse adventure outline from request", e)
          None
      }
    }
    
    if (adventureOutline.isDefined) {
      logger.info(s"Using adventure outline: ${adventureOutline.get.title}")
    }
    
    // Create GameEngine with proper parameters
    val engine = new GameEngine(
      sessionId = sessionId,
      theme = request.theme,
      artStyle = request.artStyle,
      adventureOutline = adventureOutline
    )
    
    val session = GameSession(
      id = sessionId,
      gameId = gameId,
      engine = engine,
      theme = themeObj,
      artStyle = artStyleObj,
      imageGenerationEnabled = request.imageGeneration
    )
    
    sessionManager.createSession(session)
    connectionSessions(conn) = sessionId
    logger.info(s"Session created and registered for connection")
    
    // Initialize the game first
    logger.info(s"Initializing game engine...")
    engine.initialize() match {
      case Right(initialMessage) =>
        logger.info(s"Game initialized successfully. Message length: ${initialMessage.length}")
        val sceneData = engine.getCurrentScene.map(convertScene)
        
        // Generate audio for the initial game text
        val audioBase64 = if (initialMessage.nonEmpty) {
          val audioStartTime = System.currentTimeMillis()
          logger.info(s"Generating audio for initial game text (${initialMessage.length} chars)")
          val tts = TextToSpeech()
          tts.synthesizeToBase64(initialMessage, TextToSpeech.VOICE_NOVA) match {
            case Right(audio) => 
              val audioTime = System.currentTimeMillis() - audioStartTime
              logger.info(s"Audio generation completed in ${audioTime}ms (${audio.length} bytes base64)")
              Some(audio)
            case Left(error) => 
              logger.error(s"Failed to generate audio: $error")
              None
          }
        } else {
          None
        }
        
        val message = GameStartedMessage(
          sessionId = sessionId,
          gameId = gameId,
          text = initialMessage,
          messageIndex = engine.getMessageCount,
          scene = sceneData,
          audio = audioBase64,  // Include the generated audio
          hasImage = request.imageGeneration && engine.shouldGenerateSceneImage(initialMessage),
          hasMusic = engine.shouldGenerateBackgroundMusic(initialMessage)
        )
        
        logger.info(s"Sending GameStartedMessage - hasAudio: ${message.audio.isDefined}, hasImage: ${message.hasImage}, hasMusic: ${message.hasMusic}")
        sendMessage(conn, message)
        
        // Generate image/music if needed
        if (message.hasImage) {
          generateImageAsync(session, initialMessage, message.messageIndex, conn)
        }
        if (message.hasMusic) {
          generateMusicAsync(session, initialMessage, message.messageIndex, conn)
        }
        
      case Left(error) =>
        logger.error(s"Failed to initialize game: $error")
        sendMessage(conn, ErrorMessage(s"Failed to start game: $error"))
    }
  }
  
  // Handle loading existing game
  private def handleLoadGame(conn: WebSocket, request: LoadGameRequest): Unit = {
    logger.info(s"Loading game: ${request.gameId}")
    
    GamePersistence.loadGame(request.gameId) match {
      case Right(gameState) =>
        val sessionId = IdGenerator.sessionId()
        val engine = new GameEngine(request.gameId)
        
        // Restore game state
        engine.restoreGameState(gameState)
        
        val session = GameSession(
          id = sessionId,
          gameId = request.gameId,
          engine = engine,
          theme = gameState.theme,
          artStyle = gameState.artStyle
        )
        
        sessionManager.createSession(session)
        connectionSessions(conn) = sessionId
        
        val message = GameLoadedMessage(
          sessionId = sessionId,
          gameId = request.gameId,
          conversation = gameState.conversationHistory.map(e => 
            ConversationEntry(role = e.role, content = e.content)
          ),
          currentLocation = gameState.currentScene.map(_.locationId),
          currentScene = gameState.currentScene.map(convertScene)
        )
        
        sendMessage(conn, message)
        
      case Left(error) =>
        sendMessage(conn, ErrorMessage(s"Failed to load game: $error"))
    }
  }
  
  // Handle regular command (non-streaming)
  private def handleCommand(conn: WebSocket, request: CommandRequest): Unit = {
    val sessionId = connectionSessions.get(conn)
    sessionId match {
      case Some(sid) =>
        sessionManager.getSession(sid) match {
          case Some(session) =>
            logger.info(s"Processing command for session $sid: '${request.command}'")
            
            val response = session.engine.processCommand(request.command)
            response match {
              case Right(gameResponse) =>
                val message = CommandResponseMessage(
                  text = gameResponse.text,
                  messageIndex = session.engine.getMessageCount,
                  command = request.command,
                  scene = gameResponse.scene.map(convertScene),
                  audio = gameResponse.audioBase64,
                  hasImage = session.imageGenerationEnabled && session.engine.shouldGenerateSceneImage(gameResponse.text),
                  hasMusic = session.engine.shouldGenerateBackgroundMusic(gameResponse.text)
                )
                
                sendMessage(conn, message)
                
                // Generate image/music if needed
                if (message.hasImage) {
                  generateImageAsync(session, gameResponse.text, message.messageIndex, conn)
                }
                if (message.hasMusic) {
                  generateMusicAsync(session, gameResponse.text, message.messageIndex, conn)
                }
                
                // Auto-save
                if (session.autoSaveEnabled) {
                  saveGameAsync(session)
                }
                
              case Left(error) =>
                sendMessage(conn, ErrorMessage(s"Command failed: $error"))
            }
            
          case None =>
            sendMessage(conn, ErrorMessage("Session not found"))
        }
      case None =>
        sendMessage(conn, ErrorMessage("No active session"))
    }
  }
  
  // Handle streaming command
  private def handleStreamCommand(conn: WebSocket, request: StreamCommandRequest): Unit = {
    val sessionId = connectionSessions.get(conn)
    sessionId match {
      case Some(sid) =>
        sessionManager.getSession(sid) match {
          case Some(session) =>
            val imageGeneration = request.imageGeneration.getOrElse(session.imageGenerationEnabled)
            
            logger.info(s"Processing streaming command for session $sid: '${request.command}'")
            
            Future {
              var chunkCount = 0
              val startTime = System.currentTimeMillis()
              
              session.engine.processCommandStreaming(
                request.command,
                onTextChunk = chunk => {
                  chunkCount += 1
                  sendMessage(conn, TextChunkMessage(
                    text = chunk,
                    chunkNumber = chunkCount
                  ))
                }
              ) match {
                case Right(response) =>
                  val duration = System.currentTimeMillis() - startTime
                  
                  val message = StreamCompleteMessage(
                    messageIndex = session.engine.getMessageCount,
                    totalChunks = chunkCount,
                    duration = duration,
                    scene = response.scene.map(convertScene),
                    audio = response.audioBase64,
                    hasImage = imageGeneration && session.engine.shouldGenerateSceneImage(response.text),
                    hasMusic = session.engine.shouldGenerateBackgroundMusic(response.text)
                  )
                  
                  sendMessage(conn, message)
                  
                  logger.info(s"Streaming completed: $chunkCount chunks in ${duration}ms")
                  
                  // Generate image/music if needed
                  if (message.hasImage) {
                    generateImageAsync(session, response.text, message.messageIndex, conn)
                  }
                  if (message.hasMusic) {
                    generateMusicAsync(session, response.text, message.messageIndex, conn)
                  }
                  
                  // Auto-save
                  if (session.autoSaveEnabled) {
                    saveGameAsync(session)
                  }
                  
                case Left(error) =>
                  sendMessage(conn, ErrorMessage(s"Streaming failed: $error"))
              }
            }
            
          case None =>
            sendMessage(conn, ErrorMessage("Session not found"))
        }
      case None =>
        sendMessage(conn, ErrorMessage("No active session"))
    }
  }
  
  // Handle audio command
  private def handleAudioCommand(conn: WebSocket, request: AudioCommandRequest): Unit = {
    val sessionId = connectionSessions.get(conn)
    sessionId match {
      case Some(sid) =>
        sessionManager.getSession(sid) match {
          case Some(session) =>
            logger.info(s"Processing audio command for session $sid")
            
            // Decode base64 audio and transcribe
            val audioBytes = java.util.Base64.getDecoder.decode(request.audio)
            val speechToText = SpeechToText()
            speechToText.transcribeBytes(audioBytes) match {
              case Right(transcription) =>
                sendMessage(conn, TranscriptionMessage(transcription))
                
                // Process the transcribed command
                val response = session.engine.processCommand(transcription)
                response match {
                  case Right(gameResponse) =>
                    val message = CommandResponseMessage(
                      text = gameResponse.text,
                      messageIndex = session.engine.getMessageCount,
                      command = transcription,
                      scene = gameResponse.scene.map(convertScene),
                      audio = gameResponse.audioBase64,
                      hasImage = session.imageGenerationEnabled && session.engine.shouldGenerateSceneImage(gameResponse.text),
                      hasMusic = session.engine.shouldGenerateBackgroundMusic(gameResponse.text)
                    )
                    
                    sendMessage(conn, message)
                    
                    // Generate image/music if needed
                    if (message.hasImage) {
                      generateImageAsync(session, gameResponse.text, message.messageIndex, conn)
                    }
                    if (message.hasMusic) {
                      generateMusicAsync(session, gameResponse.text, message.messageIndex, conn)
                    }
                    
                  case Left(error) =>
                    sendMessage(conn, ErrorMessage(s"Command failed: $error"))
                }
                
              case Left(error) =>
                sendMessage(conn, ErrorMessage(s"Failed to transcribe audio: $error"))
            }
            
          case None =>
            sendMessage(conn, ErrorMessage("Session not found"))
        }
      case None =>
        sendMessage(conn, ErrorMessage("No active session"))
    }
  }
  
  // Handle get image request
  private def handleGetImage(conn: WebSocket, request: GetImageRequest): Unit = {
    val sessionId = connectionSessions.get(conn)
    sessionId match {
      case Some(sid) =>
        sessionManager.getSession(sid) match {
          case Some(session) =>
            session.pendingImages.get(request.messageIndex) match {
              case Some(Some(image)) =>
                sendMessage(conn, ImageDataMessage(
                  messageIndex = request.messageIndex,
                  image = image,
                  status = "ready"
                ))
              case Some(None) =>
                sendMessage(conn, ImageDataMessage(
                  messageIndex = request.messageIndex,
                  image = "",
                  status = "pending"
                ))
              case None =>
                sendMessage(conn, ErrorMessage(s"Image not found for message ${request.messageIndex}"))
            }
          case None =>
            sendMessage(conn, ErrorMessage("Session not found"))
        }
      case None =>
        sendMessage(conn, ErrorMessage("No active session"))
    }
  }
  
  // Handle get music request
  private def handleGetMusic(conn: WebSocket, request: GetMusicRequest): Unit = {
    val sessionId = connectionSessions.get(conn)
    sessionId match {
      case Some(sid) =>
        sessionManager.getSession(sid) match {
          case Some(session) =>
            session.pendingMusic.get(request.messageIndex) match {
              case Some(Some((music, mood))) =>
                sendMessage(conn, MusicDataMessage(
                  messageIndex = request.messageIndex,
                  music = music,
                  mood = mood,
                  status = "ready"
                ))
              case Some(None) =>
                sendMessage(conn, MusicDataMessage(
                  messageIndex = request.messageIndex,
                  music = "",
                  mood = "",
                  status = "pending"
                ))
              case None =>
                sendMessage(conn, ErrorMessage(s"Music not found for message ${request.messageIndex}"))
            }
          case None =>
            sendMessage(conn, ErrorMessage("Session not found"))
        }
      case None =>
        sendMessage(conn, ErrorMessage("No active session"))
    }
  }
  
  // Handle list games request
  private def handleListGames(conn: WebSocket): Unit = {
    val games = GamePersistence.listGames().map(g => GameInfo(
      gameId = g.gameId,
      theme = g.theme,
      timestamp = g.lastPlayed,
      locationName = g.adventureTitle  // Use adventure title as location name
    ))
    
    sendMessage(conn, GamesListMessage(games))
  }
  
  // Generate image asynchronously
  private def generateImageAsync(session: GameSession, text: String, messageIndex: Int, conn: WebSocket): Unit = {
    Future {
      logger.debug(s"Starting image generation for message $messageIndex")
      val imageOpt = session.engine.generateSceneImage(text, Some(session.gameId))
      session.pendingImages(messageIndex) = imageOpt
      
      imageOpt match {
        case Some(image) =>
          sendMessage(conn, ImageReadyMessage(
            messageIndex = messageIndex,
            image = image
          ))
        case None =>
          logger.warn(s"No image generated for message $messageIndex")
      }
    }
  }
  
  // Generate music asynchronously
  private def generateMusicAsync(session: GameSession, text: String, messageIndex: Int, conn: WebSocket): Unit = {
    Future {
      logger.debug(s"Starting music generation for message $messageIndex")
      val musicOpt = session.engine.generateBackgroundMusic(text, Some(session.gameId))
      session.pendingMusic(messageIndex) = musicOpt
      
      musicOpt match {
        case Some((music, mood)) =>
          sendMessage(conn, MusicReadyMessage(
            messageIndex = messageIndex,
            music = music,
            mood = mood
          ))
        case None =>
          logger.warn(s"No music generated for message $messageIndex")
      }
    }
  }
  
  // Save game asynchronously
  private def saveGameAsync(session: GameSession): Unit = {
    Future {
      val gameState = session.engine.getGameState(session.gameId, session.theme, session.artStyle)
      GamePersistence.saveGame(gameState) match {
        case Right(_) =>
          logger.debug(s"Auto-saved game ${session.gameId}")
        case Left(error) =>
          logger.warn(s"Auto-save failed for game ${session.gameId}: $error")
      }
    }
  }
  
  // Convert internal GameScene to protocol SceneData
  private def convertScene(scene: GameScene): SceneData = {
    SceneData(
      locationName = scene.locationName,
      exits = scene.exits.map(exit => ExitData(
        direction = exit.direction,
        description = exit.description.getOrElse("")
      )),
      items = scene.items,
      npcs = scene.npcs
    )
  }
  
  // Helper function to parse AdventureOutline from JSON
  private def parseAdventureOutlineFromJson(json: ujson.Value): AdventureOutline = {
    AdventureOutline(
      title = json("title").str,
      tagline = json.obj.get("tagline").flatMap {
        case ujson.Null => None
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
        case ujson.Null => None
        case s => Some(s.str)
      }
    )
  }
}