package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.agent.ContextWindowConfig
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.llm4s.szork.game._
import org.llm4s.szork.persistence.GameStateCodec
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}

/** Covers the Dungeon Master's world state: seeded from the outline, changed by its llm4s tools and by scenes, kept in
  * saves, and still answering after the turns that changed it are pruned from what the model is sent.
  */
class WorldStateSpec extends AnyFunSuite with Matchers {

  private val outline = AdventureOutline(
    title = "The Sunken Archive",
    mainQuest = "Recover the drowned ledger",
    subQuests = List("Light the lamps", "Free the archivist's cat"),
    keyLocations = List(
      LocationOutline(
        "cavern_entrance",
        "Cavern Entrance",
        "A damp mouth of rock",
        "Start",
        exits = List(ExitOutline("north", "hall", "An iron gate", "locked", Some("a rusted padlock")))
      ),
      LocationOutline(
        "hall",
        "Small Hall",
        "A low hall",
        "The archive's antechamber",
        exits = List(
          ExitOutline("south", "cavern_entrance", "The iron gate", "locked", Some("a rusted padlock")),
          ExitOutline("down", "vault", "A crack in the floor", "hidden")
        )
      )
    ),
    importantItems = List(ItemOutline("Key", "an iron key", "opens the gate")),
    keyCharacters = List(CharacterOutline("Archivist", "guide", "patient")),
    adventureArc = "Descend / Search / Escape"
  )

  /** The tool calls the scripted Dungeon Master makes on these commands (then the cavern answers the turn). */
  private val worldRules: PartialFunction[Conversation, Reply] = {
    case ScriptedDm.Command("unlock gate") =>
      Reply.toolCall("set_exit_state", """{"direction": "north", "state": "open"}""", id = Some("call-unlock"))
    case ScriptedDm.Command("pull lever") =>
      Reply.toolCall("set_location_flag", """{"flag": "lever_pulled", "value": "true"}""")
    case ScriptedDm.Command("talk to archivist") =>
      Reply.toolCall("record_npc_met", """{"name": "archivist", "note": "asked for the ledger"}""")
    case ScriptedDm.Command("read ledger") =>
      Reply.toolCall("update_quest_progress", """{"quest": "main", "status": "completed", "note": "ledger found"}""")
    case ScriptedDm.Command("where can I go") =>
      Reply.toolCall("get_location_state", "{}", id = Some("call-check"))
    case ScriptedDm.Command("progress") =>
      Reply.toolCall("get_world_summary", "{}")
  }

  private def newEngine(llm: ScriptedLLMClient, window: Option[ContextWindowConfig] = None): GameEngine =
    GameEngine.create(
      llmClient = llm,
      sessionId = "world-test",
      adventureOutline = Some(outline),
      contextWindow = window)

  private def run(engine: GameEngine, commands: String*): Unit = commands.foreach { command =>
    withClue(command)(engine.processCommand(command, generateAudio = false).isRight shouldBe true)
  }

  private def exit(world: WorldSnapshot, location: String, direction: String): ExitRecord =
    world.location(Some(location)).flatMap(_.exits.find(_.direction == direction)).get

  test("the world is seeded from the outline: exit states as an enum, characters not yet met, quests") {
    val world = WorldSnapshot.fromOutline(Some(outline))
    world.locations.map(_.id) shouldBe List("cavern_entrance", "hall")
    exit(world, "cavern_entrance", "north") shouldBe
      ExitRecord("north", "hall", ExitState.Locked, Some("a rusted padlock"))
    exit(world, "hall", "down").state shouldBe ExitState.Hidden
    world.npcs.map(n => (n.name, n.met)) shouldBe List(("Archivist", false))
    world.quests.map(q => (q.id, q.status)) shouldBe List(
      ("main", QuestStatus.Active),
      ("side-1", QuestStatus.NotStarted),
      ("side-2", QuestStatus.NotStarted))
    WorldSnapshot.fromOutline(None) shouldBe WorldSnapshot.empty
    ExitState.names should contain allElementsOf AdventureOutlineSchema.ExitStates
  }

  test("changing an exit changes the way back; a new exit needs its target") {
    val world = WorldState.seededFrom(Some(outline))
    world.enter("cavern_entrance", "Cavern Entrance")
    world.setExitState(None, "n", ExitState.Open, None, None).isRight shouldBe true
    exit(world.snapshot, "cavern_entrance", "north") shouldBe ExitRecord("north", "hall", ExitState.Open, None)
    exit(world.snapshot, "hall", "south").state shouldBe ExitState.Open

    // discovering the crack on one side
    world.setExitState(Some("Small Hall"), "down", ExitState.Open, None, None).isRight shouldBe true
    exit(world.snapshot, "hall", "down").state shouldBe ExitState.Open

    world.setExitState(Some("hall"), "west", ExitState.Closed, None, None).isLeft shouldBe true
    world
      .setExitState(Some("hall"), "west", ExitState.Closed, Some("study"), Some("a stuck door")) shouldBe a[Right[?, ?]]
    exit(world.snapshot, "hall", "west") shouldBe ExitRecord("west", "study", ExitState.Closed, Some("a stuck door"))
  }

