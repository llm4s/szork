package org.llm4s.szork.websocket.protocol

import upickle.default._

// Base trait for all WebSocket messages
sealed trait WebSocketMessage

// Client to Server messages
sealed trait ClientMessage extends WebSocketMessage
object ClientMessage {
  implicit val rw: ReadWriter[ClientMessage] = ReadWriter.merge(
    NewGameRequest.rw,
    LoadGameRequest.rw,
    ResumeSessionRequest.rw,
    CommandRequest.rw,
    StreamCommandRequest.rw,
    AudioCommandRequest.rw,
    ListGamesRequest.rw,
    PingMessage.rw
  )
}

// Server to Client messages.
//
// upickle's macroRW omits a field whose value equals its default, so fields the client relies on (flags, lists)
// have no defaults here: they are always sent. Optional fields default to None and are omitted when absent.
sealed trait ServerMessage extends WebSocketMessage
object ServerMessage {
  implicit val rw: ReadWriter[ServerMessage] = ReadWriter.merge(
    ConnectedMessage.rw,
    GameStartedMessage.rw,
    GameLoadedMessage.rw,
    SessionResumedMessage.rw,
    SessionTakenOverMessage.rw,
    CommandResponseMessage.rw,
    TextChunkMessage.rw,
    StreamCompleteMessage.rw,
    TranscriptionMessage.rw,
    ImageReadyMessage.rw,
    MusicReadyMessage.rw,
    MediaFailedMessage.rw,
    GamesListMessage.rw,
    ErrorMessage.rw,
    PongMessage.rw
  )
}

// ============= Client Messages =============

case class NewGameRequest(
  theme: Option[String] = None,
  artStyle: Option[String] = None,
  imageGeneration: Boolean = true,
  adventureOutline: Option[ujson.Value] = None, // Adventure outline as JSON
  tts: Option[Boolean] = None,
  stt: Option[Boolean] = None,
  music: Option[Boolean] = None,
  image: Option[Boolean] = None
) extends ClientMessage
object NewGameRequest {
  implicit val rw: ReadWriter[NewGameRequest] = macroRW
}

case class LoadGameRequest(
  gameId: String,
  tts: Option[Boolean] = None,
  stt: Option[Boolean] = None,
  music: Option[Boolean] = None,
  image: Option[Boolean] = None
) extends ClientMessage
object LoadGameRequest {
  implicit val rw: ReadWriter[LoadGameRequest] = macroRW
}

/** Re-attaches a reconnected client to its session. Answered with [[SessionResumedMessage]], or an [[ErrorMessage]]
  * with details `SESSION_EXPIRED`, in which case the client should load its game again.
  */
case class ResumeSessionRequest(
  sessionId: String
) extends ClientMessage
object ResumeSessionRequest {
  implicit val rw: ReadWriter[ResumeSessionRequest] = macroRW
}

case class CommandRequest(
  command: String
) extends ClientMessage
object CommandRequest {
  implicit val rw: ReadWriter[CommandRequest] = macroRW
}

case class StreamCommandRequest(
  command: String,
  imageGeneration: Option[Boolean] = None
) extends ClientMessage
object StreamCommandRequest {
  implicit val rw: ReadWriter[StreamCommandRequest] = macroRW
}

/** A spoken command. `imageGeneration` false asks for no scene image for its response, as for typed commands. */
case class AudioCommandRequest(
  audio: String, // Base64 encoded audio
  imageGeneration: Option[Boolean] = None
) extends ClientMessage
object AudioCommandRequest {
  implicit val rw: ReadWriter[AudioCommandRequest] = macroRW
}

case class ListGamesRequest() extends ClientMessage
object ListGamesRequest {
  implicit val rw: ReadWriter[ListGamesRequest] = macroRW
}

case class PingMessage(
  timestamp: Long = System.currentTimeMillis()
) extends ClientMessage
object PingMessage {
  implicit val rw: ReadWriter[PingMessage] = macroRW
}

// ============= Server Messages =============

case class ConnectedMessage(
  message: String,
  version: String,
  serverInstanceId: String
) extends ServerMessage
object ConnectedMessage {
  implicit val rw: ReadWriter[ConnectedMessage] = macroRW
}

