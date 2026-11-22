package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.szork.stubs.{DmAnswers, FakeLLMClient, FakeLLMClientInvalid}
import org.llm4s.szork.game.{DungeonMasterOutput, GameEngine}

class GameEngineValidationSpec extends AnyFunSuite with Matchers {
  test("engine records validation issues for responses that break the game's rules") {
    implicit val llm = new FakeLLMClientInvalid()
    val engine = GameEngine.create(llmClient = llm, sessionId = "val-test", theme = Some("underground realm"))

    val init = engine.initialize()
    init.isRight shouldBe true

    val issuesOpt = engine.popValidationIssues()
    issuesOpt.isDefined shouldBe true
    issuesOpt.get.mkString(" ") should include("must match [a-z0-9_-]+")

    // ensure issues are cleared after pop
    engine.popValidationIssues() shouldBe None
  }

  /** Records every request, and answers every one with `answer`. */
  private class RecordingLLM(answer: Option[String] = None) extends FakeLLMClient {
    @volatile var requests: Vector[Seq[Message]] = Vector.empty
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      requests :+= conversation.messages
      answer.fold(super.complete(conversation, options)) { content =>
        Right(
          Completion(
            id = s"fixed-${requests.length}",
            created = java.time.Instant.now(),
            content = content,
            model = "fake-llm",
            message = AssistantMessage(content)))
      }
    }
  }

  test("llm4s states the output schema in the system prompt, so the game's prompt does not repeat it") {
    val llm = new RecordingLLM()
    GameEngine.create(llm, "schema-prompt").initialize().isRight shouldBe true

    val system = llm.requests.head.collect { case s: SystemMessage => s.content }.mkString("\n")
    system should include("JSON Schema")
    system should include("\"narrationText\"")
    DungeonMasterOutput.Directions.foreach(direction => system should include(s"\"$direction\""))
    DungeonMasterOutput.MusicMoods.foreach(mood => system should include(mood))
    // The schema is stated once, by llm4s: the game's own prompt has no JSON example of the answer
    system.take(system.indexOf("JSON Schema")) should not include "\"responseType\""
  }

  test("an exit direction outside the schema is refused by llm4s: corrected once, then the turn fails") {
    val badDirection = DmAnswers.scene("A strange hallway.", "strange_hall", "Strange Hall", Seq("north-east" -> "x"))
    val llm = new RecordingLLM(Some(badDirection))
    val engine = GameEngine.create(llm, "bad-direction")

    val result = engine.initialize()
    result.isLeft shouldBe true
    llm.requests.length shouldBe 2 // the answer, and one correction
    llm.requests.last.last.content should include("did not match the required JSON Schema")
    llm.requests.last.last.content should include("north-east")
  }
}
