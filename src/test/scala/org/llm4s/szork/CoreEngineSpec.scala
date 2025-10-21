package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.core.{CoreState, CoreEngine}
import org.llm4s.szork.game.{Exit, GameScene}

class CoreEngineSpec extends AnyFunSuite with Matchers {

  test("applyScene updates current scene and visited") {
    val scene = GameScene(
      locationId = "room1",
      locationName = "Room 1",
      narrationText = "A small room.",
      imageDescription = "",
      musicDescription = "",
      musicMood = "exploration",
      exits = List(Exit("north", "room2"))
    )
    val s0 = CoreState()
    val s1 = CoreEngine.applyScene(s0, scene)
    s1.currentScene.map(_.locationId) shouldBe Some("room1")
    s1.visitedLocations should contain("room1")
    s1.conversationHistory.last.content should include("A small room")
  }

  test("shouldGenerate helpers react to content") {
    val s0 = CoreState()
    CoreEngine.shouldGenerateSceneImage(s0, "you enter a hall") shouldBe true
    CoreEngine.shouldGenerateBackgroundMusic(s0, "a battle begins") shouldBe true
  }
}
