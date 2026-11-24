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
    * back to its own location, and a second exit in a direction the location already has. Nothing else is changed; in
    * particular a location that cannot be reached is kept (see [[problems]]).
    */
  def repair(outline: AdventureOutline): (AdventureOutline, List[String]) = {
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
    (outline.copy(keyLocations = repaired.map(_._1)), repeated ++ repaired.flatMap(_._2))
  }

  /** What is wrong with `outline`'s map, one line each: what [[repair]] drops; then, of the exits it keeps, each whose
    * state and obstacle do not go together ([[obstacleProblem]]); then the locations that no chain of exits (whatever
    * their state) connects to the first, starting, location; then the characters placed somewhere that is not a
    * location (the game then places them nowhere, see [[WorldSnapshot.fromOutline]]).
    */
  def problems(outline: AdventureOutline): List[String] = {
    val (repaired, dropped) = repair(outline)
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
