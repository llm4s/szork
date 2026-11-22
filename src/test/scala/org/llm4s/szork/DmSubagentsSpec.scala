package org.llm4s.szork

import org.llm4s.config.ConfigReader
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.{Conversation, SystemMessage, TokenUsage, ToolCall, ToolMessage}
import org.llm4s.szork.api.{EngineFactory, LLMConfig, SubagentConfig, SzorkConfig}
import org.llm4s.szork.game._
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}
import org.llm4s.testkit.{RecordedCall, Reply, ScriptedLLMClient}
import org.llm4s.trace.{NoOpTracing, TraceEvent}
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/** The Dungeon Master's subagents (llm4s delegation): NPC dialogue and hints. */
class DmSubagentsSpec extends AnyFunSuite with Matchers with LoneElement {

  private val outline = AdventureOutline(
    title = "The Whispering Cavern",
    mainQuest = "Find the lost lantern of the deep",
    subQuests = List("Answer the hermit's riddle"),
    keyLocations = List(
      LocationOutline(
        "cavern_entrance",
        "Cavern Entrance",
        "A cave mouth",
        "The start",
        puzzles = Some("The hermit's riddle opens the hidden stair"),
        secrets = Some("A hidden stair behind the waterfall")
      ),
      LocationOutline("hall", "Small Hall", "A small hall", "Where the lantern lies")
    ),
    importantItems = List(ItemOutline("lantern", "A dim lantern", "Lights the deep")),
    keyCharacters = List(
      CharacterOutline("Old Hermit", "keeper of the cavern", "A wiry hermit who speaks in riddles"),
      CharacterOutline("Mara", "lost diver", "A cheerful diver looking for a way out")
    ),
    adventureArc = "Descend and return"
  )

  private val npcSpeech = "Answer my riddle first, traveller."
  private val npcReport = ujson.Obj("speech" -> npcSpeech, "attitude" -> "wary", "revealsSomething" -> false).render()
  private val hintText = "The hermit's riddles hide more than wordplay."

  private def systemText(c: Conversation): String =
    c.messages.collectFirst { case s: SystemMessage => s.content }.getOrElse("")

  private def isNpcTask(c: Conversation) = systemText(c).contains(DmSubagents.NpcPrompt.linesIterator.next())
  private def isHintTask(c: Conversation) = systemText(c).contains(DmSubagents.HintPrompt.linesIterator.next())
  private def isDm(c: RecordedCall) = systemText(c.conversation).contains("You are a Dungeon Master")

  /** The content of a conversation's last message when it is a tool result. */
  private object LastToolResult {
    def unapply(c: Conversation): Option[String] = c.messages.lastOption.collect { case t: ToolMessage => t.content }
  }

  private def json(text: String): Option[ujson.Value] = scala.util.Try(ujson.read(text)).toOption

  private def talkCall(who: String, id: String = "talk-1") =
    ToolCall(
      id,
      DmSubagents.NpcDialogueTool,
      ujson.Obj("character" -> who, "playerSays" -> "Hello there", "situation" -> "By the cave mouth."))

  /** The cavern's Dungeon Master with subagents: it delegates "talk to X" and "hint", and weaves each report into its
    * answer; the subagents answer as `npc` and `hint` say.
    */
  private def scripted(
    npc: Reply = Reply.text(npcReport),
    hint: Reply = Reply.text(ujson.Obj("hint" -> hintText).render()),
    rules: PartialFunction[Conversation, Reply] = PartialFunction.empty
  ): ScriptedLLMClient =
    ScriptedDm(rules.orElse {
      case c if isNpcTask(c) => npc
      case c if isHintTask(c) => hint
      case ScriptedDm.Command(s"talk to $who") => Reply.toolCalls(talkCall(who))
      case ScriptedDm.Command("hint") =>
        Reply.toolCall(DmSubagents.HintTool, ujson.Obj("question" -> "hint").render())
      case ScriptedDm.Command(s"greet $who") =>
        Reply.toolCall(
          "record_npc_met",
          ujson.Obj("name" -> "Old Hermit", "note" -> "promised to show the hidden stair").render())
      case LastToolResult(result) if json(result).exists(_.obj.contains("speech")) =>
        Reply.text(
          DmAnswers
            .simple(s"The hermit eyes you and says: ${json(result).get("speech").str}", "cavern_entrance", "talk"))
      case LastToolResult(result) if json(result).exists(_.obj.contains("hint")) =>
        Reply.text(DmAnswers.simple(s"A voice whispers: ${json(result).get("hint").str}", "cavern_entrance", "hint"))
      case LastToolResult(result) if result.contains("error") =>
        Reply.text(DmAnswers.simple("The hermit says nothing.", "cavern_entrance", "talk"))
    })

