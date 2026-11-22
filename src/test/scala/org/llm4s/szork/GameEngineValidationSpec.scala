package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}
import org.llm4s.szork.game.{DungeonMasterOutput, GameEngine}

class GameEngineValidationSpec extends AnyFunSuite with Matchers {
  test("engine records validation issues for responses that break the game's rules") {
    // Valid against the output schema, but its location id breaks the game's own rules
    val llm = ScriptedLLMClient.always(
      Reply.text(DmAnswers.scene("Invalid scene response.", "Invalid Room!", "Invalid Room", Seq("north" -> "next"))))
    val engine = GameEngine.create(llmClient = llm, sessionId = "val-test", theme = Some("underground realm"))

    val init = engine.initialize()
    init.isRight shouldBe true

    val issuesOpt = engine.popValidationIssues()
    issuesOpt.isDefined shouldBe true
    issuesOpt.get.mkString(" ") should include("must match [a-z0-9_-]+")

    // ensure issues are cleared after pop
    engine.popValidationIssues() shouldBe None
  }

  test("llm4s states the output schema in the system prompt, so the game's prompt does not repeat it") {
    val llm = ScriptedDm()
    GameEngine.create(llm, "schema-prompt").initialize().isRight shouldBe true

    val system = llm.calls.head.messages.collect { case s: SystemMessage => s.content }.mkString("\n")
    system should include("JSON Schema")
    system should include("\"narrationText\"")
    DungeonMasterOutput.Directions.foreach(direction => system should include(s"\"$direction\""))
    DungeonMasterOutput.MusicMoods.foreach(mood => system should include(mood))
    // The schema is stated once, by llm4s: the game's own prompt has no JSON example of the answer
    system.take(system.indexOf("JSON Schema")) should not include "\"responseType\""
  }

  test("an exit direction outside the schema is refused by llm4s: corrected once, then the turn fails") {
    val badDirection = DmAnswers.scene("A strange hallway.", "strange_hall", "Strange Hall", Seq("north-east" -> "x"))
    val llm = ScriptedLLMClient.always(Reply.text(badDirection))
    val engine = GameEngine.create(llm, "bad-direction")

    val result = engine.initialize()
    result.isLeft shouldBe true
    llm.callCount shouldBe 2 // the answer, and one correction
    val correction = llm.lastCall.get.messages.last.content
    correction should include("did not match the required JSON Schema")
    correction should include("north-east")
  }
}
