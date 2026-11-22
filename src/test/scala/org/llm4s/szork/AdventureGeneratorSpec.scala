package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ListBuffer
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.{SystemMessage, UserMessage}
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.llm4s.trace.{NoOpTracing, TraceEvent}
import org.llm4s.szork.game._
import org.llm4s.szork.persistence.GameStateCodec
import org.llm4s.szork.stubs.ScriptedDm

class AdventureGeneratorSpec extends AnyFunSuite with Matchers {

  private val outline = AdventureOutline(
    title = "The Sunken Vault",
    tagline = Some("Some treasures are best left drowned"),
    mainQuest = "Recover the drowned crown",
    subQuests = List("Free the trapped diver"),
    keyLocations = List(
      LocationOutline(
        id = "dock",
        name = "Rotting Dock",
        description = "Planks creak over black water. A ladder descends below; to the north a gate is chained shut.",
        significance = "The start",
        exits = List(
          ExitOutline("down", "vault_door", "A slick ladder descends into the water", "open"),
          ExitOutline("north", "boathouse", "A rusted gate leads north", "locked", Some("chained with a brass padlock"))
        ),
        interactables = List("coil of rope", "barnacled post"),
        puzzles = Some("The padlock opens with the key in the diver's satchel"),
        secrets = Some("Examining the post reveals a carved arrow pointing down")
      ),
      LocationOutline("vault_door", "Vault Door", "A barnacled door", "The way in")
    ),
    importantItems = List(ItemOutline("crown", "A drowned crown glints in the silt", "The goal")),
    keyCharacters = List(CharacterOutline("Mara", "guide", "A diver who knows the vault")),
    adventureArc = "Descend, discover, escape",
    specialMechanics = None
  )

  private val answer: String = ujson.write(AdventureGenerator.outlineToJson(outline))

  /** The same outline with an exit direction outside the schema's enum. */
  private val badAnswer: String = answer.replace("\"north\"", "\"north-east\"")

  private def generate(llm: ScriptedLLMClient, tracing: Option[NoOpTracing] = None) =
    AdventureGenerator.generateAdventureOutline("a drowned city", tracing)(using llm)

  test("a valid outline is decoded, with each location's exits, interactables, puzzles and secrets") {
    val llm = ScriptedLLMClient.always(Reply.text(answer))
    generate(llm) shouldBe Right(outline)
    llm.callCount shouldBe 1
    val dock = generate(llm).toOption.get.keyLocations.head
    dock.exits.map(e => (e.direction, e.state, e.obstacle)) shouldBe List(
      ("down", "open", None),
      ("north", "locked", Some("chained with a brass padlock")))
    dock.interactables should contain("coil of rope")
    dock.secrets.get should include("carved arrow")
  }

  test("an invalid first answer is corrected once by the model, and the corrected outline is used") {
    val llm = ScriptedLLMClient.respondingTo {
      case c if c.messages.exists(_.content.contains("north-east")) => Reply.text(answer)
      case _ => Reply.text(badAnswer)
    }
    generate(llm) shouldBe Right(outline)
    llm.callCount shouldBe 2
    val correction = llm.lastCall.get.messages.last.content
    correction should include("did not match the required JSON Schema")
    correction should include("north-east")
  }

  test("a second invalid answer fails the generation") {
    val llm = ScriptedLLMClient.always(Reply.text(badAnswer))
    val result = generate(llm)
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Failed to generate adventure outline")
    llm.callCount shouldBe 2 // the answer, and one correction
  }

  test("llm4s states the outline schema once; the brief keeps the design guidance and has no JSON template") {
    val llm = ScriptedLLMClient.always(Reply.text(answer))
    generate(llm).isRight shouldBe true

    val messages = llm.calls.head.messages
    val system = messages.collect { case s: SystemMessage => s.content }.mkString("\n")
    val brief = messages.collect { case u: UserMessage => u.content }.mkString("\n")
    system should include("JSON Schema")
    val marker = "Examinable objects beyond takeable items" // the schema's description of `interactables`
    (system + "\n" + brief).sliding(marker.length).count(_ == marker) shouldBe 1
    DungeonMasterOutput.Directions.foreach(direction => system should include(s"\"$direction\""))
    AdventureOutlineSchema.ExitStates.foreach(state => system should include(s"\"$state\""))
    brief should include("a drowned city")
    brief should include("STARTING LOCATION DESIGN")
    brief should not include "\"keyLocations\""
  }

  test("generation runs as a traced agent") {
    val events = ListBuffer.empty[TraceEvent]
    val tracing = new NoOpTracing {
      override def traceEvent(event: TraceEvent): Either[LLMError, Unit] = { events += event; Right(()) }
    }
    generate(ScriptedLLMClient.always(Reply.text(answer)), Some(tracing)).isRight shouldBe true
    events.collect { case e: TraceEvent.AgentRunEnded => (e.agent, e.status) }.toList shouldBe
      List((AdventureGenerator.AgentId, "completed"))
  }

  test("an outline round-trips through its JSON, every field written") {
    val json = AdventureGenerator.outlineToJson(outline)
    AdventureGenerator.outlineFromJson(ujson.read(ujson.write(json))) shouldBe outline
    json("specialMechanics") shouldBe ujson.Null
    json("keyLocations")(1)("exits") shouldBe ujson.Arr()
    json("keyLocations")(1)("secrets") shouldBe ujson.Null
  }

  test("an outline without the location details (as saved before them) still reads") {
    val json = AdventureGenerator.outlineToJson(outline)
    json("keyLocations").arr.foreach { loc =>
      Seq("exits", "interactables", "puzzles", "secrets").foreach(loc.obj.remove)
    }
    json.obj.remove("tagline")
    val read = AdventureGenerator.outlineFromJson(json)
    read.tagline shouldBe None
    read.keyLocations.map(_.id) shouldBe List("dock", "vault_door")
    read.keyLocations.head.exits shouldBe Nil
    read.keyLocations.head.puzzles shouldBe None
  }

  test("a saved game keeps the whole outline") {
    val engine = GameEngine.create(ScriptedDm(), sessionId = "outline-save", adventureOutline = Some(outline))
    engine.initialize().isRight shouldBe true
    val state = engine.getGameState("game-1", None, None)
    val restored = GameStateCodec.fromJson(ujson.read(ujson.write(GameStateCodec.toJson(state))))
    restored.adventureOutline shouldBe Some(outline)
  }

  test("the Dungeon Master's system prompt includes each location's exits, interactables, puzzles and secrets") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, Some(outline))
    prompt should include("Rotting Dock (dock)")
    prompt should include("north to boathouse (locked; chained with a brass padlock): A rusted gate leads north")
    prompt should include("down to vault_door (open): A slick ladder descends into the water")
    prompt should include("Interactables: coil of rope, barnacled post")
    prompt should include("Puzzles: The padlock opens with the key in the diver's satchel")
    prompt should include("Secrets: Examining the post reveals a carved arrow pointing down")
  }
}
