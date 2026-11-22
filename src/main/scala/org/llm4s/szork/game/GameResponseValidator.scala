package org.llm4s.szork.game

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

object GameResponseValidator {
  private val allowedMoods: Set[String] = Set(
    "entrance",
    "exploration",
    "combat",
    "victory",
    "dungeon",
    "forest",
    "town",
    "mystery",
    "castle",
    "underwater",
    "temple",
    "boss",
    "stealth",
    "treasure",
    "danger",
    "peaceful"
  )
  private val allowedDirections: Set[String] = Set(
    "north",
    "south",
    "east",
    "west",
    "up",
    "down",
    "in",
    "out",
    "left",
    "right",
    "forward",
    "back"
  )
  private val idPattern = "^[a-z0-9_-]+$".r
  private val maxNarrationLenFull = 800 // Increased from 600 to allow for detailed opening scenes with multiple exits
  private val maxNarrationLenSimple = 500 // Increased from 400 for more detailed responses
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
        checkNarration(s.narrationText, maxNarrationLenFull)
        if (s.exits.exists(e => e.direction.trim.isEmpty || e.locationId.trim.isEmpty))
          errors += "all exits must have direction and locationId"
        if (s.exits.exists(e => !allowedDirections.contains(e.direction.toLowerCase)))
          errors += s"invalid exit direction present (allowed: ${allowedDirections.mkString(", ")})"
        if (s.exits.isEmpty) warnings += "full scene should include at least one exit"
        if (!allowedMoods.contains(s.musicMood.toLowerCase))
          warnings += s"musicMood '${s.musicMood}' is not a recognized mood"
        if (s.imageDescription.length > maxImageDescLen)
          warnings += s"imageDescription too long (${s.imageDescription.length} > $maxImageDescLen)"
        if (s.musicDescription.length > maxMusicDescLen)
          warnings += s"musicDescription too long (${s.musicDescription.length} > $maxMusicDescLen)"

      case s: SimpleResponse =>
        checkId(s.locationId)
        checkNarration(s.narrationText, maxNarrationLenSimple)
        if (s.actionTaken.trim.isEmpty) errors += "actionTaken must be non-empty"
    }

    Findings(errors.toList, warnings.toList)
  }

  /** The response if it has no errors (warnings are ignored here; see [[check]]). */
  def validate(data: GameResponseData): SzorkResult[GameResponseData] = {
    val findings = check(data)
    if (findings.errors.isEmpty) Right(data) else Left(ValidationError(findings.errors))
  }
}
