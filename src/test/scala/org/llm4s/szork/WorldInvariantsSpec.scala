package org.llm4s.szork

import org.llm4s.llmconnect.model.{Conversation, UserMessage}
import org.llm4s.szork.game._
import org.llm4s.szork.persistence.GameStateCodec
import org.llm4s.szork.playtest.{PlayedTurn, PlaytestChecks}
import org.llm4s.szork.stubs.DmAnswers
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/** The game's rules under a Dungeon Master that does anything: it answers each command with a scene somewhere at random
  * (through shut exits, in places it makes up), a simple response, or a tool call that changes an exit. Whatever it
  * does, the world the engine keeps must stay consistent. Each game is seeded, so a failure names the seed to replay.
  */
class WorldInvariantsSpec extends AnyFunSuite with Matchers {

  private val Directions = List("north", "south", "east", "west", "up", "down")
  private val States = List("open", "open", "closed", "locked", "sealed", "hidden")

  /** A sound map of `size` locations: a chain of open ways, with more exits of every state between random pairs. */
  private def randomOutline(random: Random, size: Int): AdventureOutline = {
    val ids = (0 until size).map(i => s"room_$i").toList
    def exit(direction: String, to: String, state: String) =
      ExitOutline(
        direction,
        to,
        s"A way $direction",
        state,
        Option.when(state != "open" && state != "closed")("barred"))
    val locations = ids.zipWithIndex.map { case (id, i) =>
      val onward = ids.lift(i + 1).map(exit("north", _, "open")).toList
      val back = Option.when(i > 0)(exit("south", ids(i - 1), "open")).toList
      val extra = random.shuffle(Directions.drop(2)).take(random.nextInt(3)).map { direction =>
        exit(direction, random.shuffle(ids.filterNot(_ == id)).head, States(random.nextInt(States.size)))
      }
      LocationOutline(id, id, s"The $id.", "A room", onward ++ back ++ extra)
    }
    AdventureOutline("Random", None, "Wander", Nil, locations, Nil, Nil, "Around", None)
  }

  /** A Dungeon Master that answers at random. A turn's first call may change an exit's state with a tool; every other
    * call answers, with a scene anywhere or a simple response where the player is said to be.
    */
  private def randomDm(random: Random, outline: AdventureOutline): ScriptedLLMClient = {
    val ids = outline.keyLocations.map(_.id) :+ "made_up_place"
    def scene(): Reply = {
      val id = ids(random.nextInt(ids.size))
      val exits = random.shuffle(Directions).take(random.nextInt(3)).map(_ -> ids(random.nextInt(ids.size)))
      Reply.text(DmAnswers.scene(s"You are in $id. ${random.alphanumeric.take(8).mkString}", id, id, exits))
    }
    ScriptedLLMClient.respondingTo { case conversation: Conversation =>
      val firstCall = conversation.messages.lastOption.exists {
        case user: UserMessage => !user.content.contains(ExitGuard.CorrectionLead)
        case _ => false
      }
      random.nextInt(10) match {
        case 0 | 1 if firstCall =>
          val arguments = ujson.Obj(
            "direction" -> Directions(random.nextInt(Directions.size)),
            "state" -> States(random.nextInt(States.size)),
            "target_id" -> ids(random.nextInt(ids.size)))
          Reply.toolCall("set_exit_state", ujson.write(arguments))
        case 2 | 3 =>
          Reply.text(DmAnswers.simple("Nothing changes.", ids(random.nextInt(ids.size)), "wait"))
        case _ => scene()
      }
    }
  }

  private def play(seed: Long, turns: Int): Unit = withClue(s"seed $seed: ") {
    val random = new Random(seed)
    val outline = randomOutline(random, size = 3 + random.nextInt(5))
    OutlineMap.problems(outline).filter(_.kind == OutlineMap.ProblemKind.Unreachable) shouldBe Nil
    val engine = GameEngine.create(randomDm(random, outline), s"random-$seed", adventureOutline = Some(outline))
    engine.initialize().isRight shouldBe true

    (1 to turns).foldLeft(Vector.empty[PlayedTurn]) { (played, number) =>
      val before = engine.getWorldState
      val result = engine.processCommand(s"command $number", generateAudio = false)
      val after = engine.getWorldState
      val turn = PlayedTurn(
        number,
        s"command $number",
        result.fold(_ => "", _.text),
        before,
        after,
        engine.getCurrentScene,
        engine.getInventory,
        error = result.left.toOption.map(_.message))

      withClue(s"turn $number: ") {
        // no move through an exit that is not open, a narration every turn, and scene and world in the same place
        PlaytestChecks.turn(turn, played).filter(_.hard) shouldBe Nil
        // where the player is (nowhere yet, if no scene has been shown) is a location the world knows as visited
        after.currentLocation.foreach(id => after.locations.find(_.id == id).map(_.visited) shouldBe Some(true))
        // a location has one exit in each direction, as the outline's map does
        after.locations.foreach(l => l.exits.map(_.direction).distinct shouldBe l.exits.map(_.direction))
        // an open exit has nothing in its way
        after.locations.flatMap(_.exits).filter(_.state.passable).flatMap(_.obstacle) shouldBe Nil
        // the game saves and loads as it is
        val state = engine.getGameState("game", None, None)
        val restored = GameStateCodec.fromJson(ujson.read(ujson.write(GameStateCodec.toJson(state))))
        restored.worldState shouldBe after
        restored.currentScene shouldBe engine.getCurrentScene
      }
      played :+ turn
    }
  }

  test("whatever the Dungeon Master answers, the world stays consistent") {
    (1L to 25L).foreach(seed => play(seed, turns = 30))
  }

  test("a restored game keeps to the same rules") {
    val random = new Random(99)
    val outline = randomOutline(random, size = 5)
    val first = GameEngine.create(randomDm(random, outline), "before", adventureOutline = Some(outline))
    first.initialize().isRight shouldBe true
    (1 to 10).foreach(n => first.processCommand(s"command $n", generateAudio = false))

    val second = GameEngine.create(randomDm(random, outline), "after", adventureOutline = Some(outline))
    second.restoreGameState(first.getGameState("game", None, None))
    second.getWorldState shouldBe first.getWorldState

    (1 to 20).foreach { n =>
      val before = second.getWorldState
      val result = second.processCommand(s"later $n", generateAudio = false)
      val turn = PlayedTurn(n, s"later $n", result.fold(_ => "", _.text), before, second.getWorldState, None, Nil)
      withClue(s"turn $n: ")(
        PlaytestChecks.turn(turn.copy(error = result.left.toOption.map(_.message)), Nil).filter(_.hard) shouldBe Nil)
    }
  }
}
