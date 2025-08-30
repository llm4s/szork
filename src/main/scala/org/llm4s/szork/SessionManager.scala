package org.llm4s.szork

import scala.collection.concurrent.TrieMap

/**
 * Thread-safe session manager for game sessions.
 * Shared between HTTP and WebSocket servers.
 */
class SessionManager {
  private val sessions = TrieMap[String, GameSession]()
  
  def getSession(sessionId: String): Option[GameSession] = {
    sessions.get(sessionId)
  }
  
  def updateSession(sessionId: String, session: GameSession): Unit = {
    sessions(sessionId) = session
  }
  
  def removeSession(sessionId: String): Unit = {
    sessions.remove(sessionId)
  }
  
  def createSession(session: GameSession): Unit = {
    sessions(session.id) = session
  }
  
  def getAllSessions(): Map[String, GameSession] = {
    sessions.toMap
  }
}