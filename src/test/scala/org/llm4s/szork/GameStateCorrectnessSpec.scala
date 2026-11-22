package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.llm4s.szork.stubs.{DmAnswers, FakeTTSClient, ScriptedDm}
import org.llm4s.szork.game._

/** Regression tests for game-state correctness (issue #23). */
class GameStateCorrectnessSpec extends AnyFunSuite with Matchers {

  /** Adds an item on "take" commands, as the real Dungeon Master would through its tools. */
  private def takingLLM(): ScriptedLLMClient = ScriptedDm(ScriptedDm.takesItems)

  /** Answers the command "test" with `answer`. */
  private def fixedAnswerLLM(answer: String): ScriptedLLMClient =
    ScriptedDm { case ScriptedDm.Command("test") => Reply.text(answer) }

  private val outline = AdventureOutline(
    title = "The Sunken Vault",
    mainQuest = "Recover the drowned crown",
    subQuests = Nil,
    keyLocations = List(LocationOutline("vault_door", "Vault Door", "A barnacled door", "The way in")),
    importantItems = List(ItemOutline("crown", "A drowned crown", "The goal")),
    keyCharacters = Nil,
    adventureArc = "Descend, find the crown, escape"
  )

  test("each game has its own inventory") {
    val a = GameEngine.create(takingLLM(), sessionId = "a")
    val b = GameEngine.create(takingLLM(), sessionId = "b")
    a.initialize().isRight shouldBe true
    a.processCommand("take lamp", generateAudio = false).isRight shouldBe true

    b.initialize().isRight shouldBe true // starting another game must not clear a's inventory
    b.processCommand("take rope", generateAudio = false).isRight shouldBe true

    a.getInventory shouldBe List("lamp")
    b.getInventory shouldBe List("rope")
    a.getGameState("game-a", None, None).inventory shouldBe List("lamp")
  }

  test("a restored game keeps its adventure outline in the Dungeon Master's prompt") {
    val original = GameEngine.create(ScriptedDm(), sessionId = "orig", adventureOutline = Some(outline))
    original.initialize().isRight shouldBe true
    val saved = original.getGameState("game-1", None, None)

    // Loaded without an outline passed in: the save's outline must still be used, and saved again
    val llm = ScriptedDm()
    val restored = GameEngine.create(llm, sessionId = "restored")
    restored.restoreGameState(saved)
    restored.processCommand("look", generateAudio = false).isRight shouldBe true

    val systemPrompts = llm.lastCall.get.messages.collect { case s: SystemMessage => s.content }
    systemPrompts.last should include("The Sunken Vault")
    restored.getGameState("game-1", None, None).adventureOutline.map(_.title) shouldBe Some("The Sunken Vault")
  }

  test("a response over the narration length guideline is used, with a warning") {
    val longNarration = "You wade deeper. " * 40 // > 500 characters
    val answer = DmAnswers.simple(longNarration, "cavern_entrance", "wade")
    val engine = GameEngine.create(fixedAnswerLLM(answer), sessionId = "long")
    engine.initialize().isRight shouldBe true

    val response = engine.processCommand("test", generateAudio = false)
    response.toOption.get.text shouldBe longNarration
    engine.popValidationIssues().get.mkString should include("narrationText too long")
  }

  test("a response that breaks the game's rules shows its narration, never raw JSON") {
    val answer =
      DmAnswers.scene("The floor gives way beneath you!", "Bad Id!", "Pit", Seq("down" -> "pit"))
    val engine = GameEngine.create(fixedAnswerLLM(answer), sessionId = "bad")
    engine.initialize().isRight shouldBe true
    val before = engine.getCurrentScene

    val text = engine.processCommand("test", generateAudio = false).toOption.get.text
    text shouldBe "The floor gives way beneath you!"
    text should not include "{"
    engine.getCurrentScene shouldBe before // the invalid scene is not applied
  }

  test("scene media is requested only when the player moves to a new location") {
    val engine = GameEngine.create(ScriptedDm(), sessionId = "media")
    engine.initialize().isRight shouldBe true
    engine.locationChangedLastTurn shouldBe true // first scene

    engine.processCommand("go north", generateAudio = false).isRight shouldBe true
    engine.locationChangedLastTurn shouldBe true
    engine.locationChangedLastTurn shouldBe true

    engine.processCommand("examine wall", generateAudio = false).isRight shouldBe true
    engine.locationChangedLastTurn shouldBe false
    engine.locationChangedLastTurn shouldBe false

    engine.processCommand("go north", generateAudio = false).isRight shouldBe true // same hall again
    engine.locationChangedLastTurn shouldBe false
  }

  test("each narration is recorded once in the transcript") {
    val engine = GameEngine.create(ScriptedDm(), sessionId = "transcript")
    engine.initialize().isRight shouldBe true
    engine.processCommand("go north", generateAudio = false).isRight shouldBe true

    val transcript = engine.getGameState("g", None, None).conversationHistory.map(e => e.role -> e.content)
    transcript.map(_._1) shouldBe List("assistant", "user", "assistant")
    transcript.last._2 should include("small hall")
  }

  test("narration audio comes only from the configured TTS client, and only when requested") {
    var calls = 0
    val tts = new FakeTTSClient {
      override def synthesizeToBase64(text: String, voice: String) = {
        calls += 1; super.synthesizeToBase64(text, voice)
      }
    }
    val engine = GameEngine.create(ScriptedDm(), sessionId = "tts", ttsClient = Some(tts))
    engine.initialize().isRight shouldBe true

    engine
      .processCommand("look", generateAudio = false, onTextChunk = Some(_ => ()))
      .toOption
      .get
      .audioBase64 shouldBe None
    calls shouldBe 0
    engine
      .processCommand("look", generateAudio = true, onTextChunk = Some(_ => ()))
      .toOption
      .get
      .audioBase64 shouldBe Some("FAKE_AUDIO_BASE64")
    calls shouldBe 1

    val noTts = GameEngine.create(ScriptedDm(), sessionId = "no-tts")
    noTts.initialize().isRight shouldBe true
    noTts
      .processCommand("look", generateAudio = true, onTextChunk = Some(_ => ()))
      .toOption
      .get
      .audioBase64 shouldBe None
  }

  test("step data records only the tool calls of the latest turn") {
    val engine = GameEngine.create(takingLLM(), sessionId = "steps")
    engine.initialize().isRight shouldBe true
    engine.processCommand("take lamp", generateAudio = false).isRight shouldBe true
    engine.createStepData("g", None, None, Some("take lamp"), "", None, 0).toolCalls.map(_.name) shouldBe List(
      "add_inventory_item")

    engine.processCommand("look", generateAudio = false).isRight shouldBe true
    engine.createStepData("g", None, None, Some("look"), "", None, 0).toolCalls shouldBe empty
  }
}
