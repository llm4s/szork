package org.llm4s.szork.game

import upickle.default.*

/** Whether an exit can be passed. The adventure outline states it as a string (one of
  * [[AdventureOutlineSchema.ExitStates]]); the world state keeps it as this enum.
  */
enum ExitState(val wire: String) {
  case Open extends ExitState("open")
  case Closed extends ExitState("closed")
  case Locked extends ExitState("locked")
  case Sealed extends ExitState("sealed")
  case Blocked extends ExitState("blocked")
  case Hidden extends ExitState("hidden")

  def passable: Boolean = this == ExitState.Open
}

object ExitState {

  /** The exit state named `name` (case-insensitive), if there is one. */
  def parse(name: String): Option[ExitState] = values.find(_.wire == name.trim.toLowerCase)

  val names: Seq[String] = values.toSeq.map(_.wire)

  given ReadWriter[ExitState] =
    readwriter[String].bimap(_.wire, s => parse(s).getOrElse(throw new IllegalArgumentException(s"exit state $s")))
}

/** How far the player has got with a quest. */
enum QuestStatus(val wire: String) {
  case NotStarted extends QuestStatus("not_started")
  case Active extends QuestStatus("active")
  case Completed extends QuestStatus("completed")
  case Failed extends QuestStatus("failed")
}

object QuestStatus {
  def parse(name: String): Option[QuestStatus] = values.find(_.wire == name.trim.toLowerCase)

  val names: Seq[String] = values.toSeq.map(_.wire)

  given ReadWriter[QuestStatus] =
    readwriter[String].bimap(_.wire, s => parse(s).getOrElse(throw new IllegalArgumentException(s"quest status $s")))
}

/** An exit as it stands now: which way, where it leads, whether it is passable and, if not, what is in the way. */
final case class ExitRecord(direction: String, targetId: String, state: ExitState, obstacle: Option[String])
    derives ReadWriter

/** A location as it stands now: its exits, facts about it the Dungeon Master recorded (a lever pulled, a chest
  * emptied), and whether the player has been there.
  */
final case class LocationRecord(
  id: String,
  name: String,
  exits: List[ExitRecord],
  flags: Map[String, String],
  visited: Boolean
) derives ReadWriter {

  /** Whether this is only a placeholder: a location a tool named before any scene described it (its name is its id),
    * whose real name and ways out the first scene there gives.
    */
  def isPlaceholder: Boolean = !visited && name == id
}

/** A character: whether the player has met them, where they were last seen, notes on what passed between them, and the
  * location the adventure's design first puts them in (none for a character met only through what they left).
  */
final case class NpcRecord(
  name: String,
  role: Option[String],
  met: Boolean,
  lastSeenAt: Option[String],
  notes: List[String],
  foundAt: Option[String] = None
) derives ReadWriter

/** A quest (the main one is `main`) and the player's progress with it. */
final case class QuestRecord(id: String, description: String, status: QuestStatus, notes: List[String])
    derives ReadWriter

/** The state of the game world: an immutable snapshot, kept by [[WorldState]] and stored in saves.
  *
  * It holds what must survive the Dungeon Master forgetting older turns (the conversation each model call is sent is
  * pruned): exit states, location facts, the characters met and quest progress.
  */
final case class WorldSnapshot(
  currentLocation: Option[String],
  locations: List[LocationRecord],
  npcs: List[NpcRecord],
  quests: List[QuestRecord]
) derives ReadWriter {

  /** The location with id or name `query` (case-insensitive), or the current location when `query` is empty. */
  def location(query: Option[String]): Option[LocationRecord] =
    query.orElse(currentLocation).flatMap { q =>
      val key = q.trim.toLowerCase
      locations
        .find(_.id.toLowerCase == key)
        .orElse(locations.find(_.name.toLowerCase == key))
    }

  /** The character `name` names (case-insensitive): the one with that name - a met one first, if there are two - or
    * else the one character whose name has it as a whole word ("Mara" for "Mara Vance"), preferring the one met when
    * several do.
    */
  def npc(name: String): Option[NpcRecord] = {
    val key = name.trim.toLowerCase
    val named = npcs.filter(_.name.toLowerCase == key)
    def unique(candidates: List[NpcRecord]) = Option.when(candidates.size == 1)(candidates.head)
    named
      .find(_.met)
      .orElse(named.headOption)
      .orElse(
        Option
          .when(key.nonEmpty) {
            val word = s"\\b${java.util.regex.Pattern.quote(key)}\\b".r
            val partly = npcs.filter(n => word.findFirstIn(n.name.toLowerCase).isDefined)
            unique(partly).orElse(unique(partly.filter(_.met)))
          }
          .flatten)
  }

  /** The quest `query` names (case-insensitive): the one with that id ("main quest" and "the main quest" name `main`),
    * else the one with that description, else the one quest whose description has it as a whole phrase. The main quest
    * is found only by its id, so that a quest that merely mentions "main" is never taken for it.
    */
  def quest(query: String): Option[QuestRecord] = {
    val key = WorldState.questKey(query)
    val byId = quests.find(_.id.toLowerCase == key)
    if (key == WorldState.MainQuest || key.isEmpty) byId
    else
      byId
        .orElse(quests.find(_.description.trim.toLowerCase == query.trim.toLowerCase))
        .orElse {
          val phrase = s"\\b${java.util.regex.Pattern.quote(query.trim.toLowerCase)}\\b".r
          val containing = quests.filter(q => phrase.findFirstIn(q.description.toLowerCase).isDefined)
          Option.when(containing.size == 1)(containing.head)
        }
  }

  private[game] def withLocation(record: LocationRecord): WorldSnapshot =
    if (locations.exists(_.id == record.id)) copy(locations = locations.map(l => if (l.id == record.id) record else l))
    else copy(locations = locations :+ record)
}

