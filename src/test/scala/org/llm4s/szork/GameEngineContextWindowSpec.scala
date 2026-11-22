package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.agent.ContextWindowConfig
import org.llm4s.config.ConfigReader
import org.llm4s.context.ConversationTokenCounter
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.szork.api.SzorkConfig
import org.llm4s.szork.game._
import org.llm4s.szork.stubs.FakeLLMClient

/** Covers the Dungeon Master's context window: llm4s's ContextWindowMiddleware trims what each model call is sent,
  * while the game's thread (and its saves) keep the whole conversation.
  */
class GameEngineContextWindowSpec extends AnyFunSuite with Matchers {

  private val outline = AdventureOutline(
    title = "The Sunken Archive",
    mainQuest = "Recover the drowned ledger",
    subQuests = List("Light the lamps"),
    keyLocations = List(LocationOutline("cavern_entrance", "Cavern Entrance", "A damp mouth of rock", "Start")),
    importantItems = List(ItemOutline("Lantern", "a brass lantern", "light")),
    keyCharacters = List(CharacterOutline("Archivist", "guide", "patient")),
    adventureArc = "Descend / Search / Escape"
  )

  /** Answers like FakeLLMClient, records every request, and on "take <item>" first calls add_inventory_item. */
  private class RecordingLLM extends FakeLLMClient {
    @volatile var requests: Vector[Vector[Message]] = Vector.empty
    private val calls = new java.util.concurrent.atomic.AtomicInteger()

