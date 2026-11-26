package org.llm4s.szork

import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.model.{Conversation, ReasoningEffort}
import org.llm4s.szork.adventures.Adventure
import org.llm4s.szork.api.{ArtStyle, GameTheme, SubagentConfig, SzorkConfig}
import org.llm4s.szork.game._
import org.llm4s.szork.playtest._
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The playtest harness itself, with scripted models: a designer, a Dungeon Master and a player. */
class PlaytestSpec extends AnyFunSuite with Matchers with LoneElement {

  private val outline = AdventureOutline(
    title = "The Locked Cellar",
    mainQuest = "Reach the study",
    subQuests = Nil,
    keyLocations = List(
      LocationOutline(
        "hall",
        "Hall",
        "A hall.",
        "The start",
        List(
          ExitOutline("north", "study", "A door", "open"),
          ExitOutline("down", "cellar", "A trapdoor", "locked", Some("a padlock")))
      ),
      LocationOutline("study", "Study", "A study.", "The goal", List(ExitOutline("south", "hall", "A door", "open"))),
      LocationOutline("cellar", "Cellar", "A cellar.", "A side room", List(ExitOutline("up", "hall", "Steps", "open")))
    ),
    importantItems = Nil,
    keyCharacters = Nil,
    adventureArc = "In, then on"
  )

  private val hall = DmAnswers.scene("You stand in a draughty hall.", "hall", "Hall", Seq("north" -> "study"))
  private val study = DmAnswers.scene("You step into a quiet study.", "study", "Study", Seq("south" -> "hall"))
  private val cellar = DmAnswers.scene("You drop into the cellar.", "cellar", "Cellar", Seq("up" -> "hall"))

  /** A designer that answers with `outline`, and a Dungeon Master that plays it - and walks through the locked trapdoor
    * whenever it is asked to.
    */
  private def models(extra: PartialFunction[Conversation, Reply] = PartialFunction.empty): ScriptedLLMClient = {
    val game: PartialFunction[Conversation, Reply] = {
      case c if c.messages.exists(_.content.contains("MAP RULES")) =>
        Reply.text(ujson.write(AdventureGenerator.outlineToJson(outline)))
      case c if ScriptedDm.lastUserText(c) == "go north" => Reply.text(study)
      case c if ScriptedDm.lastUserText(c) == "go south" => Reply.text(hall)
      case c if ScriptedDm.lastUserText(c) == "go down" => Reply.text(cellar)
      case c if ScriptedDm.lastUserText(c) == GameConstants.Prompts.INIT_ADVENTURE => Reply.text(hall)
      case c if c.messages.exists(_.content.contains(ExitGuard.CorrectionLead)) => Reply.text(cellar)
      case c =>
        val here = if (c.messages.exists(_.content.contains("quiet study"))) "study" else "hall"
        Reply.text(DmAnswers.simple("Nothing much happens.", here, "wait"))
    }
    ScriptedLLMClient.respondingTo(extra.orElse(game))
  }

  private def playtest(llm: ScriptedLLMClient, turns: Int, commands: String*) =
    Playtest.run(PlaytestConfig("a house", turns), llm, Player.scripted(commands*)).fold(e => fail(e.message), identity)

  test("a game that keeps its rules passes, and the report says what was played") {
    val report = playtest(models(), 3, "go north", "wait", "go south")

    report.passed shouldBe true
    report.findings.filter(_.hard) shouldBe Nil
    report.turns.map(_.command) shouldBe Vector("go north", "wait", "go south")
    report.turns.map(_.after.currentLocation) shouldBe Vector(Some("study"), Some("study"), Some("hall"))
    report.ending shouldBe None

    val json = report.toJson
    json("title").str shouldBe "The Locked Cellar"
    json("passed").bool shouldBe true
    json("locationsVisited").arr.map(_.str).toSet shouldBe Set("study", "hall")
    json("turns").arr should have size 3
    report.summary should startWith("PASSED")
  }

  test("the locked exit of the start location is walked at in a game of its own, and holds") {
    val lines = scala.collection.mutable.ListBuffer.empty[String]
    val report = Playtest
      .run(PlaytestConfig("a house", turns = 1), models(), Player.scripted("wait"), log = lines += _)
      .fold(e => fail(e.message), identity)

    report.passed shouldBe true
    lines.mkString("\n") should include("Probe 'go down': the locked exit held")
  }

