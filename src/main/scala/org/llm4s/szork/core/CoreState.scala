package org.llm4s.szork.core

import org.llm4s.szork.game.GameScene

case class CoreState(
  currentScene: Option[GameScene] = None,
  visitedLocations: Set[String] = Set.empty
)

object CoreEngine {

  /** Moves the player to `scene`. */
  def applyScene(state: CoreState, scene: GameScene): CoreState =
    state.copy(
      currentScene = Some(scene),
      visitedLocations = state.visitedLocations + scene.locationId
    )
}
