package org.llm4s.szork.persistence

import org.llm4s.szork.api.{GameTheme, ArtStyle}
import org.llm4s.szork.game.{GameScene, AdventureOutline, LocationOutline, ItemOutline, CharacterOutline}
import org.llm4s.szork.error.ErrorHandling._
import ujson._

case class ConversationEntry(
  role: String,
  content: String,
  timestamp: Long
)

// Cache entry for generated media (images/music) per location
case class MediaCacheEntry(
  locationId: String,
  imagePrompt: Option[String],
  imageCacheId: Option[String],
  musicPrompt: Option[String],
  musicCacheId: Option[String],
  generatedAt: Long
)

case class GameState(
  gameId: String,
  theme: Option[GameTheme],
  artStyle: Option[ArtStyle],
  adventureOutline: Option[AdventureOutline], // Full adventure design
  currentScene: Option[GameScene],
  visitedLocations: Set[String],
  conversationHistory: List[ConversationEntry], // Kept for backwards compatibility
  inventory: List[String],
  createdAt: Long,
  lastSaved: Long,
  lastPlayed: Long = System.currentTimeMillis(),
  totalPlayTime: Long = 0, // Total play time in milliseconds
  adventureTitle: Option[String] = None,
  // New fields for complete state persistence
  agentMessages: List[ujson.Value] = List.empty, // Full agent conversation as JSON
  mediaCache: Map[String, MediaCacheEntry] = Map.empty, // Media cache per location
  systemPrompt: Option[String] = None // Complete system prompt including adventure outline
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
  // New fields for step-based persistence
  currentStep: Int = 1,
  totalSteps: Int = 1
)

object GamePersistence {
  // Helper function to parse AdventureOutline from JSON
  private[szork] def parseAdventureOutlineFromJson(json: ujson.Value): AdventureOutline =
    AdventureOutline(
      title = json("title").str,
      tagline = json.obj.get("tagline").flatMap {
        case Null => None
        case s => Some(s.str)
      },
      mainQuest = json("mainQuest").str,
      subQuests = json("subQuests").arr.map(_.str).toList,
      keyLocations = json("keyLocations").arr
        .map(loc =>
          LocationOutline(
            id = loc("id").str,
            name = loc("name").str,
            description = loc("description").str,
            significance = loc("significance").str
          ))
        .toList,
      importantItems = json("importantItems").arr
        .map(item =>
          ItemOutline(
            name = item("name").str,
            description = item("description").str,
            purpose = item("purpose").str
          ))
        .toList,
      keyCharacters = json("keyCharacters").arr
        .map(char =>
          CharacterOutline(
            name = char("name").str,
            role = char("role").str,
            description = char("description").str
          ))
        .toList,
      adventureArc = json("adventureArc").str,
      specialMechanics = json.obj.get("specialMechanics").flatMap {
        case Null => None
        case s => Some(s.str)
      }
    )

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
