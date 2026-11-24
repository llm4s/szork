package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.agent.ContextWindowConfig
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{RecordedCall, Reply, ScriptedLLMClient}
import org.llm4s.szork.game._
import org.llm4s.szork.persistence.GameStateCodec
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}

/** Covers the current game state that [[WorldStateMiddleware]] puts into every Dungeon Master model call, as an llm4s
  * prompt section: present in every call, current after the turn's own tool calls, never stored in the thread or a
  * save, never pruned - and the fewer model calls a turn needs with it.
  */
class WorldStateMiddlewareSpec extends AnyFunSuite with Matchers {

  private val outline = AdventureOutline(
    title = "The Sunken Archive",
    mainQuest = "Recover the drowned ledger",
    subQuests = List("Light the lamps"),
    keyLocations = List(
      LocationOutline(
        "cavern_entrance",
        "Cavern Entrance",
        "A damp mouth of rock",
        "Start",
        exits = List(
          ExitOutline("north", "hall", "An iron gate", "locked", Some("a rusted padlock")),
          ExitOutline("east", "grotto", "A narrow crack", "hidden"))
      ),
      LocationOutline(
        "hall",
        "Small Hall",
        "A low hall",
        "The archive's antechamber",
        exits = List(ExitOutline("south", "cavern_entrance", "The iron gate", "locked", Some("a rusted padlock")))
      )
    ),
    importantItems = List(ItemOutline("Key", "an iron key", "opens the gate")),
    keyCharacters = List(
      CharacterOutline("Archivist", "guide", "patient", Some("hall")),
      CharacterOutline("Ferryman", "gatekeeper", "silent", Some("cavern_entrance")),
      CharacterOutline("The Founder", "legend", "long dead", None)
    ),
    adventureArc = "Descend / Search / Escape"
  )

  /** The system prompt a model call was sent. */
  private def systemText(messages: Seq[Message]): String =
    messages.collect { case s: SystemMessage => s.content }.mkString("\n")

  private def section(messages: Seq[Message]): String = {
    val system = systemText(messages)
    val start = system.indexOf(WorldStateMiddleware.Heading)
    if (start < 0) "" else system.substring(start)
  }

  /** A Dungeon Master that, like the prompt asks, reads the current game state instead of calling the read tools: on
    * "unlock gate" with the key held and the gate locked, it opens the gate and starts a side quest in one step (two
    * tool calls at once); without the key it answers straight away.
    */
  private val readsTheState: PartialFunction[Conversation, Reply] = {
    case c @ ScriptedDm.Command("unlock gate")
        if section(c.messages).contains("Inventory: iron key") &&
          section(c.messages).contains("- north -> hall: locked") =>
      Reply
        .toolCall("set_exit_state", """{"direction": "north", "state": "open"}""", id = Some("call-unlock"))
        .withToolCall("update_quest_progress", """{"quest": "side-1", "status": "active"}""", id = Some("call-quest"))
    case ScriptedDm.Command("unlock gate") =>
      Reply.text(DmAnswers.simple("You have nothing to unlock it with.", "cavern_entrance", "unlock"))
  }

  private def scripted(): ScriptedLLMClient = ScriptedDm(readsTheState.orElse(ScriptedDm.takesItems))

  private def newEngine(llm: ScriptedLLMClient, window: Option[ContextWindowConfig] = None): GameEngine =
    GameEngine.create(
      llmClient = llm,
      sessionId = "state-test",
      adventureOutline = Some(outline),
      contextWindow = window)

  private def play(engine: GameEngine, commands: String*): Unit = commands.foreach { command =>
    withClue(command)(engine.processCommand(command, generateAudio = false).isRight shouldBe true)
  }

  /** The model calls `body` made. */
  private def callsOf(llm: ScriptedLLMClient)(body: => Unit): Vector[RecordedCall] = {
    val before = llm.callCount
    body
    llm.calls.drop(before)
  }

  test("every model call is sent the current game state, after the system prompt") {
    val llm = scripted()
    val engine = newEngine(llm)
    engine.initialize().isRight shouldBe true
    play(engine, "take iron key", "look")
    engine.processCommand("examine wall", generateAudio = false, onTextChunk = Some(_ => ())).isRight shouldBe true

    llm.calls.size should be > 4
    llm.calls.foreach { call =>
      call.messages.count(_.isInstanceOf[SystemMessage]) shouldBe 1
      val system = systemText(call.messages)
      // the static prompt first, then the state, which no cacheable section follows
      system should startWith("You are a Dungeon Master")
      system should include(WorldStateMiddleware.Heading)
      system.indexOf(WorldStateMiddleware.Heading) should be > system.indexOf("RESPONSE FORMAT:")
    }
    // the opening's first call comes before the opening scene places the player
    section(llm.calls.head.messages) should include("Location: unknown yet")
    val latest = section(llm.lastCall.get.messages)
    latest should include("Location: cavern_entrance (Cavern Entrance)")
    latest should include("- north -> hall: locked (a rusted padlock)")
    latest should include("- east -> grotto: hidden - not yet discovered")
    latest should include("Inventory: iron key")
    // every unfinished quest, the active ones first, so that the model can tell when one is started or finished
    latest should include("- main [active]: Recover the drowned ledger")
    latest should include("- side-1 [not_started]: Light the lamps")
    latest.indexOf("- main [active]") should be < latest.indexOf("- side-1 [not_started]")
    // a character the adventure places here and the player has not met is to be brought in; others are not listed
    latest should include("Characters the adventure places here, not yet met")
    latest should include("- Ferryman (gatekeeper)")
    latest should not include "Archivist"
    latest should not include "The Founder"
  }