  test("the agent's world-state tools change the world, and the model is sent their results") {
    val llm = ScriptedDm(worldRules)
    val engine = newEngine(llm)
    engine.initialize().isRight shouldBe true
    engine.getWorldState.currentLocation shouldBe Some("cavern_entrance")

    run(engine, "unlock gate")
    exit(engine.getWorldState, "cavern_entrance", "north").state shouldBe ExitState.Open
    exit(engine.getWorldState, "hall", "south").state shouldBe ExitState.Open
    val fedBack = llm.lastCall.get.messages.last
    fedBack shouldBe a[ToolMessage]
    fedBack.asInstanceOf[ToolMessage].toolCallId.asString shouldBe "call-unlock"
    fedBack.content should include("is now open")

    run(engine, "pull lever", "talk to archivist", "read ledger", "go north")
    val world = engine.getWorldState
    world.location(Some("cavern_entrance")).get.flags shouldBe Map("lever_pulled" -> "true")
    world.npc("Archivist").map(n => (n.met, n.lastSeenAt, n.notes)) shouldBe
      Some((true, Some("cavern_entrance"), List("asked for the ledger")))
    world.quest("main").map(_.status) shouldBe Some(QuestStatus.Completed)
    // the scene moved the player; no tool needed
    world.currentLocation shouldBe Some("hall")
    world.locations.filter(_.visited).map(_.id) shouldBe List("cavern_entrance", "hall")

    run(engine, "progress")
    val summary = llm.lastCall.get.messages.last.content
    summary should include("lever_pulled")
    summary should include("asked for the ledger")
    summary should include("completed")
  }

  test("optional tool parameters may be null, as a strict schema (every property required) needs") {
    val tools = GameTools.forWorld(WorldState.seededFrom(Some(outline)))
    tools.foreach { tool =>
      val params = tool.toolDefinition(strict = true)("function")("parameters")
      val loose = tool.toolDefinition(strict = false)("function")("parameters")("required").arr.map(_.str).toSet
      params("properties").obj.foreach { case (name, schema) =>
        if (!loose.contains(name)) withClue(s"${tool.name}.$name: ")(schema.render() should include("null"))
      }
    }
    val setExit = tools.find(_.name == "set_exit_state").get
    val result = setExit.execute(
      ujson.read("""{"direction":"north","state":"open","location_id":"hall","target_id":null,"obstacle":null}"""))
    result.map(_("success").bool) shouldBe Right(false) // hall has no north exit, and no target was given
    setExit
      .execute(
        ujson.read("""{"direction":"south","state":"open","location_id":"hall","target_id":null,"obstacle":null}"""))
      .map(_("success").bool) shouldBe Right(true)
  }

  test("a scene at a location the outline does not plan adds it to the world") {
    val engine = GameEngine.create(llmClient = ScriptedDm(worldRules), sessionId = "no-outline")
    engine.initialize().isRight shouldBe true
    engine.getWorldState.locations.map(l => (l.id, l.name, l.visited)) shouldBe
      List(("cavern_entrance", "Cavern Entrance", true))
    engine.getWorldState.quests shouldBe Nil
  }

  test("a made-up location is recorded with the exits its scene gives, so there is a way out of it") {
    val world = WorldState.seededFrom(Some(outline))
    world.enter("cavern_entrance", "Cavern Entrance")
    world.enter(
      "smugglers_gap",
      "Smuggler's Gap",
      List("E" -> "cavern_entrance", "east" -> "hall", "in" -> "smugglers_gap"))

    world.snapshot.location(Some("smugglers_gap")).get.exits shouldBe
      List(ExitRecord("east", "cavern_entrance", ExitState.Open, None))
    // a planned location keeps the exits it was planned with, whatever a scene lists
    world.enter("cavern_entrance", "Cavern Entrance", List("up" -> "sky"))
    world.snapshot.location(Some("cavern_entrance")).get.exits.map(_.direction) shouldBe List("north")
  }

  test("a note the Dungeon Master repeats is recorded once") {
    val world = WorldState.seededFrom(Some(outline))
    world.recordNpcMet("Mara", None, Some("Ghost keeper by the hearth."))
    world.recordNpcMet("Mara", None, Some("ghost keeper  by the hearth"))
    world.recordNpcMet("Mara", None, Some("Wants her locket back"))
    world.recordNpcMet("Mara", None, Some("  "))
    world.snapshot.npc("Mara").get.notes shouldBe List("Ghost keeper by the hearth.", "Wants her locket back")

    world.updateQuest("main", QuestStatus.Active, Some("Sheltering in the archive"))
    world.updateQuest("main", QuestStatus.Active, Some("Sheltering in the archive"))
    world.snapshot.quest("main").get.notes shouldBe List("Sheltering in the archive")
  }

  test("characters are placed where the outline first puts them, if that is one of its locations") {
    val placed = outline.copy(keyCharacters = List(
      CharacterOutline("Archivist", "guide", "patient", Some("hall")),
      CharacterOutline("Diver", "rival", "brash", Some("the_open_sea")),
      CharacterOutline("The Founder", "legend", "long dead")
    ))
    WorldSnapshot.fromOutline(Some(placed)).npcs.map(n => n.name -> n.foundAt) shouldBe
      List("Archivist" -> Some("hall"), "Diver" -> None, "The Founder" -> None)
  }

