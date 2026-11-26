package org.llm4s.szork.game

import DungeonMasterOutput.{Answer, MaxSceneNarrationChars, MaxSimpleNarrationChars, ResponseType}

/** The game's own rules for a Dungeon Master answer, beyond what [[DungeonMasterOutput.schema]] expresses, and the one
  * place an [[DungeonMasterOutput.Answer]] becomes game data.
  *
  * llm4s has already checked the answer against the schema (field types, enums, every field present) and read it as an
  * `Answer`. [[problems]] is what the schema cannot say - ids that are ids, text that is not blank, and the fields each
  * kind of answer must give - and the agent runs it as an llm4s output check (`withOutputCheck`), so an answer with a
  * problem goes back to the model in the same correction as a schema error. [[warnings]] are guidelines: an answer over
  * them is used, and they are reported.
  */
object GameResponseValidator {

  /** What a location id looks like. */
  val IdPattern: scala.util.matching.Regex = "[a-z0-9_-]+".r

  private val maxImageDescLen = 600
  private val maxMusicDescLen = 400

  /** The game data `answer` gives, or its problems: what the model is told, worded for it, with JSON paths. */
  def read(answer: Answer): Either[List[String], GameResponseData] = {
    def id(path: String, value: String) =
      Option.unless(IdPattern.matches(value))(s"$path: '$value' is not a location id: lower_snake_case, [a-z0-9_-]+")
    def required[A](field: String, value: Option[A], kind: String) =
      Option.when(value.isEmpty)(s"$$.$field: a $kind answer must give it, not null")
    def text(field: String, value: Option[String]) =
      Option.when(value.exists(_.trim.isEmpty))(s"$$.$field: must not be blank")

    val common = text("narrationText", Some(answer.narrationText)).toList ++ id("$.locationId", answer.locationId)
    answer.responseType match {
      case ResponseType.simple =>
        val problems = common ++ required("actionTaken", answer.actionTaken, "simple") ++
          text("actionTaken", answer.actionTaken)
        val response = answer.actionTaken.map { action =>
          SimpleResponse(answer.narrationText, answer.locationId, action, answer.items, answer.npcs)
        }
        response.filter(_ => problems.isEmpty).toRight(problems)

      case ResponseType.fullScene =>
        val exitIds = answer.exits.toList.flatten.zipWithIndex.flatMap { case (exit, i) =>
          id(s"$$.exits[$i].locationId", exit.locationId)
        }
        val problems = common ++
          required("locationName", answer.locationName, "fullScene") ++ text("locationName", answer.locationName) ++
          required("imageDescription", answer.imageDescription, "fullScene") ++
          text("imageDescription", answer.imageDescription) ++
          required("musicDescription", answer.musicDescription, "fullScene") ++
          required("musicMood", answer.musicMood, "fullScene") ++
          required("exits", answer.exits, "fullScene") ++ exitIds
        val scene = for {
          name <- answer.locationName
          image <- answer.imageDescription
          music <- answer.musicDescription
          mood <- answer.musicMood
          exits <- answer.exits
        } yield GameScene(
          locationId = answer.locationId,
          locationName = name,
          narrationText = answer.narrationText,
          imageDescription = image,
          musicDescription = music,
          musicMood = mood.toString,
          exits = exits.map(e => Exit(e.direction.toString, e.locationId, e.description)),
          // a scene with nothing here may say so with null
          items = answer.items.getOrElse(Nil),
          npcs = answer.npcs.getOrElse(Nil)
        )
        scene.filter(_ => problems.isEmpty).toRight(problems)
    }
  }

  /** The problems of `answer` (none: it is usable), for llm4s's output check. */
  def problems(answer: Answer): Seq[String] = read(answer).left.getOrElse(Nil)

  /** Where `data` goes beyond the guidelines: worth reporting, but it is used. */
  def warnings(data: GameResponseData): List[String] = {
    def narration(text: String, maxLen: Int) =
      Option.when(text.length > maxLen)(s"narrationText too long (${text.length} > $maxLen)")
    data match {
      case s: GameScene =>
        narration(s.narrationText, MaxSceneNarrationChars).toList ++
          Option.when(s.exits.isEmpty)("full scene should include at least one exit") ++
          Option.when(s.imageDescription.length > maxImageDescLen)(
            s"imageDescription too long (${s.imageDescription.length} > $maxImageDescLen)") ++
          Option.when(s.musicDescription.length > maxMusicDescLen)(
            s"musicDescription too long (${s.musicDescription.length} > $maxMusicDescLen)")
      case s: SimpleResponse => narration(s.narrationText, MaxSimpleNarrationChars).toList
    }
  }
}
