package org.llm4s.szork.protocol

import upickle.default._
import ujson.Value

// Base trait for all WebSocket messages
sealed trait WebSocketMessage

// Client to Server messages
sealed trait ClientMessage extends WebSocketMessage
object ClientMessage {
  implicit val rw: ReadWriter[ClientMessage] = ReadWriter.merge(
    NewGameRequest.rw,
    LoadGameRequest.rw,
    CommandRequest.rw,
    StreamCommandRequest.rw,
    AudioCommandRequest.rw,
    GetImageRequest.rw,
    GetMusicRequest.rw,
    ListGamesRequest.rw,
    PingMessage.rw
  )
}

// Server to Client messages
sealed trait ServerMessage extends WebSocketMessage
object ServerMessage {
  implicit val rw: ReadWriter[ServerMessage] = ReadWriter.merge(
    ConnectedMessage.rw,
    GameStartedMessage.rw,
    GameLoadedMessage.rw,
    CommandResponseMessage.rw,
    TextChunkMessage.rw,
    StreamCompleteMessage.rw,
    TranscriptionMessage.rw,
    ImageReadyMessage.rw,
    MusicReadyMessage.rw,
    ImageDataMessage.rw,
    MusicDataMessage.rw,
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
  adventureOutline: Option[ujson.Value] = None  // Adventure outline as JSON
) extends ClientMessage
object NewGameRequest {
  implicit val rw: ReadWriter[NewGameRequest] = macroRW
}

case class LoadGameRequest(
  gameId: String
) extends ClientMessage
object LoadGameRequest {
  implicit val rw: ReadWriter[LoadGameRequest] = macroRW
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

case class AudioCommandRequest(
  audio: String // Base64 encoded audio
) extends ClientMessage
object AudioCommandRequest {
  implicit val rw: ReadWriter[AudioCommandRequest] = macroRW
}

case class GetImageRequest(
  messageIndex: Int
) extends ClientMessage
object GetImageRequest {
  implicit val rw: ReadWriter[GetImageRequest] = macroRW
}

case class GetMusicRequest(
  messageIndex: Int
) extends ClientMessage
object GetMusicRequest {
  implicit val rw: ReadWriter[GetMusicRequest] = macroRW
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
  hasImage: Boolean = false,
  hasMusic: Boolean = false
) extends ServerMessage
object GameStartedMessage {
  implicit val rw: ReadWriter[GameStartedMessage] = macroRW
}

case class GameLoadedMessage(
  sessionId: String,
  gameId: String,
  conversation: Seq[ConversationEntry],
  currentLocation: Option[String] = None,
  currentScene: Option[SceneData] = None
) extends ServerMessage
object GameLoadedMessage {
  implicit val rw: ReadWriter[GameLoadedMessage] = macroRW
}

case class CommandResponseMessage(
  text: String,
  messageIndex: Int,
  command: String,
  scene: Option[SceneData] = None,
  audio: Option[String] = None,
  hasImage: Boolean = false,
  hasMusic: Boolean = false
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

case class StreamCompleteMessage(
  messageIndex: Int,
  totalChunks: Int,
  duration: Long,
  scene: Option[SceneData] = None,
  audio: Option[String] = None,
  hasImage: Boolean = false,
  hasMusic: Boolean = false
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

case class ImageDataMessage(
  messageIndex: Int,
  image: String,
  status: String = "ready"
) extends ServerMessage
object ImageDataMessage {
  implicit val rw: ReadWriter[ImageDataMessage] = macroRW
}

case class MusicDataMessage(
  messageIndex: Int,
  music: String,
  mood: String,
  status: String = "ready"
) extends ServerMessage
object MusicDataMessage {
  implicit val rw: ReadWriter[MusicDataMessage] = macroRW
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
}

case class PongMessage(
  timestamp: Long = System.currentTimeMillis()
) extends ServerMessage
object PongMessage {
  implicit val rw: ReadWriter[PongMessage] = macroRW
}

// ============= Data Types =============

case class SceneData(
  locationName: String,
  exits: Seq[ExitData],
  items: Seq[String] = Seq.empty,
  npcs: Seq[String] = Seq.empty
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