  test("a turn that fails is a hard finding, and the game is played on") {
    val looping = models { case c if ScriptedDm.lastUserText(c) == "loop" => Reply.toolCall("list_inventory") }
    val report = playtest(looping, 2, "loop", "go north")

    report.passed shouldBe false
    report.findings.filter(_.hard).map(f => (f.check, f.turn)) shouldBe List(("turn-failed", Some(1)))
    report.turns.last.after.currentLocation shouldBe Some("study")
    report.summary should startWith("FAILED")
  }

  test("the playtest stops when the adventure ends") {
    val winning = models {
      case ScriptedDm.Command("win") =>
        Reply.toolCall("update_quest_progress", """{"quest": "main", "status": "completed"}""")
      case c if ScriptedDm.lastUserText(c) == "win" => Reply.text(DmAnswers.simple("You have done it.", "hall", "win"))
    }
    val report = playtest(winning, 10, "wait", "win", "wait", "wait")

    report.ending shouldBe Some("completed")
    report.turns.map(_.command) shouldBe Vector("wait", "win")
  }

  private def world(at: String, down: ExitState = ExitState.Locked) = {
    val seeded = WorldSnapshot.fromOutline(Some(outline))
    seeded.copy(
      currentLocation = Some(at),
      locations = seeded.locations.map { location =>
        location.copy(exits = location.exits.map(e => if (e.targetId == "cellar") e.copy(state = down) else e))
      }
    )
  }

  private def turn(number: Int, narration: String, from: String = "hall", to: String = "hall") =
    PlayedTurn(number, "x", narration, world(from), world(to), None, Nil)

  test("a move through an exit that is not open is a hard finding; one the turn opened is not") {
    val through = turn(1, "You drop into the cellar.", to = "cellar")
    PlaytestChecks.turn(through, Nil).map(f => (f.check, f.hard)) shouldBe List(("closed-exit-used", true))

    val unlocked = through.copy(after = world("cellar", down = ExitState.Open))
    PlaytestChecks.turn(unlocked, Nil) shouldBe Nil
    PlaytestChecks.turn(turn(1, "You step into the study.", to = "study"), Nil) shouldBe Nil
  }

  test("an empty narration, raw JSON and a scene the world disagrees with are hard findings") {
    PlaytestChecks.turn(turn(1, "  "), Nil).map(_.check) shouldBe List("empty-narration")
    PlaytestChecks.turn(turn(1, """{"narrationText": "x"}"""), Nil).map(_.check) shouldBe List("raw-answer")
    val elsewhere = GameScene("study", "Study", "x", "x", "x", "calm", Nil)
    PlaytestChecks.turn(turn(1, "You look.").copy(scene = Some(elsewhere)), Nil).map(_.check) shouldBe
      List("location-mismatch")
  }

  test("a narration that repeats an earlier one, and an item both carried and lying here, are soft findings") {
    val long = "The lamp room is cold and bright, its great lens turning slowly above the iron gallery and the sea."
    val first = turn(1, long)
    val again = turn(2, long.replace("cold", "chill"))
    PlaytestChecks.turn(again, Seq(first)).map(f => (f.check, f.hard)) shouldBe List(("repeated-narration", false))
    PlaytestChecks.turn(turn(2, "Taken."), Seq(turn(1, "Taken."))) shouldBe Nil

    val scene = GameScene("hall", "Hall", "x", "x", "x", "calm", Nil, items = List("Brass Key", "rope"))
    val carrying = turn(1, "You look.").copy(scene = Some(scene), inventory = List("brass key"))
    PlaytestChecks.turn(carrying, Nil).map(_.check) shouldBe List("carried-item-in-scene")
  }

  test("an outline with a location that cannot be reached is a hard finding") {
    val stranded = outline.copy(keyLocations = outline.keyLocations :+ LocationOutline("vault", "Vault", "x", "x"))
    PlaytestChecks.outline(stranded).map(f => (f.check, f.hard)) shouldBe List(("outline-map", true))
    PlaytestChecks.outline(outline) shouldBe Nil
  }

  private val PlayerPrompt = "You answer with the player's next command"
  private def isPlayer(c: Conversation) = c.messages.exists(_.content.startsWith(PlayerPrompt))
  private def isDm(c: Conversation) = c.messages.exists(_.content.contains("You are a Dungeon Master"))

