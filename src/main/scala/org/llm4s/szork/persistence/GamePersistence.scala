package org.llm4s.szork.persistence

import org.llm4s.szork.api.{GameTheme, ArtStyle}
import org.llm4s.szork.game.{GameScene, AdventureOutline}
import org.llm4s.llmconnect.model.Message
import org.llm4s.szork.error.ErrorHandling._

case class ConversationEntry(
  role: String,
  content: String,
  timestamp: Long
)

case class GameState(
  gameId: String,
  theme: Option[GameTheme],
  artStyle: Option[ArtStyle],
  adventureOutline: Option[AdventureOutline], // Full adventure design
  currentScene: Option[GameScene],
  visitedLocations: Set[String],
  conversationHistory: List[ConversationEntry], // The transcript shown to the player (narration only)
  inventory: List[String],
  createdAt: Long,
  lastSaved: Long,
  lastPlayed: Long,
  totalPlayTime: Long, // Total play time in milliseconds
  adventureTitle: Option[String] = None,
  agentMessages: List[Message] = Nil // The Dungeon Master's conversation (llm4s thread history)
)

case class GameMetadata(
  gameId: String,
  theme: String,
  artStyle: String,
  adventureTitle: String,
  createdAt: Long,
  lastSaved: Long,
  lastPlayed: Long,
  totalPlayTime: Long, // Total play time in milliseconds
  currentStep: Int = 1,
  totalSteps: Int = 1
)

object GamePersistence {
  def listGames(): List[GameMetadata] =
    // Delegate to StepPersistence
    StepPersistence.listGames()

  def deleteGame(gameId: String): SzorkResult[Unit] =
    // Delegate to StepPersistence
    StepPersistence.deleteGame(gameId)

  /** Load a game from step-based persistence.
    *
    * @param gameId
    *   Game identifier
    * @return
    *   GameState or error
    */
  def loadGame(gameId: String): SzorkResult[GameState] =
    StepPersistence.loadLatestStep(gameId).map(_.gameState)
}
