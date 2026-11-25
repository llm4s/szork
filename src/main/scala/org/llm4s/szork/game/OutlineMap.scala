package org.llm4s.szork.game

/** The map an [[AdventureOutline]] plans: its locations and the exits between them.
  *
  * The outline schema checks each exit's direction and state but cannot say that its target is one of the outline's
  * locations, so a designer may plan an exit to a place it never describes, or use one for a wall or another corner of
  * the same room. An outline a client sends, or one read from disk, is not held to the schema at all. Either would be
  * seeded into the game's world as it is ([[WorldSnapshot.fromOutline]]).
  */
object OutlineMap {

  /** What kind of thing is wrong with an outline. The first four are what [[repair]] drops. */
  enum ProblemKind {

    /** A location whose id an earlier location has (a designer sometimes writes one twice). */
    case RepeatedLocation

    /** An exit back to its own location. */
    case ExitToItself

    /** An exit to a location that is not in the outline. */
    case ExitToNowhere

    /** A second exit in a direction the location already has. */
    case RepeatedDirection

    /** An outline with no locations: there is nowhere to start. */
    case NoLocations

    /** An exit whose direction is not one of [[AdventureOutlineSchema.Directions]]. */
    case UnknownDirection

    /** An exit whose state is not one of [[AdventureOutlineSchema.ExitStates]] (the game keeps it impassable). */
    case UnknownState

    /** An exit whose state and obstacle do not go together. */
    case ObstacleMismatch

    /** A starting location with exits, none of which the player can take at once. */
    case StartShut

    /** A location no chain of exits connects to the starting one: part of the adventure cannot be played. */
    case Unreachable

    /** A character placed somewhere that is not a location. */
    case MisplacedCharacter

    /** A character with no name, or with another character's name. */
    case CharacterName

    /** An item placed somewhere that is not a location. */
    case MisplacedItem
  }

  /** One thing wrong with an outline: its kind, and a line saying what and where. */
  final case class Problem(kind: ProblemKind, text: String) {
    override def toString: String = text
  }

  /** What [[review]] makes of an outline: the outline repaired, what was dropped from it, the ways back that were
    * turned round, and what is still wrong with the repaired outline.
    */
  final case class Review(
    outline: AdventureOutline,
    dropped: List[Problem],
    turned: List[String],
    remaining: List[Problem]) {

    /** Everything wrong with the outline as it was given: what was dropped, then what remains. */
    def problems: List[Problem] = dropped ++ remaining
  }

  /** `outline` without what cannot be played, and one line for each thing dropped: a location whose id an earlier
    * location has, an exit to a location that is not in the outline, an exit back to its own location, and a second
    * exit in a direction the location already has. One thing is put right rather than dropped: a way back given as
    * "back" (or another direction relative to the player) becomes the opposite of the way there ([[alignReturns]]).
    * Nothing else is changed; in particular a location that cannot be reached is kept (see [[problems]]).
    */
  def repair(outline: AdventureOutline): (AdventureOutline, List[String]) = {
    val checked = review(outline)
    (checked.outline, checked.dropped.map(_.text) ++ checked.turned)
  }

  /** What is wrong with `outline`, one problem each: what [[repair]] drops; then what is wrong with the repaired
    * outline (see [[review]]). A way back that [[repair]] turns round is put right, not a problem.
    */
  def problems(outline: AdventureOutline): List[Problem] = review(outline).problems

  /** `outline` repaired ([[repair]]) and what is wrong with it, in one pass. What remains wrong with the repaired
    * outline is, in order: no locations at all; each exit whose direction or state is not one the schema allows, or
    * whose state and obstacle do not go together ([[obstacleProblem]]); a starting location none of whose exits can be
    * taken at once; the locations that no chain of exits (whatever their state) connects to the first, starting,
    * location; the characters placed somewhere that is not a location (the game then places them nowhere, see
    * [[WorldSnapshot.fromOutline]]), and those with no name or a name another has; and the items placed somewhere that
    * is not a location.
    */
  def review(outline: AdventureOutline): Review = {
    val locations = outline.keyLocations.distinctBy(_.id)
    val repeated = outline.keyLocations
      .diff(locations)
      .map(l => Problem(ProblemKind.RepeatedLocation, s"${l.id}: a second location with this id"))
    val ids = locations.map(_.id).toSet
    val kept = locations.map { location =>
      val (exits, dropped) = location.exits.foldLeft((Vector.empty[ExitOutline], Vector.empty[Problem])) {
        case ((exits, dropped), exit) =>
          exitProblem(location, exit, ids, exits.map(_.direction).toSet)
            .fold((exits :+ exit, dropped))(problem => (exits, dropped :+ problem))
      }
      (location.copy(exits = exits.toList), dropped)
    }
    val (aligned, turned) = alignReturns(kept.map(_._1))
    val repaired = outline.copy(keyLocations = aligned)
    Review(repaired, repeated ++ kept.flatMap(_._2), turned, remaining(repaired))
  }

