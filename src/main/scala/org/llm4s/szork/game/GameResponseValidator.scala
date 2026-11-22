package org.llm4s.szork.game

import DungeonMasterOutput.{MaxSceneNarrationChars, MaxSimpleNarrationChars}

/** The game's own rules for a Dungeon Master answer, beyond what [[DungeonMasterOutput.schema]] expresses.
  *
  * llm4s has already checked the answer against the schema (field types, required fields, exit directions), so this
  * checks only what the schema cannot: id format, non-empty text, and length and mood guidelines.
  */
object GameResponseValidator {
  private val idPattern = "^[a-z0-9_-]+$".r
  private val maxImageDescLen = 600
  private val maxMusicDescLen = 400

  /** Problems found in a response: `errors` make it unusable; `warnings` are worth reporting but do not stop the
    * response being used (e.g. narration a little over the length guideline).
    */
  final case class Findings(errors: List[String], warnings: List[String])

  def check(data: GameResponseData): Findings = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    val warnings = scala.collection.mutable.ListBuffer[String]()

    def checkId(locationId: String): Unit =
      if (locationId.trim.isEmpty) errors += "locationId must be non-empty"
      else if (idPattern.findFirstIn(locationId).isEmpty) errors += s"locationId '$locationId' must match [a-z0-9_-]+"

    def checkNarration(text: String, maxLen: Int): Unit =
      if (text.trim.isEmpty) errors += "narrationText must be non-empty"
      else if (text.length > maxLen) warnings += s"narrationText too long (${text.length} > $maxLen)"

    data match {
      case s: GameScene =>
        checkId(s.locationId)
        if (s.locationName.trim.isEmpty) errors += "locationName must be non-empty"
        checkNarration(s.narrationText, MaxSceneNarrationChars)
        if (s.exits.exists(_.locationId.trim.isEmpty)) errors += "every exit must have a locationId"
        if (s.exits.isEmpty) warnings += "full scene should include at least one exit"
        if (!DungeonMasterOutput.MusicMoods.contains(s.musicMood.toLowerCase))
          warnings += s"musicMood '${s.musicMood}' is not a recognized mood"
        if (s.imageDescription.length > maxImageDescLen)
          warnings += s"imageDescription too long (${s.imageDescription.length} > $maxImageDescLen)"
        if (s.musicDescription.length > maxMusicDescLen)
          warnings += s"musicDescription too long (${s.musicDescription.length} > $maxMusicDescLen)"

      case s: SimpleResponse =>
        checkId(s.locationId)
        checkNarration(s.narrationText, MaxSimpleNarrationChars)
        if (s.actionTaken.trim.isEmpty) errors += "actionTaken must be non-empty"
    }

    Findings(errors.toList, warnings.toList)
  }
}