object WorldSnapshot {
  val empty: WorldSnapshot = WorldSnapshot(None, Nil, Nil, Nil)

  /** The world as the outline plans it: its locations with their exits, its characters (not yet met), and its quests
    * (the main quest active, side quests not started). Without an outline, the world starts empty and is recorded as
    * the game goes.
    */
  def fromOutline(outline: Option[AdventureOutline]): WorldSnapshot = outline.fold(empty) { o =>
    WorldSnapshot(
      currentLocation = None,
      locations = o.keyLocations.map { loc =>
        LocationRecord(
          id = loc.id,
          name = loc.name,
          exits = loc.exits.map { e =>
            ExitRecord(
              direction = WorldState.normalizeDirection(e.direction),
              targetId = e.targetId,
              // the outline schema allows only known states; an unknown one is kept impassable
              state = ExitState.parse(e.state).getOrElse(ExitState.Locked),
              obstacle = e.obstacle
            )
          },
          flags = Map.empty,
          visited = false
        )
      },
      npcs = o.keyCharacters.map { c =>
        // a character placed somewhere that is not one of the outline's locations is placed nowhere
        val foundAt = c.location.filter(id => o.keyLocations.exists(_.id == id))
        NpcRecord(c.name, Some(c.role), met = false, lastSeenAt = None, notes = Nil, foundAt = foundAt)
      },
      quests = QuestRecord(WorldState.MainQuest, o.mainQuest, QuestStatus.Active, Nil) ::
        o.subQuests.zipWithIndex.map { case (q, i) => QuestRecord(s"side-${i + 1}", q, QuestStatus.NotStarted, Nil) }
    )
  }
}

/** A game's world state, read and changed by the Dungeon Master's world-state tools ([[GameTools.forWorld]]) and by
  * scenes (which move the player). Each game has its own; tool calls and saves may touch it from different threads, so
  * every operation is atomic.
  */
final class WorldState(initial: WorldSnapshot = WorldSnapshot.empty) {
  private var state: WorldSnapshot = initial

  def snapshot: WorldSnapshot = synchronized(state)

  /** Replaces the whole state, e.g. when a game starts or a saved game is restored. */
  def replaceWith(snapshot: WorldSnapshot): Unit = synchronized { state = snapshot }

  private def change[A](f: WorldSnapshot => (WorldSnapshot, A)): A = synchronized {
    val (next, result) = f(state)
    state = next
    result
  }

  /** Records that the player is at `locationId` (a scene described it), adding the location if it is new.
    *
    * A new location - one the Dungeon Master made up, since the outline's are seeded - is recorded with the scene's
    * name and `exits` (direction and target), all open: nothing else knows the ways out of it. So is a placeholder, a
    * location a tool named before any scene described it: it takes the scene's name, and the scene's exits in the
    * directions it has none recorded. A known location keeps the exits it has, whose states the scene does not decide.
    */
  def enter(locationId: String, name: String, exits: List[(String, String)] = Nil): Unit = change { s =>
    val ways = exits
      .map((direction, target) => (WorldState.normalizeDirection(direction), target))
      .filter((direction, target) => direction.nonEmpty && target.nonEmpty && target != locationId)
      .distinctBy(_._1)
      .map((direction, target) => ExitRecord(direction, target, ExitState.Open, None))
    val record = s.locations.find(_.id == locationId) match {
      case None => LocationRecord(locationId, name, ways, Map.empty, visited = false)
      case Some(placeholder) if placeholder.isPlaceholder =>
        val recorded = placeholder.exits.map(_.direction).toSet
        placeholder.copy(
          name = Option(name.trim).filter(_.nonEmpty).getOrElse(placeholder.name),
          exits = placeholder.exits ++ ways.filterNot(e => recorded(e.direction)))
      case Some(known) => known
    }
    (s.withLocation(record.copy(visited = true)).copy(currentLocation = Some(locationId)), ())
  }