  test("the adventure ends when the main quest is completed or failed, and not before") {
    val ends: PartialFunction[Conversation, Reply] = {
      case ScriptedDm.Command("light the lamp") =>
        Reply.toolCall("update_quest_progress", """{"quest": "main", "status": "completed", "note": null}""")
      case ScriptedDm.Command("start side quest") =>
        Reply.toolCall("update_quest_progress", """{"quest": "side-1", "status": "completed", "note": null}""")
    }
    val engine = newEngine(ScriptedDm(ends))
    engine.initialize().isRight shouldBe true
    engine.ending shouldBe None
    run(engine, "start side quest")
    engine.ending shouldBe None
    run(engine, "light the lamp")
    engine.ending shouldBe Some("completed")

    // a game with no outline has no quests until the Dungeon Master records its main one
    val bare = GameEngine.create(llmClient = ScriptedDm(ends), sessionId = "no-outline-ending")
    bare.initialize().isRight shouldBe true
    bare.ending shouldBe None
    run(bare, "light the lamp")
    bare.ending shouldBe Some("completed")
  }

  test("an action that changes what is here updates the scene the player is shown") {
    val takes: PartialFunction[Conversation, Reply] = {
      case ScriptedDm.Command("take lantern") =>
        Reply.text(
          ujson.write(
            ujson.read(DmAnswers.simple("Taken.", "cavern_entrance", "take")).obj ++ Map[String, ujson.Value](
              "items" -> ujson.Arr("iron gate (locked)"),
              "npcs" -> ujson.Arr("Archivist"))))
      case ScriptedDm.Command("elsewhere") =>
        Reply.text(
          ujson.write(ujson.read(DmAnswers.simple("Far away.", "hall", "think")).obj ++ Map[String, ujson.Value](
            "items" -> ujson.Arr("a wrong list"))))
    }
    val engine = newEngine(ScriptedDm(takes))
    engine.initialize().isRight shouldBe true
    engine.getCurrentScene.get.items shouldBe Nil

    val taken = engine.processCommand("take lantern", generateAudio = false).toOption.get
    engine.getCurrentScene.get.items shouldBe List("iron gate (locked)")
    engine.getCurrentScene.get.npcs shouldBe List("Archivist")
    engine.getCurrentScene.get.locationId shouldBe "cavern_entrance"
    taken.scene.map(_.items) should (be(None) or be(Some(List("iron gate (locked)"))))

    // an action that changes nothing leaves the scene alone, as does one that names another location
    run(engine, "examine wall", "elsewhere")
    engine.getCurrentScene.get.items shouldBe List("iron gate (locked)")
  }

  test("the world state survives a save and load") {
    val engine = newEngine(ScriptedDm(worldRules))
    engine.initialize().isRight shouldBe true
    run(engine, "unlock gate", "pull lever", "talk to archivist", "read ledger", "go north")
    val saved = engine.getGameState("game-1", None, None)
    saved.worldState shouldBe engine.getWorldState

    val reloaded = GameStateCodec.fromJson(ujson.read(ujson.write(GameStateCodec.toJson(saved))))
    reloaded.worldState shouldBe saved.worldState

    val llm = ScriptedDm(worldRules)
    val restored = newEngine(llm)
    restored.restoreGameState(reloaded)
    restored.getWorldState shouldBe engine.getWorldState
    run(restored, "where can I go")
    llm.lastCall.get.messages.last.content should include("Small Hall")

    // a save without a world state starts from the world its outline plans
    val oldSave = GameStateCodec.toJson(saved)
    oldSave.obj.remove("worldState")
    GameStateCodec.fromJson(oldSave).worldState shouldBe WorldSnapshot.fromOutline(Some(outline))
  }

  test("after the turn that changed it is pruned, the world state still answers correctly") {
    val llm = ScriptedDm(worldRules)
    val engine = newEngine(llm, Some(ContextWindowConfig(maxMessages = Some(4))))
    engine.initialize().isRight shouldBe true
    run(engine, "unlock gate")
    (1 to 8).foreach(i => run(engine, s"examine wall $i"))
    run(engine, "where can I go")

    val lastRequest = llm.lastCall.get.messages
    // the unlocking turn is no longer sent ...
    lastRequest.exists(_.content.contains("unlock gate")) shouldBe false
    lastRequest.collect { case a: AssistantMessage => a.toolCalls.map(_.name) }.flatten should not contain
      "set_exit_state"
    // ... but the tool's answer says the gate is open
    val answer = lastRequest.last
    answer.asInstanceOf[ToolMessage].toolCallId.asString shouldBe "call-check"
    val view = ujson.read(answer.content)
    val north = view("exits").arr.find(_("direction").str == "north").get
    north("state").str shouldBe "open"
    north("passable").bool shouldBe true
    // and the whole conversation is still in the thread
    engine.getMessages.exists(_.content.contains("unlock gate")) shouldBe true
  }
}
