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

/** An active game: its engine and settings. `executor` serialises all work on the engine.
  *
  * The engine is built with every media client the server can provide; `features` decides which of them the session
  * uses, so they can change while it plays (loading an open game applies the newly requested features). They are
  * changed only by the session's own tasks, between turns.
  */
final class GameSession(
  val id: String,
  val gameId: String,
  val engine: GameEngine,
  val theme: Option[GameTheme],
  val artStyle: Option[ArtStyle],
  initialFeatures: SessionFeatures,
  val executor: ExecutionContext
) {
  @volatile private var lastActiveAt: Long = System.currentTimeMillis()
  @volatile private var currentFeatures: SessionFeatures = initialFeatures
  @volatile private var hasEnded: Boolean = false

  def features: SessionFeatures = currentFeatures

  /** Switches the session to `features`; call from a task on `executor`, so no turn sees a change part-way. */
  def updateFeatures(features: SessionFeatures): Unit = currentFeatures = features

  def touch(): Unit = lastActiveAt = System.currentTimeMillis()
  def lastActive: Long = lastActiveAt

  /** Marks the session over (its game deleted, or the session removed or expired): work still queued for it is dropped,
    * so nothing plays or saves the game after.
    */
  def end(): Unit = hasEnded = true
  def ended: Boolean = hasEnded

  // Turns running or waiting on `executor`
  private val pendingTurns = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Counts a turn in, unless `max` are already running or waiting: then false, and the turn should be refused. */
  def beginTurn(max: Int): Boolean =
    if (pendingTurns.incrementAndGet() <= max) true
    else { pendingTurns.decrementAndGet(); false }

  /** Counts a turn out once it has finished (or was dropped). */
  def endTurn(): Unit = pendingTurns.decrementAndGet(): Unit
}

/** Thread-safe registry of active game sessions, with at most one session per game.
  *
  * A game must have one writer: two sessions playing the same game would save over each other's steps. Changes that
  * must not interleave - registering or ending a game's session, expiring idle sessions, and attaching a connection
  * (through `withSession`, `joinGame` and `registerOrJoin`) - run under one lock. The functions passed in run under it
  * too, so they must be quick and must not block.
  */
class SessionManager {
  private val sessions = TrieMap[String, GameSession]()
  // The session playing each game
  private val games = TrieMap[String, GameSession]()

  def getSession(sessionId: String): Option[GameSession] = sessions.get(sessionId)

  /** How many sessions there are. */
  def count: Int = sessions.size

  /** The session playing `gameId`, if it has one. */
  def sessionForGame(gameId: String): Option[GameSession] = games.get(gameId)

  /** Registers the session of a new game; false, registering nothing, if its session id or game id is already taken. */
  def createSession(session: GameSession): Boolean = synchronized {
    if (sessions.contains(session.id) || games.contains(session.gameId)) false
    else {
      sessions(session.id) = session
      games(session.gameId) = session
      true
    }
  }

  /** Removes the session and ends it. */
  def removeSession(sessionId: String): Unit = synchronized {
    sessions.remove(sessionId).foreach { s =>
      s.end()
      games.remove(s.gameId, s)
    }
  }

  /** Runs `f` on session `sessionId`, if it exists, so that it cannot expire while `f` runs. */
  def withSession[A](sessionId: String)(f: GameSession => A): Option[A] = synchronized {
    sessions.get(sessionId).map(f)
  }

  /** Runs `f` on the session playing `gameId`, if there is one, so that it cannot expire while `f` runs. */
  def joinGame[A](gameId: String)(f: GameSession => A): Option[A] = synchronized {
    games.get(gameId).map(f)
  }

  /** Registers `session` for its game unless the game already has a session; then runs `f` on the game's session, which
    * is `session` only if it was registered.
    */
  def registerOrJoin[A](session: GameSession)(f: GameSession => A): A = synchronized {
    val live = games.getOrElseUpdate(session.gameId, session)
    if (live eq session) sessions(session.id) = session
    f(live)
  }

  /** Removes the session playing `gameId`, ends it and returns it. */
  def removeGame(gameId: String): Option[GameSession] = synchronized {
    games.remove(gameId).map { session =>
      session.end()
      sessions.remove(session.id, session)
      session
    }
  }

  /** Removes and ends sessions idle for longer than `ttlMillis` that `inUse` does not claim; returns their ids. */
  def expireIdle(now: Long, ttlMillis: Long, inUse: String => Boolean): List[String] = synchronized {
    val expired = sessions.values.filter(s => now - s.lastActive > ttlMillis && !inUse(s.id)).toList
    expired.foreach { s =>
      s.end()
      sessions.remove(s.id, s)
      games.remove(s.gameId, s)
    }
    expired.map(_.id)
  }
}
