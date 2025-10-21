package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.stubs.FakeLLMClientInvalid
import org.llm4s.szork.game.GameEngine

class GameEngineValidationSpec extends AnyFunSuite with Matchers {
  test("engine records validation issues for invalid exit directions") {
    implicit val llm = new FakeLLMClientInvalid()
    val engine = GameEngine.create(llmClient = llm, sessionId = "val-test", theme = Some("underground realm"))

    val init = engine.initialize()
    init.isRight shouldBe true

    val issuesOpt = engine.popValidationIssues()
    issuesOpt.isDefined shouldBe true
    issuesOpt.get.mkString(" ").toLowerCase should include("invalid exit direction")

    // ensure issues are cleared after pop
    engine.popValidationIssues() shouldBe None
  }
}
