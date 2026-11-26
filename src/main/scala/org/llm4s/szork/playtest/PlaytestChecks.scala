package org.llm4s.szork.playtest

import org.llm4s.szork.game.{AdventureOutline, GameEngine, GameScene, OutlineMap, WorldSnapshot}

/** One turn of a playtest: what the player typed, what came back, and the game's state on either side of it.
  * `playerError` is why the player could not choose a command, when it could not (it then looked around).
  */
final case class PlayedTurn(
  number: Int,
  command: String,
  narration: String,
  before: WorldSnapshot,
  after: WorldSnapshot,
  scene: Option[GameScene],
  inventory: List[String],
  refused: Boolean = false,
  usage: Option[GameEngine.TurnUsage] = None,
  error: Option[String] = None,
  playerError: Option[String] = None
)

/** Something a playtest found. A `hard` finding is a broken rule of the game (the playtest fails); a soft one is a sign
  * of a dull or confused game, reported for a person to judge.
  */
final case class Finding(check: String, hard: Boolean, detail: String, turn: Option[Int] = None)

/** The rules a playtest holds a game to. They read only the outline and the turns, so they are the same for a live
  * model and a scripted one.
  */
object PlaytestChecks {

  /** Narrations of at least this many words are compared for repetition; shorter ones ("Taken.") repeat honestly. */
  val MinWordsToCompare: Int = 12

  /** Two narrations sharing this share of their words are counted as a repeat. */
  val RepeatSimilarity: Double = 0.8

  /** A turn that takes more model calls than this is reported: the Dungeon Master is circling. */
  val ManyModelCalls: Long = 6

  /** The kinds of outline problem that are hard findings: part of the adventure (or all of it) cannot be played. */
  val UnplayableOutline: Set[OutlineMap.ProblemKind] =
    Set(OutlineMap.ProblemKind.Unreachable, OutlineMap.ProblemKind.NoLocations)

  /** What is wrong with the map the game starts from. An unreachable location is a hard finding: part of the adventure
    * cannot be played. The rest (a locked exit with no obstacle, a character placed nowhere) make it poorer.
    */
  def outline(outline: AdventureOutline): List[Finding] =
    OutlineMap.problems(outline).map(problem => Finding("outline-map", UnplayableOutline(problem.kind), problem.text))

  /** What is wrong with `turn`, given the turns `earlier` in the game. */
  def turn(turn: PlayedTurn, earlier: Seq[PlayedTurn]): List[Finding] = {
    def finding(check: String, hard: Boolean, detail: String) = Finding(check, hard, detail, Some(turn.number))
    turn.playerError.map(finding("player-failed", hard = false, _)).toList ++ (turn.error match {
      case Some(error) => List(finding("turn-failed", hard = true, error))
      case None =>
        val text = turn.narration.trim
        List(
          Option.when(text.isEmpty)(finding("empty-narration", hard = true, "the turn told the player nothing")),
          Option.when(text.startsWith("{"))(
            finding("raw-answer", hard = true, s"the player was shown the model's JSON: ${text.take(80)}")),
          shutExitUsed(turn).map(finding("closed-exit-used", hard = true, _)),
          for {
            scene <- turn.scene
            here <- turn.after.currentLocation
            if scene.locationId != here
          } yield finding(
            "location-mismatch",
            hard = true,
            s"the scene shown is ${scene.locationId} but the world has the player at $here"),
          Option.when(turn.refused)(
            finding("command-refused", hard = false, s"the input guardrail refused '${turn.command}'")),
          carriedItemsShown(turn).map(finding("carried-item-in-scene", hard = false, _)),
          repeatOf(turn, earlier).map(finding("repeated-narration", hard = false, _)),
          turn.usage
            .filter(_.modelCalls > ManyModelCalls)
            .map(usage => finding("many-model-calls", hard = false, s"the turn took ${usage.modelCalls} model calls"))
        ).flatten
    })
  }

  /** What is wrong with the game as a whole. A playtest whose player never chose a command has tested nothing, which is
    * a hard finding.
    */
  def game(turns: Seq[PlayedTurn]): List[Finding] = {
    val visited = turns.flatMap(_.after.currentLocation).distinct
    List(
      Option.when(turns.sizeIs >= 10 && visited.sizeIs <= 1)(
        Finding("never-left-the-start", hard = false, s"${turns.size} turns were all played at ${visited.mkString}")),
      Option.when(turns.nonEmpty && turns.forall(_.playerError.isDefined))(Finding(
        "player-never-played",
        hard = true,
        s"the player could not choose a command on any of the ${turns.size} turns: ${turns.head.playerError.mkString}"))
    ).flatten
  }

  /** The move `turn` made through an exit that was not open once the turn was over (an exit the Dungeon Master opened
    * during the turn is open by then). A move to somewhere no exit leads is the story's to make.
    */
  private def shutExitUsed(turn: PlayedTurn): Option[String] =
    for {
      from <- turn.before.currentLocation
      to <- turn.after.currentLocation
      if from != to
      origin <- turn.after.locations.find(_.id == from)
      ways = origin.exits.filter(_.targetId == to)
      if ways.nonEmpty && !ways.exists(_.state.passable)
    } yield s"the player went from $from to $to, but the way is ${ways.map(w => s"${w.direction}: ${w.state.wire}").mkString(", ")}"

  private def carriedItemsShown(turn: PlayedTurn): Option[String] = {
    def key(item: String) = item.trim.toLowerCase
    val carried = turn.inventory.map(key).toSet
    val shown = turn.scene.toList.flatMap(_.items).filter(item => carried(key(item)))
    Option.when(shown.nonEmpty)(s"carried and also lying in the scene: ${shown.mkString(", ")}")
  }

  private def repeatOf(turn: PlayedTurn, earlier: Seq[PlayedTurn]): Option[String] = {
    val words = wordsOf(turn.narration)
    Option
      .when(words.sizeIs >= MinWordsToCompare) {
        earlier
          .filter(_.error.isEmpty)
          .map(other => other.number -> similarity(words, wordsOf(other.narration)))
          .filter(_._2 >= RepeatSimilarity)
          .maxByOption(_._2)
          .map { case (number, share) => f"${share * 100}%.0f%% the same words as turn $number" }
      }
      .flatten
  }

  private def wordsOf(text: String): Set[String] =
    text.toLowerCase.split("[^\\p{L}\\p{N}']+").filter(_.nonEmpty).toSet

  /** The share of the words of two texts that both have (Jaccard); 0 when either has too few to compare. */
  private[playtest] def similarity(a: Set[String], b: Set[String]): Double =
    if (a.sizeIs < MinWordsToCompare || b.sizeIs < MinWordsToCompare) 0.0
    else (a & b).size.toDouble / (a | b).size
}