  private def engineWith(
    llm: ScriptedLLMClient,
    subagents: SubagentSettings = SubagentSettings.default,
    tracing: Option[NoOpTracing] = None
  ): GameEngine =
    GameEngine.create(
      llmClient = llm,
      sessionId = "subagents",
      adventureOutline = Some(outline),
      subagents = subagents,
      tracing = tracing)

  private def npcCalls(llm: ScriptedLLMClient) = llm.calls.filter(c => isNpcTask(c.conversation))
  private def hintCalls(llm: ScriptedLLMClient) = llm.calls.filter(c => isHintTask(c.conversation))
  private def toolNames(call: RecordedCall): Seq[String] = call.options.tools.map(_.name)

  test("talking to a character delegates to the NPC subagent with that character's persona and memory") {
    val llm = scripted()
    val engine = engineWith(llm)
    engine.initialize().isRight shouldBe true
    engine.processCommand("greet the hermit", generateAudio = false).isRight shouldBe true

    val response = engine.processCommand("talk to the hermit", generateAudio = false)

    response.map(_.text) shouldBe Right(s"The hermit eyes you and says: $npcSpeech")
    val brief = npcCalls(llm).loneElement.lastUserText.get
    brief should include("You are Old Hermit, keeper of the cavern.")
    brief should include("speaks in riddles")
    brief should include("promised to show the hidden stair") // the record_npc_met note
    brief should include("By the cave mouth.")
    brief should include("\"Hello there\"")
    brief should not include "Mara"
    // the child has a fresh conversation: none of the game's
    npcCalls(llm).head.messages.map(_.content).mkString should not include GameConstants.Prompts.INIT_ADVENTURE
    // only the typed report comes back into the game's conversation
    engine.getMessages.collect { case t: ToolMessage => t.content }.last should include(npcSpeech)
  }

  test("a character not in the outline is voiced as a minor character") {
    val brief = DmSubagents.npcBrief(
      NpcDialogueRequest("ferryman", "Take me across", "At the river."),
      Some(outline),
      WorldSnapshot.fromOutline(Some(outline)))
    brief should include("You are ferryman, a minor character")
    brief should include("You have not spoken with the player before.")
    DmSubagents.character(Some(outline), "the hermit").map(_.name) shouldBe Some("Old Hermit")
    DmSubagents.character(Some(outline), "MARA").map(_.name) shouldBe Some("Mara")
    DmSubagents.character(Some(outline), "  ") shouldBe None
  }

  test("asking for a hint delegates to the hint subagent with the outline and the player's progress") {
    val llm = scripted()
    val engine = engineWith(llm)
    engine.initialize().isRight shouldBe true

    val response = engine.processCommand("hint", generateAudio = false)

    response.map(_.text) shouldBe Right(s"A voice whispers: $hintText")
    val brief = hintCalls(llm).loneElement.lastUserText.get
    brief should include("Main quest: Find the lost lantern of the deep")
    brief should include("main: Find the lost lantern of the deep - active")
    brief should include("Puzzle: The hermit's riddle opens the hidden stair")
    brief should include("Current location: Cavern Entrance")
    brief should include("Carrying: nothing")
    brief should include("The player asks: \"hint\"")
    npcCalls(llm) shouldBe empty
  }