case class GameStartedMessage(
  sessionId: String,
  gameId: String,
  text: String,
  messageIndex: Int,
  scene: Option[SceneData] = None,
  audio: Option[String] = None,
  hasImage: Boolean,
  hasMusic: Boolean,
  ttsEnabled: Boolean,
  sttEnabled: Boolean,
  imageEnabled: Boolean,
  musicEnabled: Boolean
) extends ServerMessage
object GameStartedMessage {
  implicit val rw: ReadWriter[GameStartedMessage] = macroRW
}

/** A loaded game. `messageIndex` is the session's message index (as in [[SessionResumedMessage]]), so a client can tell
  * on resuming whether it missed a response. The feature flags are the ones the session now uses: loading a game that
  * is already open applies the features this request asked for to its session.
  */
case class GameLoadedMessage(
  sessionId: String,
  gameId: String,
  messageIndex: Int,
  conversation: Seq[ConversationEntry],
  currentLocation: Option[String] = None,
  currentScene: Option[SceneData] = None,
  ttsEnabled: Boolean,
  sttEnabled: Boolean,
  imageEnabled: Boolean,
  musicEnabled: Boolean
) extends ServerMessage
object GameLoadedMessage {
  implicit val rw: ReadWriter[GameLoadedMessage] = macroRW
}

case class SessionResumedMessage(
  sessionId: String,
  gameId: String,
  messageIndex: Int
) extends ServerMessage
object SessionResumedMessage {
  implicit val rw: ReadWriter[SessionResumedMessage] = macroRW
}

/** Sent to a connection whose session was taken by another connection loading the same game. The client should stop
  * resuming `sessionId` and offer to load the game again (which takes the session back).
  */
case class SessionTakenOverMessage(
  sessionId: String,
  gameId: String
) extends ServerMessage
object SessionTakenOverMessage {
  implicit val rw: ReadWriter[SessionTakenOverMessage] = macroRW
}

case class CommandResponseMessage(
  text: String,
  messageIndex: Int,
  command: String,
  scene: Option[SceneData] = None,
  audio: Option[String] = None,
  hasImage: Boolean,
  hasMusic: Boolean
) extends ServerMessage
object CommandResponseMessage {
  implicit val rw: ReadWriter[CommandResponseMessage] = macroRW
}

case class TextChunkMessage(
  text: String,
  chunkNumber: Int
) extends ServerMessage
object TextChunkMessage {
  implicit val rw: ReadWriter[TextChunkMessage] = macroRW
}

/** Ends a streamed response. `text` is the final narration, which replaces the streamed text (the model may have been
  * asked to correct its answer after narration had started streaming).
  */
case class StreamCompleteMessage(
  text: String,
  messageIndex: Int,
  totalChunks: Int,
  duration: Long,
  scene: Option[SceneData] = None,
  audio: Option[String] = None,
  hasImage: Boolean,
  hasMusic: Boolean
) extends ServerMessage
object StreamCompleteMessage {
  implicit val rw: ReadWriter[StreamCompleteMessage] = macroRW
}

case class TranscriptionMessage(
  text: String
) extends ServerMessage
object TranscriptionMessage {
  implicit val rw: ReadWriter[TranscriptionMessage] = macroRW
}

case class ImageReadyMessage(
  messageIndex: Int,
  image: String // Base64 encoded image
) extends ServerMessage
object ImageReadyMessage {
  implicit val rw: ReadWriter[ImageReadyMessage] = macroRW
}

case class MusicReadyMessage(
  messageIndex: Int,
  music: String, // Base64 encoded audio
  mood: String
) extends ServerMessage
object MusicReadyMessage {
  implicit val rw: ReadWriter[MusicReadyMessage] = macroRW
}

/** The kind of media a [[MediaFailedMessage]] is about; sent as "image" or "music". */
enum MediaKind(val wireName: String) {
  case Image extends MediaKind("image")
  case Music extends MediaKind("music")
}
object MediaKind {
  implicit val rw: ReadWriter[MediaKind] = readwriter[String].bimap[MediaKind](
    _.wireName,
    name =>
      MediaKind.values
        .find(_.wireName == name)
        .getOrElse(throw new IllegalArgumentException(s"Unknown media kind: $name"))
  )
}

