package org.llm4s.szork

import java.util.UUID

/**
 * Utility for generating prefixed, truncated IDs for better readability and distinguishability.
 * 
 * Format: {prefix}-{8-char-uuid}
 * Examples: 
 *   - game-a3b2c1d4
 *   - sess-f5e6d7c8
 *   - user-9a8b7c6d
 */
object IdGenerator {
  
  /**
   * Generate a new ID with the specified prefix.
   * 
   * @param prefix The prefix to use (e.g., "game", "sess", "user")
   * @return A formatted ID like "game-a3b2c1d4"
   */
  def generate(prefix: String): String = {
    val uuid = UUID.randomUUID().toString.replace("-", "").take(8).toLowerCase
    s"$prefix-$uuid"
  }
  
  /**
   * Generate a game ID
   */
  def gameId(): String = generate("game")
  
  /**
   * Generate a session ID
   */
  def sessionId(): String = generate("sess")
  
  /**
   * Generate a user ID
   */
  def userId(): String = generate("user")
  
  /**
   * Generate a message ID
   */
  def messageId(): String = generate("msg")
  
  /**
   * Generate an adventure ID
   */
  def adventureId(): String = generate("adv")
  
  /**
   * Extract the prefix from an ID
   * 
   * @param id The full ID (e.g., "game-a3b2c1d4")
   * @return The prefix (e.g., "game")
   */
  def getPrefix(id: String): Option[String] = {
    id.split("-").headOption
  }
  
  /**
   * Extract the UUID portion from an ID
   * 
   * @param id The full ID (e.g., "game-a3b2c1d4")
   * @return The UUID portion (e.g., "a3b2c1d4")
   */
  def getUuidPart(id: String): Option[String] = {
    id.split("-").drop(1).headOption
  }
  
  /**
   * Validate that an ID has the expected format
   * 
   * @param id The ID to validate
   * @param expectedPrefix Optional expected prefix to check
   * @return true if valid
   */
  def isValid(id: String, expectedPrefix: Option[String] = None): Boolean = {
    val parts = id.split("-")
    if (parts.length != 2) return false
    
    val prefix = parts(0)
    val uuid = parts(1)
    
    // Check UUID part is 8 characters and alphanumeric
    if (uuid.length != 8 || !uuid.matches("[a-f0-9]+")) return false
    
    // Check prefix if specified
    expectedPrefix match {
      case Some(expected) => prefix == expected
      case None => prefix.nonEmpty
    }
  }
}