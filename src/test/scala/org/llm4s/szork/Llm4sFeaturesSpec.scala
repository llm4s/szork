package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.ConfigReader
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.llm4s.szork.api.{AgentTracing, SpeechConfig}
import org.llm4s.szork.error.{ConfigurationError, ValidationError}
import org.llm4s.szork.game.{GameConstants, GameEngine}
import org.llm4s.szork.media.{SpeechToText, TextToSpeech}
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}

/** The llm4s features Szork uses beyond the agent loop: step limits, tracing and speech (issue #29). */
class Llm4sFeaturesSpec extends AnyFunSuite with Matchers {

  private final case class Reader(values: Map[String, String]) extends ConfigReader {
    def get(key: String): Option[String] = values.get(key)
  }

  test("tracing is off unless TRACING_MODE names a backend") {
    AgentTracing.create(Map.empty) shouldBe None
    AgentTracing.create(Map("TRACING_MODE" -> "none")) shouldBe None
    AgentTracing.create(Map("TRACING_MODE" -> "console")) shouldBe defined
  }

  test("a traced Dungeon Master plays normally") {
    val engine =
      GameEngine.create(ScriptedDm(), "traced", tracing = AgentTracing.create(Map("TRACING_MODE" -> "console")))
    engine.initialize().isRight shouldBe true
    engine.processCommand("go north", generateAudio = false).toOption.get.text should include("small hall")
  }

  test("a model stuck calling tools is stopped at the step limit, and the game carries on") {
    // Keeps calling a tool for as long as the latest player command is "loop"
    val looping = ScriptedDm {
      case c if ScriptedDm.lastUserText(c) == "loop" => Reply.toolCall("list_inventory")
    }
    val engine = GameEngine.create(looping, "looping")
    engine.initialize().isRight shouldBe true

    val stuck = engine.processCommand("loop", generateAudio = false)
    stuck.left.toOption.get.message should include("StepLimitReached")
    looping.calls.count(_.lastUserText.contains("loop")) shouldBe GameEngine.MaxModelCallsPerTurn

    engine.processCommand("look", generateAudio = false).isRight shouldBe true
  }

  test("speech clients report a missing key as an error instead of throwing") {
    TextToSpeech.create(SpeechConfig(), Reader(Map.empty)).left.toOption.get shouldBe a[ConfigurationError]
    SpeechToText.create(SpeechConfig(), Reader(Map.empty)).left.toOption.get shouldBe a[ConfigurationError]
    val stt = SpeechToText.create(SpeechConfig(), Reader(Map("OPENAI_API_KEY" -> "sk-test"))).toOption.get
    stt.transcribeBytes(Array.emptyByteArray).left.toOption.get shouldBe a[ValidationError]
  }

  test("the narration voice and models are configurable") {
    val key = Reader(Map("OPENAI_API_KEY" -> "sk-test"))
    val onyx = SpeechConfig.load(Reader(Map("SZORK_TTS_VOICE" -> "onyx", "SZORK_TTS_MODEL" -> "tts-1-hd")))
    onyx shouldBe SpeechConfig(ttsModel = "tts-1-hd", ttsVoice = "onyx")
    TextToSpeech.create(onyx, key).toOption.get.defaultVoice shouldBe "onyx"
    TextToSpeech.create(SpeechConfig.load(Reader(Map.empty)), key).toOption.get.defaultVoice shouldBe "nova"
  }

  private val Prose = "You look around the dusty room. Nothing happens."

  /** Answers "test" with prose that is not the output schema, and - once the request holds that prose, as llm4s's
    * correction request does - with a valid answer.
    */
  private def correctingLLM(): ScriptedLLMClient = ScriptedLLMClient.respondingTo {
    case c if c.messages.exists(_.content.contains(Prose)) =>
      Reply.text(DmAnswers.simple("The dust settles.", "start", "look"))
    case c if ScriptedDm.lastUserText(c) == GameConstants.Prompts.INIT_ADVENTURE =>
      Reply.text(DmAnswers.scene("Start.", "start", "Start", Seq("north" -> "hall")))
    case _ => Reply.text(Prose)
  }

  test("an answer that is not the output schema is corrected by the model, and the corrected answer is used") {
    val llm = correctingLLM()
    val engine = GameEngine.create(llm, "correcting")
    engine.initialize().isRight shouldBe true

    engine.processCommand("test", generateAudio = false).toOption.get.text shouldBe "The dust settles."
    // the turn took two calls: the prose, then the correction
    llm.calls.count(!_.lastUserText.contains(GameConstants.Prompts.INIT_ADVENTURE)) shouldBe 2
    // The correction request tells the model what was wrong
    llm.lastCall.get.messages.map(_.content).mkString should include("You look around the dusty room")
  }

  test("a streamed turn ends with the corrected narration") {
    val llm = correctingLLM()
    val engine = GameEngine.create(llm, "correcting-stream")
    engine.initialize().isRight shouldBe true

    val response = engine.processCommand("test", generateAudio = false, onTextChunk = Some(_ => ()))
    response.toOption.get.text shouldBe "The dust settles."
  }

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
    results("read-2") should include("door")
    results("read-2") should not include RepeatedToolCallMiddleware.Reread
    results("read-3") should include(RepeatedToolCallMiddleware.Reread)
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
