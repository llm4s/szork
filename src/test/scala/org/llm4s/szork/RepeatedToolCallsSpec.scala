package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.testkit.Reply
import org.llm4s.szork.game.GameEngine
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}

/** The Dungeon Master's tool-call middleware (`RepeatedToolCalls.scala`, llm4s `wrapToolCall` and `wrapModelCall`): a
  * call repeated with nothing changed is not run again, and a turn that keeps calling tools is offered none.
  */
class RepeatedToolCallsSpec extends AnyFunSuite with Matchers {

  test("a tool call repeated within a turn is not run again, and the model is told to answer") {
    import org.llm4s.szork.game.RepeatedToolCallMiddleware
    // Records the lamp again and again until it is told the call was a repeat
    val repeating = ScriptedDm {
      case c if c.messages.exists(_.content.contains(RepeatedToolCallMiddleware.Repeated)) =>
        Reply.text(DmAnswers.simple("You take the lamp.", "cavern_entrance", "take"))
      case c if ScriptedDm.lastUserText(c) == "take lamp" =>
        Reply.toolCall("add_inventory_item", """{"item": "lamp"}""")
    }
    val engine = GameEngine.create(repeating, "repeating")
    engine.initialize().isRight shouldBe true

    engine.processCommand("take lamp", generateAudio = false).toOption.get.text shouldBe "You take the lamp."
    engine.getInventory shouldBe List("lamp")
    // the call, its repeat, and the answer
    repeating.calls.count(_.lastUserText.contains("take lamp")) shouldBe 3

    // the next turn may make the same call again
    engine.processCommand("take lamp", generateAudio = false).isRight shouldBe true
  }

  test("a turn that keeps looking things up is given no more tools, and answers") {
    import org.llm4s.szork.game.ToolRoundsMiddleware
    // Takes the lamp, then looks up one location after another for as long as it is offered tools
    val dithering = ScriptedDm {
      case c if ScriptedDm.lastUserText(c) == "take lamp" =>
        val round = c.messages.reverseIterator.takeWhile(m => !m.content.contains("take lamp")).size
        if (round == 0) Reply.toolCall("add_inventory_item", """{"item": "lamp"}""")
        else if (round < ToolRoundsMiddleware.MaxRounds * 2)
          Reply.toolCall("get_location_state", s"""{"location_id": "room_$round"}""")
        else Reply.text(DmAnswers.simple("You take the lamp.", "cavern_entrance", "take"))
    }
    val engine = GameEngine.create(dithering, "dithering")
    engine.initialize().isRight shouldBe true

    engine.processCommand("take lamp", generateAudio = false).toOption.get.text shouldBe "You take the lamp."
    engine.getInventory shouldBe List("lamp")
    // the rounds that had tools, then the call that had none
    val turn = dithering.calls.filter(_.lastUserText.contains("take lamp"))
    turn.map(_.options.tools.nonEmpty) shouldBe List.fill(ToolRoundsMiddleware.MaxRounds)(true) :+ false
    engine.lastTurnUsage.map(_.modelCalls) shouldBe Some(ToolRoundsMiddleware.MaxRounds + 1L)

    // the next turn has its tools again
    engine.processCommand("take lamp", generateAudio = false).isRight shouldBe true
    val next = dithering.calls.takeRight(ToolRoundsMiddleware.MaxRounds + 1)
    next.map(_.options.tools.nonEmpty) shouldBe List.fill(ToolRoundsMiddleware.MaxRounds)(true) :+ false
  }

  test("a call repeated after the game changed runs again; a read repeated with nothing changed is not run") {
    import org.llm4s.llmconnect.model.{ToolMessage, UserMessage}
    import org.llm4s.szork.game.RepeatedToolCallMiddleware
    val steps = Vector(
      "get_location_state" -> "{}",
      "set_location_flag" -> """{"flag": "door", "value": "open"}""",
      "get_location_state" -> "{}", // the location changed since the first read: it runs
      "get_location_state" -> "{}" // nothing changed since: not run
    )
    def done(c: org.llm4s.llmconnect.model.Conversation): Int =
      c.messages.reverseIterator.takeWhile(!_.isInstanceOf[UserMessage]).count(_.isInstanceOf[ToolMessage])
    val llm = ScriptedDm {
      case c if ScriptedDm.lastUserText(c) == "check the door" && done(c) < 2 =>
        val (tool, args) = steps(done(c))
        Reply.toolCall(tool, args)
      case c if ScriptedDm.lastUserText(c) == "check the door" && done(c) == 2 =>
        Reply.toolCalls(
          org.llm4s.llmconnect.model
            .ToolCall(org.llm4s.llmconnect.model.ToolCallId("read-2"), steps(2)._1, ujson.Obj()),
          org.llm4s.llmconnect.model.ToolCall(org.llm4s.llmconnect.model.ToolCallId("read-3"), steps(3)._1, ujson.Obj())
        )
      case c if ScriptedDm.lastUserText(c) == "check the door" =>
        Reply.text(DmAnswers.simple("The door stands open.", "cavern_entrance", "examine"))
    }
    val engine = GameEngine.create(llm, "rereading")
    engine.initialize().isRight shouldBe true

    engine.processCommand("check the door", generateAudio = false).isRight shouldBe true
    val results = engine.getMessages.collect { case t: ToolMessage => t.toolCallId.asString -> t.content }.toMap
    // read-2 and read-3 come in one response, whose tool calls llm4s runs concurrently: whichever reaches the
    // middleware first runs (the location changed since the first read), and the other is refused as a repeat
    val (refused, ran) =
      Seq(results("read-2"), results("read-3")).partition(_.contains(RepeatedToolCallMiddleware.Reread))
    refused.size shouldBe 1
    ran.size shouldBe 1
    ran.head should include("door")
  }

  test("setting something back to how it was earlier in the turn runs") {
    val flags = Vector("open", "shut", "open")
    val llm = ScriptedDm {
      case c if ScriptedDm.lastUserText(c) == "fiddle with the door" =>
        val done = c.messages.reverseIterator
          .takeWhile(!_.isInstanceOf[org.llm4s.llmconnect.model.UserMessage])
          .count(_.isInstanceOf[org.llm4s.llmconnect.model.ToolMessage])
        if (done < flags.size) Reply.toolCall("set_location_flag", s"""{"flag": "door", "value": "${flags(done)}"}""")
        else Reply.text(DmAnswers.simple("You leave the door open.", "cavern_entrance", "fiddle"))
    }
    val engine = GameEngine.create(llm, "setting-back")
    engine.initialize().isRight shouldBe true

    engine.processCommand("fiddle with the door", generateAudio = false).isRight shouldBe true
    engine.getWorldState.location(None).get.flags shouldBe Map("door" -> "open")
  }
}
