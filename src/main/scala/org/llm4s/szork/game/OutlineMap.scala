package org.llm4s.szork.game

/** The map an [[AdventureOutline]] plans: its locations and the exits between them.
  *
  * The outline schema checks each exit's direction and state but cannot say that its target is one of the outline's
  * locations, so a designer may plan an exit to a place it never describes, or use one for a wall or another corner of
  * the same room. [[WorldSnapshot.fromOutline]] would seed those into the game's world as they are.
  */
object OutlineMap {

  /** `outline` without what cannot be played, and one line for each thing dropped: a location whose id an earlier
    * location has (a designer sometimes writes one twice), an exit to a location that is not in the outline, an exit
    * back to its own location, and a second exit in a direction the location already has. One thing is put right rather
    * than dropped: a way back given as "back" (or another direction relative to the player) becomes the opposite of the
    * way there ([[alignReturns]]). Nothing else is changed; in particular a location that cannot be reached is kept
    * (see [[problems]]).
    */
  def repair(outline: AdventureOutline): (AdventureOutline, List[String]) = {
    val (repaired, dropped, turned) = repaired3(outline)
    (repaired, dropped ++ turned)
  }

  // The repaired outline, what was dropped from it, and the ways back that were turned round
  private def repaired3(outline: AdventureOutline): (AdventureOutline, List[String], List[String]) = {
    val locations = outline.keyLocations.distinctBy(_.id)
    val repeated = outline.keyLocations.diff(locations).map(l => s"${l.id}: a second location with this id")
    val ids = locations.map(_.id).toSet
    val repaired = locations.map { location =>
      val (kept, dropped) = location.exits.foldLeft((Vector.empty[ExitOutline], Vector.empty[String])) {
        case ((kept, dropped), exit) =>
          exitProblem(location, exit, ids, kept.map(_.direction).toSet)
            .fold((kept :+ exit, dropped))(problem => (kept, dropped :+ problem))
      }
      (location.copy(exits = kept.toList), dropped)
    }
    val (aligned, turned) = alignReturns(repaired.map(_._1))
    (outline.copy(keyLocations = aligned), repeated ++ repaired.flatMap(_._2), turned)
  }

  private val Opposite: Map[String, String] =
    Seq("north" -> "south", "east" -> "west", "up" -> "down", "in" -> "out").flatMap { case (a, b) =>
      Seq(a -> b, b -> a)
    }.toMap

  // Directions that mean the same wherever the player came from
  private val Fixed: Set[String] = Set("north", "south", "east", "west", "up", "down")

  /** Gives each way back the opposite direction of the way there, where the designer gave it relative to the player.
    *
    * A designer sometimes writes "north" from A to B and "back" from B to A. The player, who went north, then reads of
    * a door "back" (or, as the Dungeon Master guesses, south) that the map does not have. Such an exit - any whose
    * direction is not a compass point, up or down - takes the opposite of the fixed direction that leads to its
    * location from its target, if its location has no exit that way already. Exits in fixed directions are never
    * changed, even when a pair of them does not line up: a passage may bend.
    */
  private def alignReturns(locations: List[LocationOutline]): (List[LocationOutline], List[String]) = {
    // The fixed direction that leads from one location to another
    val ways = (for {
      from <- locations
      exit <- from.exits if Fixed(exit.direction)
    } yield (from.id, exit.targetId) -> exit.direction).toMap
    val aligned = locations.map { location =>
      location.exits.foldLeft((Vector.empty[ExitOutline], Vector.empty[String])) { case ((done, notes), exit) =>
        val wanted = Option
          .unless(Fixed(exit.direction))(ways.get((exit.targetId, location.id)).flatMap(Opposite.get))
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

  /** What is wrong with `outline`'s map, one line each: what [[repair]] drops; then, of the exits it keeps, each whose
    * state and obstacle do not go together ([[obstacleProblem]]); then the locations that no chain of exits (whatever
    * their state) connects to the first, starting, location; then the characters placed somewhere that is not a
    * location (the game then places them nowhere, see [[WorldSnapshot.fromOutline]]).
    */
  def problems(outline: AdventureOutline): List[String] = {
    // A way back that was turned round is put right, not a problem
    val (repaired, dropped, _) = repaired3(outline)
    val obstacles = for {
      location <- repaired.keyLocations
      exit <- location.exits
      problem <- obstacleProblem(location, exit)
    } yield problem
    val ids = repaired.keyLocations.map(_.id).toSet
    val misplaced = repaired.keyCharacters.flatMap { character =>
      character.location.filterNot(ids).map(id => s"${character.name}: is placed at $id, which is not a location")
    }
    dropped ++ obstacles ++ unreachable(repaired).map { location =>
      s"${location.id}: cannot be reached from the starting location (${outline.keyLocations.head.id})"
    } ++ misplaced
  }

  /** What is wrong with `exit`'s state and obstacle together: a locked or sealed exit that does not say what must be
    * overcome, a hidden one that does not say how it is found, or an open one that has an obstacle. (A closed exit may
    * have one or not: without, the player simply opens it.) Reported and not repaired: whether an open exit with an
    * obstacle was meant open, or a locked one without was meant closed, is the designer's to say.
    */
  private def obstacleProblem(location: LocationOutline, exit: ExitOutline): Option[String] = {
    val obstacle = exit.obstacle.map(_.trim).filter(_.nonEmpty)
    val where = s"${location.id}: the ${exit.direction} exit to ${exit.targetId} is ${exit.state}"
    (exit.state, obstacle) match {
      case ("locked" | "sealed", None) => Some(s"$where but gives no obstacle to overcome")
      case ("hidden", None) => Some(s"$where but does not say how it is found")
      case ("open", Some(what)) => Some(s"$where but has an obstacle ($what)")
      case _ => None
    }
  }

  private def exitProblem(
    location: LocationOutline,
    exit: ExitOutline,
    ids: Set[String],
    taken: Set[String]): Option[String] =
    if (exit.targetId == location.id)
      Some(s"${location.id}: the ${exit.direction} exit leads back to ${location.id} itself")
    else if (!ids(exit.targetId))
      Some(s"${location.id}: the ${exit.direction} exit leads to ${exit.targetId}, which is not a location")
    else if (taken(exit.direction)) Some(s"${location.id}: a second ${exit.direction} exit, to ${exit.targetId}")
    else None

  private def unreachable(outline: AdventureOutline): List[LocationOutline] = {
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
