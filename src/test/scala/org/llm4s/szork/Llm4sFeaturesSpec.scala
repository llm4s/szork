package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.szork.api.AgentTracing
import org.llm4s.szork.error.{ConfigurationError, ValidationError}
import org.llm4s.szork.game.GameEngine
import org.llm4s.szork.media.{SpeechToText, TextToSpeech}
import org.llm4s.szork.stubs.FakeLLMClient

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
      GameEngine.create(new FakeLLMClient(), "traced", tracing = AgentTracing.create(Map("TRACING_MODE" -> "console")))
    engine.initialize().isRight shouldBe true
    engine.processCommand("go north", generateAudio = false).toOption.get.text should include("small hall")
  }

  test("a model stuck calling tools is stopped at the step limit, and the game carries on") {
    var calls = 0
    val looping = new FakeLLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        // Keeps calling a tool for as long as the latest player command is "loop"
        conversation.messages.reverse.collectFirst { case u: UserMessage => u.content } match {
          case Some("loop") =>
            calls += 1
            val call = ToolCall(s"call-$calls", "list_inventory", ujson.Obj())
            Right(
              Completion(
                id = s"loop-$calls",
                created = java.time.Instant.now(),
                content = "",
                model = "fake-llm",
                message = AssistantMessage(contentOpt = None, toolCalls = Seq(call)),
                toolCalls = List(call)
              ))
          case _ => super.complete(conversation, options)
        }
    }
    val engine = GameEngine.create(looping, "looping")
    engine.initialize().isRight shouldBe true

    val stuck = engine.processCommand("loop", generateAudio = false)
    stuck.left.toOption.get.message should include("StepLimitReached")
    calls shouldBe GameEngine.MaxModelCallsPerTurn

    engine.processCommand("look", generateAudio = false).isRight shouldBe true
  }

  test("speech clients report a missing key as an error instead of throwing") {
    TextToSpeech.create(Reader(Map.empty)).left.toOption.get shouldBe a[ConfigurationError]
    SpeechToText.create(Reader(Map.empty)).left.toOption.get shouldBe a[ConfigurationError]
    val stt = SpeechToText.create(Reader(Map("OPENAI_API_KEY" -> "sk-test"))).toOption.get
    stt.transcribeBytes(Array.emptyByteArray).left.toOption.get shouldBe a[ValidationError]
  }

  test("the narration voice and models are configurable") {
    TextToSpeech
      .create(Reader(Map("OPENAI_API_KEY" -> "sk-test", "SZORK_TTS_VOICE" -> "onyx")))
      .toOption
      .get
      .defaultVoice shouldBe "onyx"
    TextToSpeech.create(Reader(Map("OPENAI_API_KEY" -> "sk-test"))).toOption.get.defaultVoice shouldBe "nova"
  }

  /** Answers "test" first with prose that is not the output schema, then - when llm4s asks it to correct that - with a
    * valid answer.
    */
  private class CorrectingLLM extends FakeLLMClient {
    @volatile var answers = 0
    @volatile var lastRequest: Seq[Message] = Nil
    private def answer(conversation: Conversation): Completion = {
      val inTestTurn =
        conversation.messages.reverse.collectFirst { case u: UserMessage => u.content }.exists(_ != "Start adventure")
      lastRequest = conversation.messages
      val content =
        if (!inTestTurn) org.llm4s.szork.stubs.DmAnswers.scene("Start.", "start", "Start", Seq("north" -> "hall"))
        else {
          answers += 1
          if (answers == 1) "You look around the dusty room. Nothing happens."
          else org.llm4s.szork.stubs.DmAnswers.simple("The dust settles.", "start", "look")
        }
      Completion(
        id = s"c-$answers",
        created = java.time.Instant.now(),
        content = content,
        model = "fake-llm",
        message = AssistantMessage(content))
    }
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(answer(conversation))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit): Result[Completion] = {
      val completion = answer(conversation)
      onChunk(StreamedChunk(completion.id, Some(completion.content)))
      Right(completion)
    }
  }

  test("an answer that is not the output schema is corrected by the model, and the corrected answer is used") {
    val llm = new CorrectingLLM
    val engine = GameEngine.create(llm, "correcting")
    engine.initialize().isRight shouldBe true

    engine.processCommand("test", generateAudio = false).toOption.get.text shouldBe "The dust settles."
    llm.answers shouldBe 2
    // The correction request tells the model what was wrong
    llm.lastRequest.map(_.content).mkString should include("You look around the dusty room")
  }

  test("a streamed turn ends with the corrected narration") {
    val llm = new CorrectingLLM
    val engine = GameEngine.create(llm, "correcting-stream")
    engine.initialize().isRight shouldBe true

    val response = engine.processCommandStreaming("test", _ => (), generateAudio = false)
    response.toOption.get.text shouldBe "The dust settles."
  }
}