  // What is wrong with an outline that repair has nothing more to drop from
  private def remaining(outline: AdventureOutline): List[Problem] = {
    val ids = outline.keyLocations.map(_.id).toSet
    val empty =
      Option.when(outline.keyLocations.isEmpty)(Problem(ProblemKind.NoLocations, "the outline has no locations"))
    val exits = for {
      location <- outline.keyLocations
      exit <- location.exits
      problem <- directionProblem(location, exit)
        .orElse(stateProblem(location, exit))
        .orElse(obstacleProblem(location, exit))
    } yield problem
    val start = outline.keyLocations.headOption
    val shut = start.filter(s => s.exits.nonEmpty && !s.exits.exists(takenAtOnce)).map { s =>
      Problem(ProblemKind.StartShut, s"${s.id}: the starting location has no exit the player can take at once")
    }
    val unreachable = start.toList.flatMap { s =>
      unreached(outline).map { location =>
        Problem(ProblemKind.Unreachable, s"${location.id}: cannot be reached from the starting location (${s.id})")
      }
    }
    val misplaced = outline.keyCharacters.flatMap { character =>
      character.location.filterNot(ids).map { id =>
        Problem(ProblemKind.MisplacedCharacter, s"${character.name}: is placed at $id, which is not a location")
      }
    }
    val names = outline.keyCharacters.map(_.name.trim)
    val blank = Option.when(names.exists(_.isEmpty))(Problem(ProblemKind.CharacterName, "a character has no name"))
    val twice = names.filter(_.nonEmpty).groupBy(_.toLowerCase).values.filter(_.sizeIs > 1).map { same =>
      Problem(ProblemKind.CharacterName, s"${same.head}: ${same.size} characters have this name")
    }
    val items = outline.importantItems.flatMap { item =>
      item.location.filterNot(ids).map { id =>
        Problem(ProblemKind.MisplacedItem, s"${item.name}: is placed at $id, which is not a location")
      }
    }
    empty.toList ++ exits ++ shut ++ unreachable ++ misplaced ++ blank ++ twice ++ items
  }

  // An exit the player can go through now, or simply open and go through
  private def takenAtOnce(exit: ExitOutline): Boolean =
    exit.state == ExitState.Open.wire || (exit.state == ExitState.Closed.wire && !hasObstacle(exit))

  private def hasObstacle(exit: ExitOutline): Boolean = exit.obstacle.exists(_.trim.nonEmpty)

  private val Opposite: Map[String, String] =
    Seq("north" -> "south", "east" -> "west", "up" -> "down", "in" -> "out").flatMap { case (a, b) =>
      Seq(a -> b, b -> a)
    }.toMap

  private val Directions: Set[String] = AdventureOutlineSchema.Directions.toSet

  /** Gives each way back the opposite direction of the way there, where the designer gave it relative to the player.
    *
    * A designer sometimes writes "north" from A to B and "back" from B to A. The player, who went north, then reads of
    * a door "back" (or, as the Dungeon Master guesses, south) that the map does not have. Such an exit - any whose
    * direction is not one an outline allows ([[AdventureOutlineSchema.Directions]]) - takes the opposite of the
    * direction that leads to its location from its target, if its location has no exit that way already. Exits in the
    * outline's directions are never changed, even when a pair of them does not line up: a passage may bend.
    */
  private def alignReturns(locations: List[LocationOutline]): (List[LocationOutline], List[String]) = {
    // The direction that leads from one location to another
    val ways = (for {
      from <- locations
      exit <- from.exits if Directions(exit.direction)
    } yield (from.id, exit.targetId) -> exit.direction).toMap
    val aligned = locations.map { location =>
      location.exits.foldLeft((Vector.empty[ExitOutline], Vector.empty[String])) { case ((done, notes), exit) =>
        val wanted = Option
          .unless(Directions(exit.direction))(ways.get((exit.targetId, location.id)).flatMap(Opposite.get))
          .flatten
          .filterNot(direction =>
            location.exits.exists(_.direction == direction) || done.exists(_.direction == direction))
        wanted match {
          case Some(direction) =>
            val note = s"${location.id}: the ${exit.direction} exit to ${exit.targetId} is now $direction, the " +
              s"opposite of the way from ${exit.targetId}"
            (done :+ exit.copy(direction = direction), notes :+ note)
          case None => (done :+ exit, notes)
        }
      } match { case (exits, notes) => (location.copy(exits = exits.toList), notes) }
    }
    (aligned.map(_._1), aligned.flatMap(_._2))
  }

