package org.llm4s.szork

import org.llm4s.szork.game._
import org.llm4s.szork.stubs.{DmAnswers, FakeAnthropic}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** What Szork's agents put on the wire, read from llm4s's real Anthropic client talking to a local server.
  *
  * These are the properties a scripted client cannot test, and the ones the first live runs failed on
  * (llm4s/llm4s#2909): a sampling parameter the model refuses, and an answer length too short for the answer.
  */
class WireFormatSpec extends AnyFunSuite with Matchers {

  private val outline = AdventureOutline(
    title = "The Wire",
    mainQuest = "Cross it",
    subQuests = Nil,
    keyLocations = List(
      LocationOutline("hall", "Hall", "A hall.", "The start", List(ExitOutline("north", "study", "A door", "open"))),
      LocationOutline("study", "Study", "A study.", "The end", List(ExitOutline("south", "hall", "A door", "open")))
    ),
    importantItems = Nil,
    keyCharacters = Nil,
    adventureArc = "Across"
  )

  private val outlineAnswer = ujson.write(AdventureGenerator.outlineToJson(outline))
  private val openingAnswer = DmAnswers.scene("You stand in the hall.", "hall", "Hall", Seq("north" -> "study"))

  test("the designer asks for room for a whole outline, and sends no temperature to a model that refuses one") {
    FakeAnthropic.using(_ => outlineAnswer) { api =>
      AdventureGenerator.generateAdventureOutline("a wire")(using api.client(Some("claude-sonnet-5-5"))) shouldBe
        Right(outline)

      val request = api.requests.head
      request("model").str shouldBe "claude-sonnet-5-5"
      request("max_tokens").num.toInt shouldBe AdventureGenerator.MaxOutputTokens
      request.obj.keySet should not contain "temperature"
      FakeAnthropic.systemOf(request) should include("JSON Schema")
    }
  }

  test("no sampling parameter is sent that Szork did not set, whatever the model") {
    FakeAnthropic.using(_ => outlineAnswer) { api =>
      AdventureGenerator
        .generateAdventureOutline("a wire")(using api.client(Some("claude-sonnet-4-6")))
        .isRight shouldBe
        true
      (api.requests.head.obj.keySet & Set("temperature", "top_p", "top_k")) shouldBe Set.empty
    }
  }

  test("an outline the model did not finish fails the generation with a reason the player can act on") {
    FakeAnthropic.using(_ => outlineAnswer.take(outlineAnswer.length / 2), stopReason = "max_tokens") { api =>
      val failed = AdventureGenerator.generateAdventureOutline("a wire")(using api.client()).left.toOption.get
      failed.userMessage should include("incomplete or malformed")
      api.requests should have size 2 // the answer, and llm4s's one correction
    }
  }

  test("the Dungeon Master's request carries the outline, the game state and the game's tools") {
    FakeAnthropic.using(_ => openingAnswer) { api =>
      val engine = GameEngine.create(api.client(), "wire", adventureOutline = Some(outline))
      engine.initialize() shouldBe Right("You stand in the hall.")
      engine.processCommand("look", generateAudio = false).isRight shouldBe true

      val opening = api.requests.head
      FakeAnthropic.systemOf(opening) should include("The Wire")
      FakeAnthropic.toolsOf(
        opening) should contain allOf ("add_inventory_item", "set_exit_state", "update_quest_progress")
      opening.obj.keySet should not contain "temperature"
      opening("max_tokens").num.toInt should be >= 1024

      // The second turn's request states where the first left the player
      FakeAnthropic.systemOf(api.requests.last) should include("hall")
      api.requests.last("messages").arr.map(_("role").str).toList should contain("assistant")
    }
  }

  test("a reasoning effort reaches the designer, the Dungeon Master and a model that reasons in its own way") {
    import org.llm4s.llmconnect.model.ReasoningEffort
    val low = Some(ReasoningEffort.Low)

    // The Claude 5 models always reason: they are told how hard, and decide how much themselves
    FakeAnthropic.using(_ => outlineAnswer) { api =>
      AdventureGenerator.generateAdventureOutline("a wire", reasoning = low)(using
        api.client(Some("claude-haiku-5-5"))) shouldBe Right(outline)
      api.requests.head("output_config")("effort").str shouldBe "low"
      api.requests.head("thinking")("type").str shouldBe "adaptive"
    }
    FakeAnthropic.using(_ => openingAnswer) { api =>
      val engine = GameEngine.create(api.client(Some("claude-sonnet-5-5")), "wire", reasoning = low)
      engine.initialize().isRight shouldBe true
      api.requests.head("output_config")("effort").str shouldBe "low"
      api.requests.head("max_tokens").num.toInt shouldBe GameEngine.MaxOutputTokens
    }
    // An older model reasons within a budget, which must leave room for the answer
    FakeAnthropic.using(_ => openingAnswer) { api =>
      val engine = GameEngine.create(api.client(Some("claude-sonnet-4-6")), "wire", reasoning = low)
      engine.initialize().isRight shouldBe true
      val request = api.requests.head
      request("thinking")("budget_tokens").num.toInt should be < GameEngine.MaxOutputTokens / 2
      request.obj.keySet should not contain "output_config"
    }
    // No effort set: nothing about reasoning is sent
    FakeAnthropic.using(_ => openingAnswer) { api =>
      GameEngine.create(api.client(Some("claude-sonnet-5-5")), "wire").initialize().isRight shouldBe true
      (api.requests.head.obj.keySet & Set("output_config", "thinking")) shouldBe Set.empty
    }
  }
}