  test("a call later in the turn is sent the state as the turn's tool calls left it") {
    val llm = scripted()
    val engine = newEngine(llm)
    engine.initialize().isRight shouldBe true
    play(engine, "take iron key")

    val turn = callsOf(llm)(play(engine, "unlock gate"))
    turn.size shouldBe 2
    section(turn(0).messages) should include("- north -> hall: locked")
    section(turn(0).messages) should include("- side-1 [not_started]")
    // the second call follows both tool results, and its state shows what they changed
    turn(1).messages.takeRight(2).collect { case t: ToolMessage => t.toolCallId } shouldBe
      Seq("call-unlock", "call-quest")
    section(turn(1).messages) should include("- north -> hall: open")
    section(turn(1).messages) should include("- side-1 [active]: Light the lamps")
  }

  test("a character stops being announced once met, and a finished quest leaves the list") {
    val world = WorldState.seededFrom(Some(outline))
    world.enter("cavern_entrance", "Cavern Entrance")
    world.recordNpcMet("Ferryman", None, Some("took the coin"))
    world.updateQuest("side-1", QuestStatus.Completed, None)

    val text = WorldStateMiddleware.render(world.snapshot, Nil)
    text should not include "not yet met"
    text should include("- Ferryman (took the coin)")
    text should not include "side-1"
    text should include("- main [active]")
  }

  test("the state is never stored: not in the thread's history, nor in a save") {
    val llm = scripted()
    val engine = newEngine(llm)
    engine.initialize().isRight shouldBe true
    play(engine, "take iron key", "unlock gate", "look", "examine wall")

    engine.getMessages.exists(_.content.contains(WorldStateMiddleware.Heading)) shouldBe false
    engine.getMessages.exists(_.isInstanceOf[SystemMessage]) shouldBe false
    val saved = ujson.write(GameStateCodec.toJson(engine.getGameState("game-1", None, None)))
    saved should not include WorldStateMiddleware.Heading

    // a restored game is sent the state afresh, once
    val restoredLlm = scripted()
    val restored = newEngine(restoredLlm)
    restored.restoreGameState(GameStateCodec.fromJson(ujson.read(saved)))
    play(restored, "look")
    val system = systemText(restoredLlm.lastCall.get.messages)
    system.split(java.util.regex.Pattern.quote(WorldStateMiddleware.Heading), -1).length shouldBe 2
    section(restoredLlm.lastCall.get.messages) should include("- north -> hall: open")
  }

  test("pruning the conversation never drops the state") {
    val llm = scripted()
    val engine = newEngine(llm, Some(ContextWindowConfig(maxMessages = Some(2))))
    engine.initialize().isRight shouldBe true
    play(engine, "take iron key")
    (1 to 6).foreach(i => play(engine, s"examine wall $i"))

    val last = llm.lastCall.get.messages
    // the turn that took the key is no longer sent ...
    last.exists(_.content.contains("take iron key")) shouldBe false
    last.filterNot(_.isInstanceOf[SystemMessage]).size should be <= 2
    // ... but the state still says it is carried
    section(last) should include("Inventory: iron key")
    llm.calls.foreach(call => systemText(call.messages) should include(WorldStateMiddleware.Heading))
  }

  test("with the state in the call, unlocking a door takes two model calls, not five") {
    val llm = scripted()
    val engine = newEngine(llm)
    engine.initialize().isRight shouldBe true

    // without the key the Dungeon Master answers at once
    callsOf(llm)(play(engine, "unlock gate")).size shouldBe 1

    play(engine, "take iron key")
    // with it: one step making both changes, then the answer. Before the state was in every call, the prompt had the
    // model call list_inventory, get_location_state, set_exit_state and update_quest_progress one at a time - five
    // calls with the answer.
    val turn = callsOf(llm)(play(engine, "unlock gate"))
    turn.size shouldBe 2
    engine.lastTurnUsage.map(_.modelCalls) shouldBe Some(2L)
    val steps = engine.getMessages.drop(engine.getMessages.lastIndexWhere(_.content == "unlock gate")).collect {
      case a: AssistantMessage if a.toolCalls.nonEmpty => a.toolCalls.map(_.name)
    }
    steps shouldBe Vector(Seq("set_exit_state", "update_quest_progress"))
    engine.getMessages.collect { case a: AssistantMessage =>
      a.toolCalls.map(_.name)
    }.flatten should contain noneOf ("list_inventory",
    "get_location_state")
    engine.getWorldState.location(Some("cavern_entrance")).get.exits.find(_.direction == "north").get.state shouldBe
      ExitState.Open
    engine.getWorldState.quest("side-1").map(_.status) shouldBe Some(QuestStatus.Active)
  }

  test("the section bounds its own size, pointing to the read tools for the rest") {
    val many = WorldSnapshot.fromOutline(Some(outline))
    val crowded = (1 to 40).foldLeft(new WorldState(many)) { (w, i) =>
      w.enter("cavern_entrance", "Cavern Entrance")
      w.setFlag(None, s"flag_$i", "x" * 200)
      w.setExitState(None, s"passage-$i", ExitState.Closed, Some(s"room-$i"), None)
      w
    }
    val text = WorldStateMiddleware.render(crowded.snapshot, (1 to 100).map(i => s"item $i").toList)
    text.length should be <= WorldStateMiddleware.MaxChars
    text should include("more exits (get_location_state lists them)")
    WorldStateMiddleware
      .render(WorldSnapshot.fromOutline(Some(outline)), (1 to 40).map(i => s"item $i").toList) should include(
      "and 10 more items (list_inventory lists them)")
  }
}
