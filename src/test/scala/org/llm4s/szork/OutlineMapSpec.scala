package org.llm4s.szork

import org.llm4s.szork.game._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class OutlineMapSpec extends AnyFunSuite with Matchers {

  /** An exit whose obstacle goes with its state: none when open or closed, one otherwise. */
  private def exit(direction: String, target: String, state: String = "open") =
    ExitOutline(
      direction,
      target,
      s"A way $direction",
      state,
      Option.when(state != "open" && state != "closed")(s"something $state it"))

  private def location(id: String, exits: ExitOutline*) =
    LocationOutline(id, id.capitalize, s"The $id.", "A place", exits.toList)

  private def outline(locations: LocationOutline*) = AdventureOutline(
    title = "Test",
    mainQuest = "Get out",
    subQuests = Nil,
    keyLocations = locations.toList,
    importantItems = Nil,
    keyCharacters = Nil,
    adventureArc = "In, then out"
  )

  private val sound = outline(
    location("hall", exit("north", "study"), exit("down", "cellar", "locked")),
    location("study", exit("south", "hall")),
    location("cellar", exit("up", "hall"))
  )

  test("a sound map has no problems and is left as it is") {
    OutlineMap.problems(sound) shouldBe Nil
    OutlineMap.repair(sound) shouldBe (sound, Nil)
  }

  test("a way back given as 'back' becomes the opposite of the way there, and is not a problem") {
    def place(id: String, exits: (String, String)*) =
      LocationOutline(
        id,
        id,
        s"The $id",
        "",
        exits = exits.map { case (d, to) => ExitOutline(d, to, "", "open") }.toList)
    val abbey = outline(
      place("gatehouse", "north" -> "cloister"),
      // North leads here from the gatehouse, so "back" to it is south
      place("cloister", "back" -> "gatehouse", "down" -> "crypt", "up" -> "tower"),
      place("crypt", "up" -> "cloister"),
      place("tower", "back" -> "cloister", "forward" -> "crypt")
    )
    val (repaired, notes) = OutlineMap.repair(abbey)
    def exits(id: String) = repaired.keyLocations.find(_.id == id).get.exits.map(e => e.direction -> e.targetId)

    exits("cloister") shouldBe List("south" -> "gatehouse", "down" -> "crypt", "up" -> "tower")
    // The tower is up from the cloister, so its way back is down; nothing fixed leads from the crypt to the tower
    exits("tower") shouldBe List("down" -> "cloister", "forward" -> "crypt")
    notes should have size 2
    notes.head should (include("cloister") and include("now south"))
    OutlineMap.problems(abbey) shouldBe empty

    // Fixed directions are never changed, even when a pair does not line up (a passage may bend), and a direction
    // the location already uses is not taken twice
    val bent = outline(
      place("hall", "north" -> "yard", "east" -> "store"),
      place("yard", "west" -> "hall"),
      place("store", "west" -> "yard", "back" -> "hall"))
    OutlineMap.repair(bent) shouldBe (bent, Nil)
  }

  test("an exit to a location that is not in the outline is reported and dropped") {
    val broken = outline(
      location("hall", exit("north", "study"), exit("west", "smugglers_gap")),
      location("study", exit("south", "hall"), exit("up", "attic", "locked")))

    OutlineMap.problems(broken) shouldBe List(
      "hall: the west exit leads to smugglers_gap, which is not a location",
      "study: the up exit leads to attic, which is not a location")
    val (repaired, dropped) = OutlineMap.repair(broken)
    repaired.keyLocations.map(l => l.id -> l.exits.map(_.direction)) shouldBe List(
      "hall" -> List("north"),
      "study" -> List("south"))
    dropped shouldBe OutlineMap.problems(broken)
    OutlineMap.problems(repaired) shouldBe Nil
  }

  test("an exit that leads back to its own location is reported and dropped") {
    val broken = outline(location("hall", exit("north", "study"), exit("east", "hall", "sealed")), location("study"))

    val (repaired, dropped) = OutlineMap.repair(broken)
    dropped shouldBe List("hall: the east exit leads back to hall itself")
    repaired.keyLocations.head.exits.map(_.direction) shouldBe List("north")
  }

  test("a second exit in the same direction is reported and dropped") {
    val broken =
      outline(location("hall", exit("north", "study"), exit("north", "cellar")), location("study"), location("cellar"))

    val (repaired, dropped) = OutlineMap.repair(broken)
    dropped shouldBe List("hall: a second north exit, to cellar")
    repaired.keyLocations.head.exits.map(_.targetId) shouldBe List("study")
  }

  test("a location written twice is reported and its second copy dropped") {
    val broken = outline(
      location("hall", exit("north", "study")),
      location("study", exit("south", "hall")),
      location("study", exit("south", "hall"), exit("up", "attic")))

    val (repaired, dropped) = OutlineMap.repair(broken)
    dropped shouldBe List("study: a second location with this id")
    repaired.keyLocations shouldBe broken.keyLocations.take(2)
    OutlineMap.problems(repaired) shouldBe Nil
  }

  test("a location no exit leads to is reported, and kept since no repair is certain") {
    val broken =
      outline(location("hall", exit("north", "study")), location("study", exit("south", "hall")), location("vault"))

    OutlineMap.problems(broken) shouldBe List("vault: cannot be reached from the starting location (hall)")
    OutlineMap.repair(broken) shouldBe (broken, Nil)
  }

  test("a location reached only through an exit that is not open still counts as reachable") {
    OutlineMap.problems(outline(location("hall", exit("down", "cellar", "hidden")), location("cellar"))) shouldBe Nil
  }

  test("a locked, sealed or hidden exit with no obstacle, and an open exit with one, are reported and kept") {
    def bare(direction: String, target: String, state: String, obstacle: Option[String] = None) =
      ExitOutline(direction, target, s"A way $direction", state, obstacle)
    val broken = outline(
      location(
        "hall",
        bare("north", "study", "locked"),
        bare("east", "study", "sealed", Some("  ")),
        bare("down", "cellar", "hidden"),
        bare("west", "cellar", "open", Some("a heavy bar")),
        // sound: a plain closed door, a stuck one, and the others with what their states need
        bare("south", "study", "closed"),
        bare("up", "study", "closed", Some("swollen shut")),
        bare("in", "cellar", "locked", Some("a padlock")),
        bare("out", "cellar", "hidden", Some("behind the tapestry")),
        bare("left", "study", "open")
      ),
      location("study"),
      location("cellar")
    )

    OutlineMap.problems(broken) shouldBe List(
      "hall: the north exit to study is locked but gives no obstacle to overcome",
      "hall: the east exit to study is sealed but gives no obstacle to overcome",
      "hall: the down exit to cellar is hidden but does not say how it is found",
      "hall: the west exit to cellar is open but has an obstacle (a heavy bar)"
    )
    OutlineMap.repair(broken) shouldBe (broken, Nil)
  }

  test("location ids are matched as the world state matches them: exactly") {
    val broken = outline(location("hall", exit("north", "Study")), location("study", exit("south", "hall")))
    OutlineMap.problems(broken).head should include("leads to Study, which is not a location")
  }

  test("a character placed somewhere that is not a location is reported") {
    val placed = sound.copy(keyCharacters = List(
      CharacterOutline("Mara", "guide", "calm", Some("study")),
      CharacterOutline("Voss", "inspector", "cold", Some("city_office")),
      CharacterOutline("Finch", "missing keeper", "absent", None)
    ))
    OutlineMap.problems(placed) shouldBe List("Voss: is placed at city_office, which is not a location")
    OutlineMap.repair(placed) shouldBe (placed, Nil)
  }

  test("everything but the dropped exits survives a repair") {
    val broken = outline(location("hall", exit("north", "study"), exit("out", "nowhere")), location("study"))
    val (repaired, _) = OutlineMap.repair(broken)
    repaired.copy(keyLocations = Nil) shouldBe broken.copy(keyLocations = Nil)
    repaired.keyLocations.map(_.copy(exits = Nil)) shouldBe broken.keyLocations.map(_.copy(exits = Nil))
  }
}
