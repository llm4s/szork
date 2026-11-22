package org.llm4s.szork.api

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import org.llm4s.szork.game.GameEngine

/** The optional features a session uses: what the player asked for, limited to what the server can provide. */
final case class SessionFeatures(
  tts: Boolean,
  stt: Boolean,
  image: Boolean,
  music: Boolean,
  autoSave: Boolean = true
)

object SessionFeatures {

  /** The features requested (None meaning "if available"), limited to the server's `capabilities`. */
  def requested(
    capabilities: Capabilities,
    tts: Option[Boolean],
    stt: Option[Boolean],
    image: Option[Boolean],
    music: Option[Boolean]
  ): SessionFeatures =
    SessionFeatures(
      tts = tts.getOrElse(true) && capabilities.tts,
      stt = stt.getOrElse(true) && capabilities.stt,
      image = image.getOrElse(true) && capabilities.image,
      music = music.getOrElse(true) && capabilities.music
    )
}

/** An active game: its engine and settings. `executor` serialises all work on the engine. */
final class GameSession(
  val id: String,
  val gameId: String,
  val engine: GameEngine,
  val theme: Option[GameTheme],
  val artStyle: Option[ArtStyle],
  val features: SessionFeatures,
  val executor: ExecutionContext
) {
  @volatile private var lastActiveAt: Long = System.currentTimeMillis()

  def touch(): Unit = lastActiveAt = System.currentTimeMillis()
  def lastActive: Long = lastActiveAt
}

/** Thread-safe registry of active game sessions. */
class SessionManager {
  private val sessions = TrieMap[String, GameSession]()

  def getSession(sessionId: String): Option[GameSession] = sessions.get(sessionId)

  def createSession(session: GameSession): Unit = sessions(session.id) = session

  def removeSession(sessionId: String): Unit = sessions.remove(sessionId)

  def getAllSessions(): Map[String, GameSession] = sessions.toMap

  /** Removes sessions idle for longer than `ttlMillis` that `inUse` does not claim; returns their ids. */
  def expireIdle(now: Long, ttlMillis: Long, inUse: String => Boolean): List[String] = {
    val expired = sessions.values.filter(s => now - s.lastActive > ttlMillis && !inUse(s.id)).map(_.id).toList
    expired.foreach(sessions.remove)
    expired
  }
}
