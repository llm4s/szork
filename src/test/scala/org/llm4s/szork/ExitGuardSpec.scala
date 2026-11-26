package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.llm4s.szork.game._
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}

/** Covers the rule that a scene never moves the player through an exit that is not open: the Dungeon Master is told
  * what is in the way and answers again ([[ExitGuardMiddleware]]), and whatever it answers, the move is not recorded.
  */
class ExitGuardSpec extends AnyFunSuite with Matchers {

  private val Riddle = "an arch that demands a riddle be answered"

  private val outline = AdventureOutline(
    title = "The Riddle Market",
    mainQuest = "Pass the arch",
    subQuests = Nil,
    keyLocations = List(
      LocationOutline(
        "market_thoroughfare",
        "Market Thoroughfare",
        "A crowded street",
        "Start",
        exits = List(
          ExitOutline("east", "riddle_gate", "A carved arch", "locked", Some(Riddle)),
          ExitOutline("north", "alley", "A plain door", "closed"),
          ExitOutline("down", "cellar", "A trapdoor under the stall", "hidden", Some("found by moving the stall")),
          ExitOutline("south", "square", "The street runs on", "open")
        )
      ),
      LocationOutline(
        "riddle_gate",
        "Riddle Gate",
        "Beyond the arch",
        "The goal",
        exits = List(ExitOutline("west", "market_thoroughfare", "The arch", "locked", Some(Riddle)))),
      LocationOutline(
        "alley",
        "Alley",
        "A narrow alley",
        "A side way",
        exits = List(ExitOutline("south", "market_thoroughfare", "The plain door", "closed"))),
      LocationOutline(
        "cellar",
        "Cellar",
        "A damp cellar",
        "A secret",
        exits = List(ExitOutline("up", "market_thoroughfare", "The trapdoor", "open"))),
      LocationOutline(
        "square",
        "Square",
        "An open square",
        "A crossing",
        exits = List(ExitOutline("north", "market_thoroughfare", "The street", "open")))
    ),
    importantItems = Nil,
    keyCharacters = Nil,
    adventureArc = "Arrive / Answer / Pass"
  )

  private val Market =
    DmAnswers.scene(
      "You stand in the market.",
      "market_thoroughfare",
      "Market Thoroughfare",
      Seq("east" -> "riddle_gate"))
  private val ThroughTheArch =
    DmAnswers.scene("You stride under the arch.", "riddle_gate", "Riddle Gate", Seq("west" -> "market_thoroughfare"))
  private val Alley =
    DmAnswers.scene("You step into the alley.", "alley", "Alley", Seq("south" -> "market_thoroughfare"))
  private val ArchAsks = DmAnswers.simple("The arch asks its riddle; you have no answer.", "market_thoroughfare", "go")

  /** Whether the request is the exit guard's corrective one. */
  private def corrected(c: Conversation): Boolean = ScriptedDm.lastUserText(c).startsWith(ExitGuard.CorrectionLead)

  private def endsWithToolResult(c: Conversation): Boolean = c.messages.lastOption.exists(_.isInstanceOf[ToolMessage])

  /** Opens at the market; "go east" walks through the locked arch, and `rules` say what follows. */
  private def dm(rules: PartialFunction[Conversation, Reply]): ScriptedLLMClient = ScriptedDm(rules.orElse {
    case ScriptedDm.Command(GameConstants.Prompts.INIT_ADVENTURE) => Reply.text(Market)
    case ScriptedDm.Command("look") => Reply.text(Market)
    case ScriptedDm.Command("go east") => Reply.text(ThroughTheArch)
  })

  private def started(llm: ScriptedLLMClient): GameEngine = {
    val engine = GameEngine.create(llmClient = llm, sessionId = "exit-guard", adventureOutline = Some(outline))
    engine.initialize().isRight shouldBe true
    engine
  }

  /** Plays `command`, streaming or not, and returns the response with what was streamed. */
  private def play(engine: GameEngine, command: String, streaming: Boolean): (GameResponse, String) = {
    val streamed = new StringBuilder()
    val response = engine.processCommand(
      command,
      generateAudio = false,
      onTextChunk = Option.when(streaming) { (chunk: String) =>
        streamed.append(chunk); ()
      })
    withClue(response)(response.isRight shouldBe true)
    (response.toOption.get, streamed.toString)
  }

