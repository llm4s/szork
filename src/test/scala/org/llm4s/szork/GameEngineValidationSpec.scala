package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.Reply
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}
import org.llm4s.szork.game.{DungeonMasterOutput, GameEngine}

/** How the Dungeon Master's answers are held to the output schema and the game's rules for them. */
class GameEngineValidationSpec extends AnyFunSuite with Matchers {

  test(
    "an answer that breaks the game's rules goes back to the model once; a second fails the turn, changing nothing") {
    // Valid against the output schema, but its location id is not an id: the player would "arrive" nowhere
    val badId = DmAnswers.scene("You step into the crypt.", "Crypt Door", "Crypt Door", Seq("south" -> "x"))
    // answers "enter", and the correction of its answer to it, with the same bad id
    val llm = ScriptedDm {
      case c if c.messages.exists(m => m.isInstanceOf[UserMessage] && m.content == "enter") =>
        Reply.text(badId)
    }
    val engine = GameEngine.create(llm, "bad-id")
    engine.initialize().isRight shouldBe true
    val (before, messages) = (engine.getCurrentScene, engine.getMessages)
    val callsBefore = llm.callCount

    engine.processCommand("enter", generateAudio = false).isLeft shouldBe true
    llm.callCount - callsBefore shouldBe 2 // the answer, and llm4s's one correction
    val correction = llm.lastCall.get.messages.last.content
    correction should include("$.locationId: 'Crypt Door' is not a location id")
    engine.getCurrentScene shouldBe before
    engine.getMessages shouldBe messages
  }

  test("llm4s states the output schema in the system prompt, so the game's prompt does not repeat it") {
    val llm = ScriptedDm()
    GameEngine.create(llm, "schema-prompt").initialize().isRight shouldBe true

    val system = llm.calls.head.messages.collect { case s: SystemMessage => s.content }.mkString("\n")
    system should include("JSON Schema")
    system should include("\"narrationText\"")
    DungeonMasterOutput.Directions.foreach(direction => system should include(s"\"$direction\""))
    DungeonMasterOutput.MusicMoods.foreach(mood => system should include(s"\"$mood\""))
    // The schema is stated once, by llm4s: the game's own prompt has no JSON example of the answer
    system.take(system.indexOf("JSON Schema")) should not include "\"responseType\""
  }
}
