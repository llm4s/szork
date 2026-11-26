package org.llm4s.szork

import scala.collection.mutable.ListBuffer

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.config.ConfigReader
import org.llm4s.error.LLMError
import org.llm4s.trace.{NoOpTracing, TraceEvent}
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.llm4s.szork.api.{AgentTracing, SpeechConfig}
import org.llm4s.szork.error.{ConfigurationError, ValidationError}
import org.llm4s.szork.game.{GameConstants, GameEngine}
import org.llm4s.szork.media.{SpeechToText, TextToSpeech}
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}

/** The llm4s features Szork uses beyond the agent loop: step limits, tracing, structured output's correction of an
  * invalid answer, and speech (issue #29). The other middleware have their own specs (`RepeatedToolCallsSpec`,
  * `ExitGuardSpec`, `WorldStateMiddlewareSpec`).
  */
class Llm4sFeaturesSpec extends AnyFunSuite with Matchers {

  private final case class Reader(values: Map[String, String]) extends ConfigReader {
    def get(key: String): Option[String] = values.get(key)
  }

  test("tracing is off unless TRACING_MODE names a backend") {
    AgentTracing.create(Map.empty) shouldBe None
    AgentTracing.create(Map("TRACING_MODE" -> "none")) shouldBe None
    AgentTracing.create(Map("TRACING_MODE" -> "console")) shouldBe defined
  }

  test("each of the Dungeon Master's turns, streamed or not, is traced as a completed run") {
    val events = ListBuffer.empty[TraceEvent]
    val tracing = new NoOpTracing {
      override def traceEvent(event: TraceEvent): Either[LLMError, Unit] = { events += event; Right(()) }
    }
    val engine = GameEngine.create(ScriptedDm(), "traced", tracing = Some(tracing))
    engine.initialize().isRight shouldBe true
    engine.processCommand("go north", generateAudio = false, onTextChunk = Some(_ => ())).isRight shouldBe true

    val runs = events.collect { case e: TraceEvent.AgentRunEnded => e }.toList
    runs.map(r => (r.agent, r.status.name)) shouldBe List.fill(2)((GameEngine.AgentId, "completed"))
    runs.map(_.threadId).distinct should have size 1 // one game, one thread
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
}
