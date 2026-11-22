package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.stubs.ScriptedDm
import org.llm4s.szork.game.GameEngine

class GameEngineUnitSpec extends AnyFunSuite with Matchers {

  test("initialize and process commands without network using a scripted Dungeon Master") {
    val llm = ScriptedDm()
    val engine = GameEngine.create(llmClient = llm, sessionId = "test-session", theme = Some("underground realm"))

    val init = engine.initialize()
    init.isRight shouldBe true

    val r1 = engine.processCommand("look", generateAudio = false)
    r1.isRight shouldBe true
    r1.toOption.get.text.toLowerCase should include("cavern")

    val r2 = engine.processCommand("go north", generateAudio = false)
    r2.isRight shouldBe true
    engine.getCurrentScene.map(_.locationId) shouldBe Some("hall")

    val r3 = engine.processCommand("examine wall", generateAudio = false)
    r3.isRight shouldBe true
    r3.toOption.get.text.toLowerCase should include("examine")
  }
}