  private def exitState(engine: GameEngine, location: String, direction: String): ExitState =
    engine.getWorldState.location(Some(location)).flatMap(_.exits.find(_.direction == direction)).get.state

  private def turnCalls(llm: ScriptedLLMClient): Int =
    llm.calls.count(!_.lastUserText.contains(GameConstants.Prompts.INIT_ADVENTURE))

  private def stillAtTheMarket(engine: GameEngine): Unit = {
    engine.getWorldState.currentLocation shouldBe Some("market_thoroughfare")
    engine.getCurrentScene.map(_.locationId) shouldBe Some("market_thoroughfare")
    engine.locationChangedLastTurn shouldBe false
    exitState(engine, "market_thoroughfare", "east") shouldBe ExitState.Locked
    engine.getWorldState.location(Some("riddle_gate")).get.visited shouldBe false
    val saved = engine.getGameState("game-1", None, None)
    saved.worldState.currentLocation shouldBe Some("market_thoroughfare")
    saved.currentScene.map(_.locationId) shouldBe Some("market_thoroughfare")
  }

  test("the opening scene, a look around and a move through an open exit need no correction") {
    val llm = dm { case ScriptedDm.Command("go south") =>
      Reply.text(DmAnswers.scene("You reach the square.", "square", "Square", Seq("north" -> "market_thoroughfare")))
    }
    val engine = started(llm)
    engine.getWorldState.currentLocation shouldBe Some("market_thoroughfare")
    play(engine, "look", streaming = false)._1.text shouldBe "You stand in the market."
    play(engine, "go south", streaming = false)
    engine.getWorldState.currentLocation shouldBe Some("square")
    llm.calls.size shouldBe 3
  }

  for (streaming <- Seq(false, true)) {
    val mode = if (streaming) "streaming" else "blocking"

    test(s"$mode: a scene through a locked exit is sent back to the model, whose second answer is used") {
      val llm = dm { case c if corrected(c) => Reply.text(ArchAsks) }
      val engine = started(llm)

      val (response, streamed) = play(engine, "go east", streaming)

      response.text shouldBe "The arch asks its riddle; you have no answer."
      response.refused shouldBe false
      stillAtTheMarket(engine)
      if (streaming) streamed should endWith("The arch asks its riddle; you have no answer.")

      // the model was told what it did and what is in the way
      turnCalls(llm) shouldBe 2
      val request = llm.lastCall.get.messages
      request.init.last shouldBe a[AssistantMessage]
      request.init.last.content should include("You stride under the arch.")
      val correction = request.last.content
      correction should include("market_thoroughfare")
      correction should include("east exit to riddle_gate is locked")
      correction should include(Riddle)
      correction should include("set_exit_state")

      // the thread, and so a save, holds the turn as the command and the answer that was used
      val turn = engine.getMessages.takeRight(2)
      turn.map(_.content) shouldBe Vector("go east", ArchAsks)
      engine.getMessages.exists(_.content.contains(ExitGuard.CorrectionLead)) shouldBe false
      engine.getMessages.exists(_.content.contains("You stride under the arch.")) shouldBe false
    }

    test(s"$mode: a model that moves the player through the locked exit again is overruled") {
      val llm = dm { case c if corrected(c) => Reply.text(ThroughTheArch) }
      val engine = started(llm)

      val (response, _) = play(engine, "go east", streaming)

      response.text should include("east")
      response.text should include("locked")
      response.text should include(Riddle)
      response.text should not include "stride"
      stillAtTheMarket(engine)
      turnCalls(llm) shouldBe 2

      // the thread holds what the player was told, as a simple answer at the market
      val stored = ResponseInterpreter.interpret(engine.getMessages.last.content).data
      stored shouldBe Some(SimpleResponse(response.text, "market_thoroughfare", ExitGuard.ShutAction))

      // and the game plays on from the market
      play(engine, "look", streaming)._1.text shouldBe "You stand in the market."
      engine.getWorldState.currentLocation shouldBe Some("market_thoroughfare")
    }

    test(s"$mode: the corrected model may open the exit with set_exit_state and then move the player") {
      val llm = dm {
        case c if corrected(c) => Reply.toolCall("set_exit_state", """{"direction": "east", "state": "open"}""")
        case c if endsWithToolResult(c) => Reply.text(ThroughTheArch)
      }
      val engine = started(llm)

      play(engine, "go east", streaming)._1.text shouldBe "You stride under the arch."

      engine.getWorldState.currentLocation shouldBe Some("riddle_gate")
      engine.locationChangedLastTurn shouldBe true
      exitState(engine, "market_thoroughfare", "east") shouldBe ExitState.Open
      exitState(engine, "riddle_gate", "west") shouldBe ExitState.Open
      turnCalls(llm) shouldBe 3
      engine.getMessages.exists(_.content.contains(ExitGuard.CorrectionLead)) shouldBe false
    }

    test(s"$mode: a closed door the model opens with set_exit_state in the same turn is walked through") {
      val llm = dm {
        case ScriptedDm.Command("go north") =>
          Reply.toolCall("set_exit_state", """{"direction": "north", "state": "open"}""")
        case c if endsWithToolResult(c) => Reply.text(Alley)
      }
      val engine = started(llm)

      play(engine, "go north", streaming)._1.text shouldBe "You step into the alley."

      engine.getWorldState.currentLocation shouldBe Some("alley")
      exitState(engine, "market_thoroughfare", "north") shouldBe ExitState.Open
      turnCalls(llm) shouldBe 2 // the tool call and the scene: nothing to correct
    }

    test(s"$mode: a scene somewhere no exit leads to still moves the player") {
      val llm = dm { case ScriptedDm.Command("sleep") =>
        Reply.text(DmAnswers.scene("You dream of a glass sea.", "dreamscape", "Dreamscape", Seq("out" -> "alley")))
      }
      val engine = started(llm)

      play(engine, "sleep", streaming)

      engine.getWorldState.currentLocation shouldBe Some("dreamscape")
      turnCalls(llm) shouldBe 1
    }
  }

