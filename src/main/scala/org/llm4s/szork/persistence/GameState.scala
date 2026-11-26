package org.llm4s.szork.persistence

import org.llm4s.szork.api.{GameTheme, ArtStyle}
import org.llm4s.szork.game.{GameScene, AdventureOutline, WorldSnapshot}
import org.llm4s.llmconnect.model.Message

case class GameState(
  gameId: String,
  theme: Option[GameTheme],
  artStyle: Option[ArtStyle],
  adventureOutline: Option[AdventureOutline], // Full adventure design
  currentScene: Option[GameScene],
  visitedLocations: Set[String],
  inventory: List[String],
  createdAt: Long,
  lastSaved: Long,
  lastPlayed: Long,
  totalPlayTime: Long, // Total play time in milliseconds
  adventureTitle: Option[String] = None,
  adventureId: Option[String] = None, // The library adventure the game plays, if it plays one
  // The Dungeon Master's conversation (llm4s thread history); the player's transcript is derived from it
  agentMessages: List[Message] = Nil,
  worldState: WorldSnapshot = WorldSnapshot.empty // Exits, location facts, characters met, quest progress
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
