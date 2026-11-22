package org.llm4s.szork.game

import org.llm4s.szork.error.ValidationError
import org.llm4s.llmconnect.model._

object ResponseInterpreter {
  // Helper to extract just narrationText from JSON when full parsing fails
  def extractNarrationTextFromJson(response: String): Option[String] =
    try
      if (response.trim.startsWith("{") && response.contains("narrationText")) {
        val json = ujson.read(response)
        json.obj.get("narrationText").map(_.str)
      } else None
    catch {
      case _: Exception =>
        val pattern = """"narrationText"\s*:\s*"([^"]+(?:\\.[^"]+)*)"""".r
        pattern.findFirstMatchIn(response).map(_.group(1).replace("\\\"", "\"").replace("\\n", "\n"))
    }

  /** A turn's answer, interpreted: the structured data if it is usable, the narration to show the player, and any
    * problems found (errors or warnings) for diagnostics.
    */
  final case class Interpretation(data: Option[GameResponseData], narration: String, issues: Option[List[String]])

  val FallbackNarration: String =
    "I apologize, but there was an error processing the game response. Please try your command again."

  /** Interprets the Dungeon Master's JSON answer.
    *
    * Structured data with only warnings is used as-is. If the data has errors or cannot be parsed, the player still
    * sees the narration - the JSON's narrationText, or the text itself if it is not JSON - never raw JSON.
    */
  def interpret(answer: String): Interpretation =
    GameResponseParser.parse(answer) match {
      case Right(data) =>
        val findings = GameResponseValidator.check(data)
        val issues = Option(findings.errors ++ findings.warnings).filter(_.nonEmpty)
        if (findings.errors.isEmpty) Interpretation(Some(data), data.narrationText, issues)
        else Interpretation(None, data.narrationText, issues)
      case Left(error) =>
        Interpretation(None, narrationOf(answer).getOrElse(FallbackNarration), Some(List(error.message)))
    }

  /** The narration in an answer that could not be parsed. */
  private def narrationOf(answer: String): Option[String] = {
    val trimmed = answer.trim
    if (trimmed.contains("{")) GameResponseParser.extractJson(trimmed).flatMap(extractNarrationTextFromJson)
    else Some(trimmed).filter(_.nonEmpty)
  }
}