/** Media announced for a message (hasImage / hasMusic) could not be produced. */
case class MediaFailedMessage(
  messageIndex: Int,
  kind: MediaKind
) extends ServerMessage
object MediaFailedMessage {
  implicit val rw: ReadWriter[MediaFailedMessage] = macroRW
}

case class GamesListMessage(
  games: Seq[GameInfo]
) extends ServerMessage
object GamesListMessage {
  implicit val rw: ReadWriter[GamesListMessage] = macroRW
}

case class ErrorMessage(
  error: String,
  details: Option[String] = None
) extends ServerMessage
object ErrorMessage {
  implicit val rw: ReadWriter[ErrorMessage] = macroRW

  /** `details` of the error answering a [[ResumeSessionRequest]] for a session that no longer exists. */
  val SessionExpired: String = "SESSION_EXPIRED"
}

case class PongMessage(
  timestamp: Long
) extends ServerMessage
object PongMessage {
  implicit val rw: ReadWriter[PongMessage] = macroRW
}

// ============= Data Types =============

case class SceneData(
  locationName: String,
  exits: Seq[ExitData],
  items: Seq[String],
  npcs: Seq[String]
)
object SceneData {
  implicit val rw: ReadWriter[SceneData] = macroRW
}

case class ExitData(
  direction: String,
  description: String
)
object ExitData {
  implicit val rw: ReadWriter[ExitData] = macroRW
}

case class ConversationEntry(
  role: String,
  content: String
)
object ConversationEntry {
  implicit val rw: ReadWriter[ConversationEntry] = macroRW
}

case class GameInfo(
  gameId: String,
  theme: String,
  timestamp: Long,
  locationName: String
)
object GameInfo {
  implicit val rw: ReadWriter[GameInfo] = macroRW
}

/** The wire format: every message is `{"type": "<name>", "data": {...fields}}`, where the name is the class name
  * without its `Request`/`Message` suffix, starting lower case (e.g. `StreamCommandRequest` is `streamCommand`).
  */
object ProtocolCodec {
  private def wireName(className: String): String = {
    val base = className.stripSuffix("Request").stripSuffix("Message")
    base.take(1).toLowerCase + base.drop(1)
  }

  private val clientClassByType: Map[String, String] = Seq(
    "NewGameRequest",
    "LoadGameRequest",
    "ResumeSessionRequest",
    "CommandRequest",
    "StreamCommandRequest",
    "AudioCommandRequest",
    "ListGamesRequest",
    "PingMessage"
  ).map(c => wireName(c) -> c).toMap

  /** The wire type name of a server message. */
  def typeOf(message: ServerMessage): String = wireName(message.getClass.getSimpleName)

  /** Decodes a client message; fields may be at the top level or inside `data`. */
  def decode(text: String): Either[String, ClientMessage] =
    try {
      val json = ujson.read(text)
      val obj = json.obj
      val msgType = obj.get("type").map(_.str).toRight("Missing 'type' field in client message")
      msgType.flatMap { t =>
        clientClassByType.get(t).toRight(s"Unknown client message type: $t").map { className =>
          val source = obj.get("data") match {
            case Some(data: ujson.Obj) => data.value
            case _ => obj
          }
          val fields = ujson.Obj()
          source.foreach { case (k, v) => if (k != "type") fields(k) = v }
          fields("$type") = className
          read[ClientMessage](fields)
        }
      }
    } catch {
      case e: Exception => Left(s"Invalid client message: ${e.getMessage}")
    }

  /** Encodes a server message as `{"type": ..., "data": {...}}`. */
  def encode(message: ServerMessage): String = {
    val fields = ujson.Obj()
    writeJs[ServerMessage](message).obj.foreach { case (k, v) => if (!k.startsWith("$")) fields(k) = v }
    val result = ujson.Obj("type" -> typeOf(message))
    if (fields.value.nonEmpty) result("data") = fields
    ujson.write(result)
  }
}