    private def toolCallFor(conversation: Conversation): Option[Completion] =
      conversation.messages.lastOption.collect {
        case u: UserMessage if u.content.startsWith("take ") =>
          val call =
            ToolCall(s"call-${calls.incrementAndGet()}", "add_inventory_item", ujson.Obj("item" -> u.content.drop(5)))
          Completion(
            id = call.id,
            created = java.time.Instant.now(),
            content = "",
            model = "fake-llm",
            message = AssistantMessage(contentOpt = None, toolCalls = Seq(call)),
            toolCalls = List(call)
          )
      }

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      requests :+= conversation.messages.toVector
      toolCallFor(conversation).map(Right(_)).getOrElse(super.complete(conversation, options))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit): Result[Completion] = {
      requests :+= conversation.messages.toVector
      toolCallFor(conversation).map(Right(_)).getOrElse(super.streamComplete(conversation, options, onChunk))
    }
  }

  private def newEngine(llm: RecordingLLM, window: ContextWindowConfig, id: String = "window-test"): GameEngine =
    GameEngine.create(
      llmClient = llm,
      sessionId = id,
      theme = Some("underground realm"),
      adventureOutline = Some(outline),
      contextWindow = Some(window))

  private val commands = Vector("look", "take lantern", "go north", "examine wall", "take rope", "look")

  /** Plays `turns` commands after the opening, every third one streamed; each must answer in the structured format. */
  private def play(engine: GameEngine, turns: Int, from: Int = 0): Seq[String] =
    (from until from + turns).map { i =>
      val command = commands(i % commands.length) match {
        case take if take.startsWith("take") => s"$take $i"
        case other => other
      }
      val result =
        if (i % 3 == 0) engine.processCommand(command, generateAudio = false, onTextChunk = Some(_ => ()))
        else engine.processCommand(command, generateAudio = false)
      withClue(s"turn $i ($command): ") {
        result.isRight shouldBe true
        engine.popValidationIssues() shouldBe None
      }
      command
    }

  private def conversation(request: Vector[Message]): Vector[Message] = request.filterNot(_.isInstanceOf[SystemMessage])

  /** A request sends every tool call with its results, and every result with its call. */
  private def toolPairsIntact(request: Vector[Message]): Boolean = {
    val callIds = request.collect { case a: AssistantMessage => a.toolCalls.map(_.id) }.flatten.toSet
    val resultIds = request.collect { case t: ToolMessage => t.toolCallId }.toSet
    callIds == resultIds
  }

  test("a long game sends at most maxMessages of conversation per model call, and keeps its whole history") {
    val llm = new RecordingLLM
    val engine = newEngine(llm, ContextWindowConfig(maxMessages = Some(8)))
    engine.initialize().isRight shouldBe true
    val played = play(engine, 40)

    llm.requests.size should be > 40
    llm.requests.foreach { request =>
      // the system prompt, with the adventure outline, goes first in every call and is not counted
      request.head shouldBe a[SystemMessage]
      request.head.content should include("The Sunken Archive")
      request.count(_.isInstanceOf[SystemMessage]) shouldBe 1
      conversation(request).size should be <= 8
      conversation(request).head shouldBe a[UserMessage]
    }
    // the latest call holds the latest command
    conversation(llm.requests.last).collect { case u: UserMessage => u.content }.last shouldBe played.last

    // the thread is not pruned: every command is still in the history
    engine.getMessages.collect { case u: UserMessage =>
      u.content
    } shouldBe
      (GameConstants.Prompts.INIT_ADVENTURE +: played.toVector)
    engine.getMessages.size should be > 40 * 2
    engine.getCurrentScene.map(_.locationId) shouldBe Some("hall") // the last move was "go north"
  }

  test("a token limit keeps each call's conversation within maxTokens, counted with cl100k_base") {
    val counter = ConversationTokenCounter.openAI().fold(e => fail(e.message), identity)
    val llm = new RecordingLLM
    val maxTokens = 600
    val engine = newEngine(llm, ContextWindowConfig(maxTokens = Some(maxTokens)))
    engine.initialize().isRight shouldBe true
    play(engine, 30)

    val sentTokens = llm.requests.map(request => conversation(request).map(counter.countMessage).sum)
    sentTokens.foreach(_ should be <= maxTokens)
    // pruning happened: the whole history is well over the limit, yet more than the current turn was sent
    engine.getMessages.map(counter.countMessage).sum should be > maxTokens * 5
    llm.requests
      .takeRight(10)
      .foreach(request => conversation(request).count(_.isInstanceOf[UserMessage]) should be > 1)
  }

  test("pruning never sends an inventory tool call without its result, whatever the limit") {
    (3 to 9).foreach { limit =>
      val llm = new RecordingLLM
      val engine = newEngine(llm, ContextWindowConfig(maxMessages = Some(limit)), s"tools-$limit")
      engine.initialize().isRight shouldBe true
      val played = play(engine, 24)

      withClue(s"maxMessages=$limit: ") {
        llm.requests.foreach(request => toolPairsIntact(request) shouldBe true)
        // the tools ran on every take
        engine.getInventory should contain theSameElementsAs played.filter(_.startsWith("take ")).map(_.drop(5))
        // and the history holds every pair
        toolPairsIntact(engine.getMessages) shouldBe true
      }
    }
    // with room for it, an earlier turn's tool call and result are sent together
    val llm = new RecordingLLM
    val engine = newEngine(llm, ContextWindowConfig(maxMessages = Some(9)))
    engine.initialize().isRight shouldBe true
    play(engine, 24)
    llm.requests.exists { request =>
      val lastUser = request.lastIndexWhere(_.isInstanceOf[UserMessage])
      request.take(lastUser).exists(_.isInstanceOf[ToolMessage])
    } shouldBe true
  }

  test("a save holds the whole conversation, and a restored game prunes it again") {
    val window = ContextWindowConfig(maxMessages = Some(6))
    val original = newEngine(new RecordingLLM, window, "save")
    original.initialize().isRight shouldBe true
    play(original, 20)
    val saved = original.getGameState("game-1", None, None)
    saved.agentMessages shouldBe original.getMessages.toList

    val llm = new RecordingLLM
    val restored = newEngine(llm, window, "restore")
    restored.restoreGameState(saved)
    restored.getMessages shouldBe original.getMessages
    restored.getInventory shouldBe original.getInventory

    play(restored, 3, from = 20)
    llm.requests.foreach { request =>
      conversation(request).size should be <= 6
      request.head.content should include("The Sunken Archive")
      toolPairsIntact(request) shouldBe true
    }
    restored.getMessages.take(original.getMessages.size) shouldBe original.getMessages
    restored.getMessages.collect { case u: UserMessage => u.content }.size shouldBe
      original.getMessages.collect { case u: UserMessage => u.content }.size + 3
  }

  test("a token limit is capped to fit the model's context window") {
    val llm = new FakeLLMClient // 8000-token window, 1000 reserved for the completion
    GameEngine.contextWindowFor(Some(ContextWindowConfig(maxTokens = Some(24000))), llm).flatMap(_.maxTokens) shouldBe
      Some(3500)
    GameEngine.contextWindowFor(Some(ContextWindowConfig(maxTokens = Some(2000))), llm).flatMap(_.maxTokens) shouldBe
      Some(2000)
    GameEngine.contextWindowFor(Some(ContextWindowConfig(maxMessages = Some(10))), llm) shouldBe
      Some(ContextWindowConfig(maxMessages = Some(10)))
    GameEngine.contextWindowFor(None, llm) shouldBe None
  }

  private final case class FakeReader(values: Map[String, String]) extends ConfigReader {
    override def get(key: String): Option[String] = values.get(key)
  }

  test("the context window is configured from SZORK_DM_CONTEXT_MAX_TOKENS and SZORK_DM_CONTEXT_MAX_MESSAGES") {
    def load(env: (String, String)*) = SzorkConfig.loadDmContextWindow(FakeReader(env.toMap))
    load() shouldBe Some(ContextWindowConfig(maxTokens = Some(GameEngine.DefaultContextTokens)))
    load("SZORK_DM_CONTEXT_MAX_TOKENS" -> "8000") shouldBe Some(ContextWindowConfig(maxTokens = Some(8000)))
    load("SZORK_DM_CONTEXT_MAX_TOKENS" -> "0") shouldBe None
    load("SZORK_DM_CONTEXT_MAX_TOKENS" -> "off") shouldBe None
    load("SZORK_DM_CONTEXT_MAX_TOKENS" -> "lots") shouldBe load()
    load("SZORK_DM_CONTEXT_MAX_MESSAGES" -> "40") shouldBe Some(ContextWindowConfig(maxMessages = Some(40)))
    load("SZORK_DM_CONTEXT_MAX_MESSAGES" -> "40", "SZORK_DM_CONTEXT_MAX_TOKENS" -> "8000") shouldBe
      Some(ContextWindowConfig(maxMessages = Some(40)))
  }
}
