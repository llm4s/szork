package org.llm4s.szork

object GameResponseValidator {
  private val allowedMoods: Set[String] = Set(
    "entrance","exploration","combat","victory","dungeon","forest","town","mystery",
    "castle","underwater","temple","boss","stealth","treasure","danger","peaceful"
  )
  private val allowedDirections: Set[String] = Set(
    "north","south","east","west","up","down","in","out","left","right","forward","back"
  )
  private val idPattern = "^[a-z0-9_-]+$".r
  private val maxNarrationLenFull = 400
  private val maxNarrationLenSimple = 250
  private val maxImageDescLen = 600
  private val maxMusicDescLen = 400

  def validate(data: GameResponseData): Either[List[String], GameResponseData] = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    data match {
      case s: GameScene =>
        if (s.locationId.trim.isEmpty) errors += "locationId must be non-empty"
        else if (idPattern.findFirstIn(s.locationId).isEmpty) errors += s"locationId '${s.locationId}' must match [a-z0-9_-]+"
        if (s.locationName.trim.isEmpty) errors += "locationName must be non-empty"
        if (s.narrationText.trim.isEmpty) errors += "narrationText must be non-empty"
        else if (s.narrationText.length > maxNarrationLenFull) errors += s"narrationText too long (${s.narrationText.length} > $maxNarrationLenFull)"
        if (s.exits.exists(e => e.direction.trim.isEmpty || e.locationId.trim.isEmpty))
          errors += "all exits must have direction and locationId"
        if (s.exits.nonEmpty && s.exits.exists(e => !allowedDirections.contains(e.direction.toLowerCase)))
          errors += s"invalid exit direction present (allowed: ${allowedDirections.mkString(", ")})"
        if (s.exits.isEmpty)
          errors += "full scene should include at least one exit"
        if (!allowedMoods.contains(s.musicMood.toLowerCase))
          errors += s"musicMood '${s.musicMood}' is not a recognized mood"
        if (s.imageDescription.length > maxImageDescLen)
          errors += s"imageDescription too long (${s.imageDescription.length} > $maxImageDescLen)"
        if (s.musicDescription.length > maxMusicDescLen)
          errors += s"musicDescription too long (${s.musicDescription.length} > $maxMusicDescLen)"

      case s: SimpleResponse =>
        if (s.locationId.trim.isEmpty) errors += "locationId must be non-empty"
        else if (idPattern.findFirstIn(s.locationId).isEmpty) errors += s"locationId '${s.locationId}' must match [a-z0-9_-]+"
        if (s.narrationText.trim.isEmpty) errors += "narrationText must be non-empty"
        else if (s.narrationText.length > maxNarrationLenSimple) errors += s"narrationText too long (${s.narrationText.length} > $maxNarrationLenSimple)"
        if (s.actionTaken.trim.isEmpty) errors += "actionTaken must be non-empty"
    }

    if (errors.isEmpty) Right(data) else Left(errors.toList)
  }
}
