package org.llm4s.szork.stubs

/** Dungeon Master answers in the structured output format (every schema property present, narration first). */
object DmAnswers {
  private def withNulls(fields: (String, ujson.Value)*): String = {
    val all = Seq(
      "narrationText",
      "responseType",
      "locationId",
      "locationName",
      "imageDescription",
      "musicDescription",
      "musicMood",
      "exits",
      "items",
      "npcs",
      "actionTaken")
    val supplied = fields.toMap
    ujson.write(ujson.Obj.from(all.map(k => k -> supplied.getOrElse(k, ujson.Null))))
  }

  def scene(
    narration: String,
    locationId: String,
    locationName: String,
    exits: Seq[(String, String)],
    mood: String = "exploration"): String =
    withNulls(
      "narrationText" -> narration,
      "responseType" -> "fullScene",
      "locationId" -> locationId,
      "locationName" -> locationName,
      "imageDescription" -> s"A view of $locationName",
      "musicDescription" -> "Ambient",
      "musicMood" -> mood,
      "exits" -> ujson.Arr.from(exits.map { case (dir, to) =>
        ujson.Obj("direction" -> dir, "locationId" -> to, "description" -> ujson.Null)
      }),
      "items" -> ujson.Arr(),
      "npcs" -> ujson.Arr()
    )

  def simple(narration: String, locationId: String, action: String): String =
    withNulls(
      "narrationText" -> narration,
      "responseType" -> "simple",
      "locationId" -> locationId,
      "actionTaken" -> action)
}
