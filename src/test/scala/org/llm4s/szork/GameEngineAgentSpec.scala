package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.szork.stubs.FakeLLMClient
import org.llm4s.szork.game.{GameConstants, GameEngine}

/** Covers the GameEngine's use of the llm4s Agent: conversation threads, streaming turns, and restoring a saved
  * conversation onto a new thread.
  */
class GameEngineAgentSpec extends AnyFunSuite with Matchers {

  private def newEngine(sessionId: String): GameEngine =
    GameEngine.create(llmClient = new FakeLLMClient(), sessionId = sessionId, theme = Some("underground realm"))

  test("each turn continues the same conversation, without the system prompt in its history") {
    val engine = newEngine("thread-test")
    engine.initialize().isRight shouldBe true
    engine.getMessages.collect { case u: UserMessage => u.content } shouldBe Vector(
      GameConstants.Prompts.INIT_ADVENTURE)

    engine.processCommand("look", generateAudio = false).isRight shouldBe true
    engine.processCommand("go north", generateAudio = false).isRight shouldBe true

    val messages = engine.getMessages
    messages.collect { case u: UserMessage => u.content } shouldBe Vector(
      GameConstants.Prompts.INIT_ADVENTURE,
      "look",
      "go north")
    messages.count(_.isInstanceOf[AssistantMessage]) shouldBe 3
    messages.exists(_.isInstanceOf[SystemMessage]) shouldBe false
    engine.getMessageCount shouldBe messages.length
  }

  test("a streaming turn streams narration and continues the thread a non-streaming turn opened") {
    val engine = newEngine("stream-test")
    engine.initialize().isRight shouldBe true

    val streamed = new StringBuilder()
    val result = engine.processCommandStreaming("go north", chunk => streamed.append(chunk), generateAudio = false)

    result.isRight shouldBe true
    result.toOption.get.text should include("small hall")
    streamed.toString should include("small hall")
    streamed.toString should not include "<<<JSON>>>"
    engine.getCurrentScene.map(_.locationId) shouldBe Some("hall")
    engine.getMessages.collect { case u: UserMessage => u.content }.last shouldBe "go north"
  }

  test("a restored game continues its saved conversation on a new thread") {
    val original = newEngine("save-test")
    original.initialize().isRight shouldBe true
    original.processCommand("go north", generateAudio = false).isRight shouldBe true
    val saved = original.getGameState("game-1", None, None)

    val restored = newEngine("restore-test")
    restored.restoreGameState(saved)
    restored.getMessages shouldBe original.getMessages

    val next = restored.processCommand("examine wall", generateAudio = false)
    next.isRight shouldBe true
    restored.getMessages.collect { case u: UserMessage => u.content } shouldBe Vector(
      GameConstants.Prompts.INIT_ADVENTURE,
      "go north",
      "examine wall")
  }

  test("the agent runs the game's inventory tools and feeds their results back to the model") {
    // Asks for add_inventory_item on "take" commands until it sees the tool's result, then narrates
    val llm = new FakeLLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        conversation.messages.lastOption match {
          case Some(u: UserMessage) if u.content.startsWith("take") =>
            val call = ToolCall("call-1", "add_inventory_item", ujson.Obj("item" -> "brass lantern"))
            Right(
              Completion(
                id = "tool-turn",
                created = java.time.Instant.now(),
                content = "",
                model = "fake-llm",
                message = AssistantMessage(contentOpt = None, toolCalls = Seq(call)),
                toolCalls = List(call)
              ))
          case _ => super.complete(conversation, options)
        }
    }
    val engine = GameEngine.create(llmClient = llm, sessionId = "tool-test")
    engine.initialize().isRight shouldBe true

    engine.processCommand("take lantern", generateAudio = false).isRight shouldBe true

    engine.getInventory should contain("brass lantern")
    val toolResults = engine.getMessages.collect { case t: ToolMessage => t }
    toolResults.map(_.toolCallId) shouldBe Vector("call-1")
    toolResults.head.content should include("brass lantern")
  }

  test("a save that ends mid-turn (a tool call without its result) is cut back and still plays") {
    val original = newEngine("midturn-save")
    original.initialize().isRight shouldBe true
    val saved = original.getGameState("game-1", None, None)
    val dangling =
      AssistantMessage(contentOpt = None, toolCalls = Seq(ToolCall("call-x", "list_inventory", ujson.Obj())))
    val broken = saved.copy(agentMessages = saved.agentMessages :+ UserMessage("look") :+ dangling)

    val restored = newEngine("midturn-restore")
    restored.restoreGameState(broken)
    restored.getMessages shouldBe original.getMessages.toVector :+ UserMessage("look")

    restored.processCommand("examine wall", generateAudio = false).isRight shouldBe true
  }
}
