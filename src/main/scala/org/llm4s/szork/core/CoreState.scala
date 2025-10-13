package org.llm4s.szork.core

import org.llm4s.szork.game.GameScene
import org.llm4s.szork.persistence.ConversationEntry

case class CoreState(
  currentScene: Option[GameScene] = None,
  visitedLocations: Set[String] = Set.empty,
  inventory: Set[String] = Set.empty,
  conversationHistory: List[ConversationEntry] = Nil
)

object CoreEngine {
  def applyScene(state: CoreState, scene: GameScene): CoreState =
    state.copy(
      currentScene = Some(scene),
      visitedLocations = state.visitedLocations + scene.locationId,
      conversationHistory =
        state.conversationHistory :+ ConversationEntry("assistant", scene.narrationText, System.currentTimeMillis())
    )

  def applySimpleResponse(state: CoreState, text: String): CoreState =
    state.copy(conversationHistory =
      state.conversationHistory :+ ConversationEntry("assistant", text, System.currentTimeMillis()))

  def trackUser(state: CoreState, text: String): CoreState =
    state.copy(conversationHistory =
      state.conversationHistory :+ ConversationEntry("user", text, System.currentTimeMillis()))

  // Pure helpers used by the engine
  def isNewScene(response: String): Boolean = {
    val sceneIndicators = List(
      "you enter",
      "you arrive",
      "you find yourself",
      "you see",
      "before you",
      "you are in",
      "you stand",
      "exits:",
      "you reach"
    )
    val lowerResponse = response.toLowerCase
    sceneIndicators.exists(lowerResponse.contains)
  }

  def shouldGenerateSceneImage(state: CoreState, responseText: String): Boolean =
    state.currentScene.isDefined || isNewScene(responseText)

  def shouldGenerateBackgroundMusic(state: CoreState, responseText: String): Boolean = {
    val lowerText = responseText.toLowerCase
    state.currentScene.isDefined ||
    isNewScene(responseText) ||
    lowerText.contains("battle") ||
    lowerText.contains("victory") ||
    lowerText.contains("defeated") ||
    lowerText.contains("enter") ||
    lowerText.contains("arrive")
  }
}