  test("a streaming turn streams only the Dungeon Master's narration, never the subagent's output") {
    val llm = scripted()
    val engine = engineWith(llm)
    engine.initialize().isRight shouldBe true
    val chunks = ListBuffer.empty[String]

    val response = engine.processCommand("talk to the hermit", generateAudio = false, onTextChunk = Some(chunks += _))

    response.map(_.text) shouldBe Right(s"The hermit eyes you and says: $npcSpeech")
    chunks.mkString shouldBe response.toOption.get.text
    chunks.mkString should not include "wary" // the report's other fields never reach the player
    // the subagent's model call was not streamed; the Dungeon Master's were
    npcCalls(llm).loneElement.streamed shouldBe false
    llm.calls.filter(isDm).last.streamed shouldBe true
  }

  test("a subagent's run is traced as a child of the Dungeon Master's run") {
    val events = ListBuffer.empty[TraceEvent]
    val tracing = new NoOpTracing {
      override def traceEvent(event: TraceEvent): Either[LLMError, Unit] = { events += event; Right(()) }
    }
    val engine = engineWith(scripted(), tracing = Some(tracing))
    engine.initialize().isRight shouldBe true
    engine.processCommand("talk to the hermit", generateAudio = false).isRight shouldBe true

    val runs = events.collect { case e: TraceEvent.AgentRunEnded => e }.toList
    val dmRuns = runs.filter(_.agent == GameEngine.AgentId)
    val child = runs.filter(_.agent == DmSubagents.NpcDialogueId).loneElement
    child.status shouldBe "completed"
    child.parentThreadId shouldBe Some(dmRuns.last.threadId)
    child.parentRunId shouldBe Some(dmRuns.last.runId)
  }

  test("a subagent that cannot answer gets one correction, and the Dungeon Master answers without it") {
    val llm = scripted(npc = Reply.text("not json at all"))
    val engine = engineWith(llm)
    engine.initialize().isRight shouldBe true

    val response = engine.processCommand("talk to the hermit", generateAudio = false)

    response.map(_.text) shouldBe Right("The hermit says nothing.")
    npcCalls(llm) should have size 2 // its one step: the answer and llm4s's one correction
  }

  test("a subagent over its token budget makes no further model call, and the Dungeon Master answers without it") {
    // llm4s checks the budget before each model call: an invalid answer that used up the budget gets no correction
    val tokens = DmSubagents.NpcMaxTokens.toInt + 1000
    val llm = scripted(npc = Reply.text("not json at all").withUsage(TokenUsage(tokens - 10, 10, tokens)))
    val engine = engineWith(llm)
    engine.initialize().isRight shouldBe true

    val response = engine.processCommand("talk to the hermit", generateAudio = false)

    response.map(_.text) shouldBe Right("The hermit says nothing.")
    npcCalls(llm) should have size 1
    engine.getMessages.collect { case t: ToolMessage => t.content }.last should include("token budget")
  }

  test("a turn delegates at most the configured number of tasks") {
    val calls = (1 to 4).map(i => talkCall("the hermit", s"talk-$i"))
    val llm = scripted(rules = { case ScriptedDm.Command("talk to everyone") => Reply.toolCalls(calls*) })
    val engine = engineWith(llm)
    engine.initialize().isRight shouldBe true

    engine.processCommand("talk to everyone", generateAudio = false).isRight shouldBe true

    npcCalls(llm) should have size DmSubagents.Delegation.maxTasksPerTurn.toLong
    val results = engine.getMessages.collect { case t: ToolMessage => t.content }.takeRight(4)
    results.count(_.contains(npcSpeech)) shouldBe DmSubagents.Delegation.maxTasksPerTurn
    results.count(_.contains("error")) shouldBe 1
  }