  /** The location `location` names (default: the current one), or a placeholder for one a tool names before any scene
    * has described it, under its name made a location id. Left when there is no current location, or the name makes no
    * id.
    */
  private def locationOrPlaceholder(s: WorldSnapshot, location: Option[String]): Either[String, LocationRecord] =
    s.location(location) match {
      case Some(found) => Right(found)
      case None =>
        location match {
          case None => Left("The player's location is not known yet; give location_id")
          case Some(named) =>
            val id = WorldState.locationId(named)
            if (id.isEmpty) Left(s"'$named' is not a location id")
            else Right(s.location(Some(id)).getOrElse(LocationRecord(id, id, Nil, Map.empty, visited = false)))
        }
    }

  /** Sets the state of the exit `direction` from `location` (default: the current location).
    *
    * An exit not yet recorded is added when `targetId` is given. The exit back from the target location, if it is
    * recorded, gets the same state (unless the new state is hidden: a way hidden on one side may be plain on the
    * other): the one in the opposite direction, or else the only exit back. An obstacle is kept while the way stays
    * barred; one that is only closed now (unlocked, unsealed, cleared) has none unless it is given. Returns what
    * changed, or why nothing did.
    */
  def setExitState(
    location: Option[String],
    direction: String,
    newState: ExitState,
    targetId: Option[String],
    obstacle: Option[String]
  ): Either[String, String] = change { s =>
    val dir = WorldState.normalizeDirection(direction)
    locationOrPlaceholder(s, location) match {
      case Left(problem) => (s, Left(problem))
      case Right(loc) =>
        val existing = loc.exits.find(_.direction == dir)
        // a new exit's target, named by id or name, is a location id
        val named = targetId.map(t => s.location(Some(t)).map(_.id).getOrElse(WorldState.locationId(t)))
        existing.map(_.targetId).orElse(named.filter(_.nonEmpty)) match {
          case None =>
            (s, Left(s"${loc.id} has no recorded exit $dir; give target_id to record a new one"))
          case Some(target) =>
            val kept = WorldState.obstacleAfter(existing, newState, obstacle)
            val exit = ExitRecord(dir, target, newState, kept)
            val updated = loc.copy(exits = loc.exits.filterNot(_.direction == dir) :+ exit)
            val withExit = s.withLocation(updated)
            // the same way back, from the other side
            val back = for {
              other <- withExit.locations.find(_.id == target)
              if newState != ExitState.Hidden
              reverse <- WorldState.wayBack(other, loc.id, dir)
            } yield (other, reverse)
            val (next, alsoBack) = back match {
              case Some((other, reverse)) =>
                val mirrored =
                  reverse.copy(state = newState, obstacle = WorldState.obstacleAfter(Some(reverse), newState, kept))
                (
                  withExit.withLocation(
                    other.copy(exits = other.exits.map(e => if (e.direction == reverse.direction) mirrored else e))),
                  s" (and the way back, ${reverse.direction} from ${other.id})")
              case None => (withExit, "")
            }
            val was = existing.map(e => s" (was ${e.state.wire})").getOrElse(" (new exit)")
            (next, Right(s"The exit $dir from ${loc.id} to $target is now ${newState.wire}$was$alsoBack"))
        }
    }
  }

  /** Records a fact about `location` (default: the current location); an empty value removes the flag. */
  def setFlag(location: Option[String], flag: String, value: String): Either[String, String] = change { s =>
    locationOrPlaceholder(s, location) match {
      case Left(problem) => (s, Left(problem))
      case Right(loc) =>
        val key = flag.trim
        if (value.trim.isEmpty)
          (s.withLocation(loc.copy(flags = loc.flags - key)), Right(s"Cleared '$key' at ${loc.id}"))
        else
          (s.withLocation(loc.copy(flags = loc.flags + (key -> value.trim))), Right(s"At ${loc.id}, '$key' = '$value'"))
    }
  }

  /** Records that the player met `name` (at `location`, default the current one), with an optional note. */
  def recordNpcMet(name: String, location: Option[String], note: Option[String]): String = change { s =>
    val where = s
      .location(location)
      .map(_.id)
      .orElse(location.map(WorldState.locationId).filter(_.nonEmpty))
      .orElse(s.currentLocation)
    val known = s.npc(name)
    val base = known.getOrElse(NpcRecord(name.trim, None, met = false, lastSeenAt = None, notes = Nil))
    val record =
      base.copy(met = true, lastSeenAt = where.orElse(base.lastSeenAt), notes = WorldState.noted(base.notes, note))
    val npcs =
      if (known.isDefined) s.npcs.map(n => if (n eq base) record else n) else s.npcs :+ record
    val first = if (known.exists(_.met)) "Updated" else "Recorded meeting"
    (s.copy(npcs = npcs), s"$first ${record.name}${where.map(w => s" at $w").getOrElse("")}")
  }