  test("a closed door the model walks through without opening it is corrected too") {
    val llm = dm {
      case ScriptedDm.Command("go north") => Reply.text(Alley)
      case c if corrected(c) => Reply.text(Alley)
    }
    val engine = started(llm)

    val text = play(engine, "go north", streaming = false)._1.text
    text should include("north")
    text should include("closed")
    engine.getWorldState.currentLocation shouldBe Some("market_thoroughfare")
    llm.lastCall.get.messages.last.content should include("north exit to alley is closed")
  }

  test("a hidden exit is not given away to the player, though the model is told of it") {
    val cellar = DmAnswers.scene("You drop into the cellar.", "cellar", "Cellar", Seq("up" -> "market_thoroughfare"))
    val llm = dm {
      case ScriptedDm.Command("go down") => Reply.text(cellar)
      case c if corrected(c) => Reply.text(cellar)
    }
    val engine = started(llm)

    val text = play(engine, "go down", streaming = false)._1.text
    text should not include "hidden"
    text should not include "cellar"
    text should not include "stall"
    engine.getWorldState.currentLocation shouldBe Some("market_thoroughfare")
    val correction = llm.lastCall.get.messages.last.content
    correction should include("down exit to cellar is hidden")
    correction should include("found by moving the stall")
  }

  test("an answer wrapped in a code fence is checked as its JSON") {
    val llm = dm {
      case ScriptedDm.Command("go east") => Reply.text(s"```json\n$ThroughTheArch\n```")
      case c if corrected(c) => Reply.text(ArchAsks)
    }
    val engine = started(llm)

    play(engine, "go east", streaming = false)._1.text shouldBe "The arch asks its riddle; you have no answer."
    stillAtTheMarket(engine)
  }

  test("an answer with prose and braces of its own round its JSON is checked as the JSON llm4s takes from it") {
    val llm = dm {
      case ScriptedDm.Command("go east") => Reply.text(s"A note {of sorts}: $ThroughTheArch")
      case c if corrected(c) => Reply.text(ArchAsks)
    }
    val engine = started(llm)

    play(engine, "go east", streaming = false)._1.text shouldBe "The arch asks its riddle; you have no answer."
    stillAtTheMarket(engine)
    turnCalls(llm) shouldBe 2
  }