  private def directionProblem(location: LocationOutline, exit: ExitOutline): Option[Problem] =
    Option.unless(Directions(exit.direction)) {
      Problem(
        ProblemKind.UnknownDirection,
        s"${location.id}: the exit to ${exit.targetId} has direction '${exit.direction}', which is not one of " +
          AdventureOutlineSchema.Directions.mkString(", ")
      )
    }

  private def stateProblem(location: LocationOutline, exit: ExitOutline): Option[Problem] =
    Option.when(ExitState.parse(exit.state).isEmpty) {
      Problem(
        ProblemKind.UnknownState,
        s"${location.id}: the ${exit.direction} exit to ${exit.targetId} has state '${exit.state}', which is not " +
          s"one of ${AdventureOutlineSchema.ExitStates.mkString(", ")}"
      )
    }

  /** What is wrong with `exit`'s state and obstacle together: a locked, sealed or blocked exit that does not say what
    * must be overcome, a hidden one that does not say how it is found, or an open one that has an obstacle. (A closed
    * exit may have one or not: without, the player simply opens it.) Reported and not repaired: whether an open exit
    * with an obstacle was meant open, or a locked one without was meant closed, is the designer's to say.
    */
  private def obstacleProblem(location: LocationOutline, exit: ExitOutline): Option[Problem] = {
    val obstacle = exit.obstacle.map(_.trim).filter(_.nonEmpty)
    val where = s"${location.id}: the ${exit.direction} exit to ${exit.targetId} is ${exit.state}"
    (ExitState.parse(exit.state), obstacle) match {
      case (Some(ExitState.Locked | ExitState.Sealed | ExitState.Blocked), None) =>
        Some(s"$where but gives no obstacle to overcome")
      case (Some(ExitState.Hidden), None) => Some(s"$where but does not say how it is found")
      case (Some(ExitState.Open), Some(what)) => Some(s"$where but has an obstacle ($what)")
      case _ => None
    }
  }.map(Problem(ProblemKind.ObstacleMismatch, _))

  private def exitProblem(
    location: LocationOutline,
    exit: ExitOutline,
    ids: Set[String],
    taken: Set[String]): Option[Problem] =
    if (exit.targetId == location.id)
      Some(
        Problem(
          ProblemKind.ExitToItself,
          s"${location.id}: the ${exit.direction} exit leads back to ${location.id} itself"))
    else if (!ids(exit.targetId))
      Some(
        Problem(
          ProblemKind.ExitToNowhere,
          s"${location.id}: the ${exit.direction} exit leads to ${exit.targetId}, which is not a location"))
    else if (taken(exit.direction))
      Some(
        Problem(ProblemKind.RepeatedDirection, s"${location.id}: a second ${exit.direction} exit, to ${exit.targetId}"))
    else None

  private def unreached(outline: AdventureOutline): List[LocationOutline] = {
    val exits = outline.keyLocations.map(l => l.id -> l.exits.map(_.targetId)).toMap
    @annotation.tailrec
    def reach(frontier: List[String], seen: Set[String]): Set[String] = frontier match {
      case Nil => seen
      case id :: rest =>
        val found = exits.getOrElse(id, Nil).filterNot(seen)
        reach(rest ++ found, seen ++ found)
    }
    val reached = outline.keyLocations.headOption.fold(Set.empty[String])(start => reach(List(start.id), Set(start.id)))
    outline.keyLocations.filterNot(l => reached(l.id))
  }
}