  test("the model player is asked with the configured reasoning and room to answer, and plays what it types") {
    val llm = models { case c if isPlayer(c) => Reply.text("> GO NORTH\n") }
    val report = Playtest
      .run(PlaytestConfig("a house", 1, probeExits = false), llm, Player.model(llm, Some(ReasoningEffort.Low)))
      .fold(e => fail(e.message), identity)
    report.turns.map(_.command) shouldBe Vector("go north")
    report.findings shouldBe Nil
    val asked = llm.calls.filter(c => isPlayer(c.conversation)).loneElement
    asked.options.maxTokens shouldBe Some(Player.MaxOutputTokens)
    asked.options.reasoning shouldBe Some(ReasoningEffort.Low)
  }

  test("a player that cannot choose looks around, is reported, and a playtest it never played in fails") {
    val failing = models { case c if isPlayer(c) => Reply.failure(SimpleError("overloaded")) }
    val report = Playtest
      .run(PlaytestConfig("a house", 2, probeExits = false), failing, Player.model(failing))
      .fold(e => fail(e.message), identity)
    report.turns.map(_.command) shouldBe Vector("look", "look")
    report.findings.map(f => (f.check, f.hard, f.turn)) shouldBe List(
      ("player-failed", false, Some(1)),
      ("player-failed", false, Some(2)),
      ("player-never-played", true, None))
    report.findings.head.detail should include("overloaded")
    report.passed shouldBe false

    val silent = models { case c if isPlayer(c) => Reply.text("  \n") }
    Player.model(silent).next("x", None, Nil, Nil) shouldBe Left("the player model answered with no command")
  }

  test("the playtest's games are made from the server's settings") {
    val llm = models()
    val quiet = SzorkConfig(subagents = SubagentConfig(npcDialogue = false, hints = false), reasoningEffort = None)
    Playtest
      .run(PlaytestConfig("a house", 1, probeExits = false), llm, Player.scripted("wait"), settings = quiet)
      .isRight shouldBe true
    val dm = llm.calls.filter(c => isDm(c.conversation))
    dm should not be empty
    dm.flatMap(_.options.tools.map(_.name)).filter(_.startsWith("task_")) shouldBe empty
    dm.map(_.options.reasoning).distinct shouldBe List(None)

    val subagents = models()
    Playtest
      .run(PlaytestConfig("a house", 1, probeExits = false), subagents, Player.scripted("wait"))
      .isRight shouldBe true
    subagents.calls.filter(c => isDm(c.conversation)).head.options.tools.map(_.name) should contain(
      DmSubagents.HintTool)
  }

  test("a library adventure is played under its own theme and art style") {
    val adventure = Adventure(
      "the-locked-cellar",
      outline,
      Some(GameTheme("custom", "Station", "a derelict space station")),
      Some(ArtStyle("pixel", "Pixel")),
      0L)
    val llm = models()
    val report = Playtest
      .playAdventure(
        adventure,
        PlaytestConfig("a haunted lighthouse", 1, probeExits = false),
        llm,
        Player.scripted("wait"))
      .fold(e => fail(e.message), identity)
    report.theme shouldBe "a derelict space station"
    val system = llm.calls.filter(c => isDm(c.conversation)).head.conversation.messages.head.content
    system should include("Adventure Theme: a derelict space station")
    system should include("Art Style: pixel art style")
    system should not include "lighthouse"
  }

  test("a report is written where it is asked for, making its directories, or the failure is said") {
    val report = playtest(models(), 1, "wait")
    val root = java.nio.file.Files.createTempDirectory("playtest")
    val nested = root.resolve("runs/today/report.json")
    Playtest.writeReport(report, nested) shouldBe Right(nested)
    ujson.read(java.nio.file.Files.readString(nested))("title").str shouldBe "The Locked Cellar"

    val blocked = root.resolve("runs/today/report.json/inside.json") // a file is in the way of the directory
    Playtest.writeReport(report, blocked).isLeft shouldBe true
  }

  test("an outline problem's severity is decided by its kind, not by the words in it") {
    // an open exit with an obstacle is a soft problem, whatever its obstacle says
    val wordy = outline.copy(keyLocations = outline.keyLocations.map { l =>
      l.copy(exits = l.exits.map(e => if (e.targetId == "study") e.copy(obstacle = Some("cannot be reached")) else e))
    })
    PlaytestChecks.outline(wordy).map(f => (f.hard, f.detail)) shouldBe List(
      (false, "hall: the north exit to study is open but has an obstacle (cannot be reached)"))
  }
}