  test("a move refused after two rounds of tool calls is still offered the tools to open the way") {
    val command = "answer the riddle and go east"
    // tool results since the player's command: what the model has done so far this turn
    def done(c: Conversation): Int =
      c.messages.reverseIterator.takeWhile(!_.isInstanceOf[UserMessage]).count(_.isInstanceOf[ToolMessage])
    val llm = dm {
      case c if corrected(c) => Reply.toolCall("set_exit_state", """{"direction": "east", "state": "open"}""")
      case c if ScriptedDm.lastUserText(c) == command =>
        done(c) match {
          case 0 => Reply.toolCall("get_location_state", """{"location_id": "riddle_gate"}""")
          case 1 => Reply.toolCall("set_location_flag", """{"flag": "riddle", "value": "answered"}""")
          case _ => Reply.text(ThroughTheArch) // refused the first time, used once the arch is open
        }
    }
    val engine = started(llm)

    play(engine, command, streaming = false)._1.text shouldBe "You stride under the arch."

    // the correction came in the third round, which may still call tools: it was offered them, and its call ran
    val correction = llm.calls.find(c => ScriptedDm.lastUserText(c.conversation).startsWith(ExitGuard.CorrectionLead))
    correction.map(_.options.tools.nonEmpty) shouldBe Some(true)
    correction.get.messages.last.content should include("set_exit_state")
    exitState(engine, "market_thoroughfare", "east") shouldBe ExitState.Open
    engine.getWorldState.currentLocation shouldBe Some("riddle_gate")
  }

  test("a correction offered no tools does not ask the model to call one") {
    val seeded = WorldState.seededFrom(Some(outline))
    seeded.enter("market_thoroughfare", "Market Thoroughfare")
    val move = ExitGuard.blockedMove(seeded.snapshot, "riddle_gate").get
    ExitGuard.correction(move) should include("set_exit_state")
    val toolless = ExitGuard.correction(move, canOpen = false)
    toolless should not include "set_exit_state"
    toolless should include("simple response with locationId market_thoroughfare")
  }

  test("blockedMove: only a move to another location, along exits of which none is open") {
    val seeded = WorldState.seededFrom(Some(outline))
    // no current location yet: the opening scene
    ExitGuard.blockedMove(seeded.snapshot, "riddle_gate") shouldBe None

    seeded.enter("market_thoroughfare", "Market Thoroughfare")
    val world = seeded.snapshot
    ExitGuard.blockedMove(world, "market_thoroughfare") shouldBe None
    ExitGuard.blockedMove(world, "square") shouldBe None
    ExitGuard.blockedMove(world, "dreamscape") shouldBe None
    ExitGuard.blockedMove(world, "alley").map(_.exits.map(e => (e.direction, e.state))) shouldBe
      Some(List(("north", ExitState.Closed)))
    val arch = ExitGuard.blockedMove(world, "riddle_gate").get
    (arch.from, arch.to) shouldBe ("market_thoroughfare", "riddle_gate")
    arch.exits shouldBe List(ExitRecord("east", "riddle_gate", ExitState.Locked, Some(Riddle)))

    // a second, open way to the same place makes the move legitimate
    seeded.setExitState(None, "up", ExitState.Open, Some("riddle_gate"), None).isRight shouldBe true
    ExitGuard.blockedMove(seeded.snapshot, "riddle_gate") shouldBe None
  }

  test("blockedMove on an answer: a simple response, or an answer that is not a usable scene, moves no one") {
    val seeded = WorldState.seededFrom(Some(outline))
    seeded.enter("market_thoroughfare", "Market Thoroughfare")
    val world = seeded.snapshot
    ExitGuard.blockedMoveIn(world, ThroughTheArch).map(_.to) shouldBe Some("riddle_gate")
    ExitGuard.blockedMoveIn(world, DmAnswers.simple("You peer east.", "riddle_gate", "examine")) shouldBe None
    ExitGuard.blockedMoveIn(world, "The arch looms.") shouldBe None
    ExitGuard.blockedMoveIn(world, Market) shouldBe None
  }

  test("the overruling answer matches the Dungeon Master's output: every property, narration first") {
    val seeded = WorldState.seededFrom(Some(outline))
    seeded.enter("market_thoroughfare", "Market Thoroughfare")
    val json = ujson.read(ExitGuard.shutAnswer(ExitGuard.blockedMove(seeded.snapshot, "riddle_gate").get))
    json.obj.keys.toList shouldBe ujson.read(ArchAsks).obj.keys.toList
    json("responseType").str shouldBe "simple"
    json("locationId").str shouldBe "market_thoroughfare"
  }
}
