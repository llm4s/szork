package org.llm4s.szork.core

import org.llm4s.szork.game.GameScene
import org.llm4s.szork.persistence.ConversationEntry

case class CoreState(
  currentScene: Option[GameScene] = None,
  visitedLocations: Set[String] = Set.empty,
  conversationHistory: List[ConversationEntry] = Nil
)

object CoreEngine {

  /** Moves the player to `scene`. The scene's narration is recorded separately, by [[trackAssistant]]. */
  def applyScene(state: CoreState, scene: GameScene): CoreState =
    state.copy(
      currentScene = Some(scene),
      visitedLocations = state.visitedLocations + scene.locationId
    )

  def trackAssistant(state: CoreState, text: String): CoreState =
    state.copy(conversationHistory =
      state.conversationHistory :+ ConversationEntry("assistant", text, System.currentTimeMillis()))

  def trackUser(state: CoreState, text: String): CoreState =
    state.copy(conversationHistory =
      state.conversationHistory :+ ConversationEntry("user", text, System.currentTimeMillis()))
}
