package org.llm4s.szork

import org.llm4s.szork.game._
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class OutlineMapSpec extends AnyFunSuite with Matchers with LoneElement {

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

  private def texts(outline: AdventureOutline): List[String] = OutlineMap.problems(outline).map(_.text)

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
    // nothing leads from the crypt to the tower, so "forward" cannot be put right: it is reported
    texts(abbey) shouldBe List(
      "tower: the exit to crypt has direction 'forward', which is not one of north, south, east, west, up, down, in, out")

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

    texts(broken) shouldBe List(
      "hall: the west exit leads to smugglers_gap, which is not a location",
      "study: the up exit leads to attic, which is not a location")
    val (repaired, dropped) = OutlineMap.repair(broken)
    repaired.keyLocations.map(l => l.id -> l.exits.map(_.direction)) shouldBe List(
      "hall" -> List("north"),
      "study" -> List("south"))
    dropped shouldBe texts(broken)
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

    texts(broken) shouldBe List("vault: cannot be reached from the starting location (hall)")
    OutlineMap.repair(broken) shouldBe (broken, Nil)
  }

  test("a location reached only through an exit that is not open still counts as reachable") {
    // the start's only exit is hidden, which is a problem of its own, but the cellar is not unreachable
    OutlineMap
      .problems(outline(location("hall", exit("down", "cellar", "hidden")), location("cellar")))
      .map(_.kind) shouldBe List(OutlineMap.ProblemKind.StartShut)
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
        bare("out", "cellar", "hidden", Some("behind the tapestry"))
      ),
      location("study"),
      location("cellar")
    )

    texts(broken) shouldBe List(
      "hall: the north exit to study is locked but gives no obstacle to overcome",
      "hall: the east exit to study is sealed but gives no obstacle to overcome",
      "hall: the down exit to cellar is hidden but does not say how it is found",
      "hall: the west exit to cellar is open but has an obstacle (a heavy bar)"
    )
    OutlineMap.repair(broken) shouldBe (broken, Nil)
  }

  test("location ids are matched as the world state matches them: exactly") {
    val broken = outline(location("hall", exit("north", "Study")), location("study", exit("south", "hall")))
    texts(broken).head should include("leads to Study, which is not a location")
  }

  test("a character placed somewhere that is not a location is reported") {
    val placed = sound.copy(keyCharacters = List(
      CharacterOutline("Mara", "guide", "calm", Some("study")),
      CharacterOutline("Voss", "inspector", "cold", Some("city_office")),
      CharacterOutline("Finch", "missing keeper", "absent", None)
    ))
    texts(placed) shouldBe List("Voss: is placed at city_office, which is not a location")
    OutlineMap.repair(placed) shouldBe (placed, Nil)
  }

  test("an exit whose direction or state the schema does not allow is reported (a client's outline is not checked)") {
    val sent = outline(
      location(
        "hall",
        exit("north", "study"),
        ExitOutline("north-east", "study", "A crooked passage", "open"),
        ExitOutline("down", "study", "A trapdoor", "ajar")),
      location("study", exit("south", "hall"))
    )
    texts(sent) shouldBe List(
      "hall: the exit to study has direction 'north-east', which is not one of north, south, east, west, up, down, in, out",
      "hall: the down exit to study has state 'ajar', which is not one of open, closed, locked, sealed, blocked, hidden"
    )
  }

  test("a blocked exit needs an obstacle, as a locked or sealed one does") {
    val blocked = outline(
      location("hall", exit("north", "study"), ExitOutline("east", "study", "Rubble", "blocked")),
      location("study", exit("south", "hall")))
    texts(blocked) shouldBe List("hall: the east exit to study is blocked but gives no obstacle to overcome")
    val cleared = outline(
      location("hall", exit("north", "study"), exit("east", "study", "blocked")),
      location("study", exit("south", "hall")))
    OutlineMap.problems(cleared) shouldBe Nil
  }

  test("an outline with no locations is reported, and is not an error") {
    OutlineMap.problems(outline()).map(_.kind) shouldBe List(OutlineMap.ProblemKind.NoLocations)
  }

  test("a starting location whose exits cannot be taken at once is reported; a plain closed door can be") {
    val shut = outline(location("hall", exit("north", "study", "locked")), location("study", exit("south", "hall")))
    texts(shut) shouldBe List("hall: the starting location has no exit the player can take at once")
    val door = outline(location("hall", exit("north", "study", "closed")), location("study", exit("south", "hall")))
    OutlineMap.problems(door) shouldBe Nil
    // a single room with no exits is not shut in
    OutlineMap.problems(outline(location("hall"))) shouldBe Nil
  }

  test("a character with no name, or with another's name, and an item placed nowhere are reported") {
    val crowded = sound.copy(
      keyCharacters = List(
        CharacterOutline("Mara", "guide", "calm"),
        CharacterOutline(" mara ", "twin", "calmer"),
        CharacterOutline("  ", "nobody", "blank")),
      importantItems = List(
        ItemOutline("key", "A key", "Opens the cellar", Some("study")),
        ItemOutline("map", "A map", "x", Some("loft")))
    )
    texts(crowded) shouldBe List(
      "a character has no name",
      "Mara: 2 characters have this name",
      "map: is placed at loft, which is not a location")
  }

  test("in and out are the outline's own directions: never turned, and a way back from them is their opposite") {
    def place(id: String, exits: (String, String)*) =
      LocationOutline(
        id,
        id,
        s"The $id",
        "",
        exits = exits.map { case (d, to) => ExitOutline(d, to, "", "open") }.toList)
    val mill = outline(
      place("yard", "north" -> "mill"),
      place("mill", "out" -> "yard", "in" -> "loft"),
      place("loft", "back" -> "mill"))
    val (repaired, notes) = OutlineMap.repair(mill)
    repaired.keyLocations.map(_.exits.map(_.direction)) shouldBe List(List("north"), List("out", "in"), List("out"))
    notes.loneElement should include("now out")
  }

  test("the adventures in the library decode and have no problems") {
    val dirs = java.nio.file.Files
      .list(java.nio.file.Paths.get("adventures"))
      .toArray
      .toList
      .map(_.asInstanceOf[java.nio.file.Path])
    val files = dirs.map(_.resolve("adventure.json")).filter(java.nio.file.Files.exists(_))
    files should not be empty
    files.foreach { file =>
      withClue(file) {
        val json = ujson.read(java.nio.file.Files.readString(file))
        val read = AdventureGenerator.parseOutline(json("outline"))
        read.isRight shouldBe true
        read.foreach(o => OutlineMap.problems(o) shouldBe Nil)
      }
    }
  }

  test("everything but the dropped exits survives a repair") {
    val broken = outline(location("hall", exit("north", "study"), exit("out", "nowhere")), location("study"))
    val (repaired, _) = OutlineMap.repair(broken)
    repaired.copy(keyLocations = Nil) shouldBe broken.copy(keyLocations = Nil)
    repaired.keyLocations.map(_.copy(exits = Nil)) shouldBe broken.keyLocations.map(_.copy(exits = Nil))
  }
}