  test("each subagent has one step, a timeout and a token budget, and nothing of the Dungeon Master's") {
    val world = new WorldState()
    val agents = DmSubagents.forGame(SubagentSettings.default, () => Some(outline), world, new PlayerInventory())
    agents.map(_.id.value) shouldBe Vector(DmSubagents.NpcDialogueId, DmSubagents.HintId)
    agents.foreach { agent =>
      agent.inheritance.budget.maxSteps shouldBe Some(DmSubagents.MaxStepsPerTask)
      agent.inheritance.budget.timeout shouldBe Some(60.seconds)
      agent.inheritance.budget.maxTokens shouldBe defined
      agent.inheritance.delegate shouldBe false
    }
    DmSubagents.Delegation.maxDepth shouldBe 1
  }

  test("the Dungeon Master is offered a tool per enabled subagent, and none when they are off") {
    def firstCallTools(settings: SubagentSettings): Seq[String] = {
      val llm = scripted()
      engineWith(llm, settings).initialize().isRight shouldBe true
      toolNames(llm.calls.head)
    }
    firstCallTools(SubagentSettings.default) should contain allOf (DmSubagents.NpcDialogueTool, DmSubagents.HintTool)
    firstCallTools(SubagentSettings.none).filter(_.startsWith("task")) shouldBe empty
    firstCallTools(SubagentSettings(hints = false)).filter(_.startsWith("task")) shouldBe Seq(
      DmSubagents.NpcDialogueTool)

    val off = scripted()
    engineWith(off, SubagentSettings.none).initialize().isRight shouldBe true
    systemText(off.calls.head.conversation) should not include "YOUR SUBAGENTS"
  }

  test("the system prompt says when to delegate, only for the subagents offered") {
    val both = PromptBuilder.fullSystemPrompt(None, None, Some(outline), SubagentSettings.default)
    both should include(s"- ${DmSubagents.NpcDialogueTool}: when the player talks to a character")
    both should include(s"- ${DmSubagents.HintTool}: when the player asks for a hint")
    val npcOnly = PromptBuilder.fullSystemPrompt(None, None, Some(outline), SubagentSettings(hints = false))
    npcOnly should not include DmSubagents.HintTool
    val none = PromptBuilder.fullSystemPrompt(None, None, Some(outline))
    none should not include "task_"
    none should include("(record_npc_met aside)")
  }

  test("the subagents are configured from the environment, on by default") {
    final case class Env(values: Map[String, String]) extends ConfigReader {
      override def get(key: String): Option[String] = values.get(key)
    }
    SzorkConfig.load(Env(Map.empty)).subagents shouldBe SubagentConfig(npcDialogue = true, hints = true, model = None)
    SzorkConfig
      .load(
        Env(Map("SZORK_NPC_AGENT" -> "off", "SZORK_HINT_AGENT" -> "FALSE", "SZORK_SUBAGENT_MODEL" -> " gpt-4o-mini ")))
      .subagents shouldBe SubagentConfig(npcDialogue = false, hints = false, model = Some("gpt-4o-mini"))
    SzorkConfig.load(Env(Map("SZORK_SUBAGENT_MODEL" -> " "))).subagents.model shouldBe None
  }

  test("a subagent model gets its own client on the Dungeon Master's provider, else the Dungeon Master's is used") {
    val subagentClient = ScriptedLLMClient.always(Reply.text("{}"))
    val llm = Some(LLMConfig("openai", "key", Some("gpt-4o")))
    val asked = ListBuffer.empty[Option[String]]
    def clientFor(config: SzorkConfig) = {
      asked += config.llmConfig.flatMap(_.model)
      Right(subagentClient)
    }

    val cheaper = SzorkConfig(llmConfig = llm, subagents = SubagentConfig(hints = false, model = Some("gpt-4o-mini")))
    val settings = EngineFactory.subagentSettings(cheaper, clientFor)
    settings shouldBe SubagentSettings(npcDialogue = true, hints = false, client = Some(subagentClient))
    asked.toList shouldBe List(Some("gpt-4o-mini"))

    EngineFactory.subagentSettings(SzorkConfig(llmConfig = llm), clientFor).client shouldBe None
    EngineFactory
      .subagentSettings(cheaper, _ => Left("no such model"))
      .client shouldBe None
  }
}