  /** Sets a quest's status (adding the quest if it is new), with an optional note on the progress made. */
  def updateQuest(quest: String, status: QuestStatus, note: Option[String]): String = change { s =>
    s.quest(quest) match {
      case Some(q) =>
        val updated = q.copy(status = status, notes = WorldState.noted(q.notes, note))
        (
          s.copy(quests = s.quests.map(x => if (x.id == q.id) updated else x)),
          s"Quest ${q.id} is now ${status.wire} (was ${q.status.wire})")
      case None =>
        // a game without an outline has no quests seeded: its main quest is recorded under the id that ends the game
        val id =
          if (WorldState.questKey(quest) == WorldState.MainQuest) WorldState.MainQuest
          else s"quest-${s.quests.size + 1}"
        (
          s.copy(quests = s.quests :+ QuestRecord(id, quest.trim, status, note.toList)),
          s"Recorded new quest $id: ${status.wire}")
    }
  }
}

object WorldState {

  /** Seeded from `outline` (empty without one). */
  def seededFrom(outline: Option[AdventureOutline]): WorldState = new WorldState(WorldSnapshot.fromOutline(outline))

  private val shortDirections = Map(
    "n" -> "north",
    "s" -> "south",
    "e" -> "east",
    "w" -> "west",
    "u" -> "up",
    "d" -> "down",
    "ne" -> "northeast",
    "nw" -> "northwest",
    "se" -> "southeast",
    "sw" -> "southwest")

  /** The id of the main quest, whose completion or failure ends the adventure. */
  val MainQuest: String = "main"

  /** A quest query as an id: lower-cased, without a leading "the" or a trailing "quest" ("The main quest" is `main`).
    */
  private[game] def questKey(query: String): String =
    query.trim.toLowerCase.stripPrefix("the ").stripSuffix(" quest").trim

  /** `name` as a location id (lower_snake_case, as the outline's and the scenes' are): "Riddle Gate" is `riddle_gate`.
    * Empty when it has no letters or digits.
    */
  def locationId(name: String): String =
    name.trim.toLowerCase.replaceAll("[^a-z0-9]+", "_").stripPrefix("_").stripSuffix("_")

  private val opposites = Map(
    "north" -> "south",
    "east" -> "west",
    "up" -> "down",
    "northeast" -> "southwest",
    "northwest" -> "southeast",
    "in" -> "out")

  /** The direction opposite `direction`, if it has one. */
  def opposite(direction: String): Option[String] =
    opposites.get(direction).orElse(opposites.collectFirst { case (d, o) if o == direction => d })

  /** The exit of `other` that is the way back to `from` along the exit `direction`: the one in the opposite direction,
    * or else the only exit of `other` to `from`. None when there is none, or several and none is opposite.
    */
  private[game] def wayBack(other: LocationRecord, from: String, direction: String): Option[ExitRecord] = {
    val toFrom = other.exits.filter(_.targetId == from)
    toFrom
      .find(e => opposite(direction).contains(e.direction))
      .orElse(Option.when(toFrom.size == 1)(toFrom.head))
  }

  /** The obstacle of an exit that is now `state`, given `obstacle` or else keeping the one `before` had. An open exit
    * has none, and an exit made closed from barred (unlocked, unsealed, cleared) loses the old one: a closed exit with
    * an obstacle cannot simply be opened.
    */
  private[game] def obstacleAfter(
    before: Option[ExitRecord],
    state: ExitState,
    obstacle: Option[String]
  ): Option[String] =
    if (state.passable) None
    else
      obstacle.orElse(
        before.filterNot(b => state == ExitState.Closed && b.state != ExitState.Closed).flatMap(_.obstacle))

  /** `notes` with `note` added, unless it is blank or already there (the Dungeon Master often repeats one). */
  private[game] def noted(notes: List[String], note: Option[String]): List[String] = {
    def key(text: String) = text.trim.toLowerCase.replaceAll("\\s+", " ").stripSuffix(".")
    note.map(_.trim).filter(_.nonEmpty).filterNot(n => notes.exists(key(_) == key(n))).fold(notes)(notes :+ _)
  }

  def normalizeDirection(direction: String): String = {
    val d = direction.trim.toLowerCase
    shortDirections.getOrElse(d, d)
  }
}
