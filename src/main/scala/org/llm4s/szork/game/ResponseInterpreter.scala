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

  /** Interprets an answer in the `narration <<<JSON>>> {...}` format (or bare JSON).
    *
    * Structured data with only warnings is used as-is. If the data has errors or cannot be parsed, the player still
    * sees the narration: the text before the JSON marker, or the JSON's narrationText - never raw JSON.
    */
  def interpret(answer: String): Interpretation =
    GameResponseParser.parse(answer) match {
      case Right(data) =>
        val findings = GameResponseValidator.check(data)
        val issues = Option(findings.errors ++ findings.warnings).filter(_.nonEmpty)
        if (findings.errors.isEmpty) Interpretation(Some(data), data.narrationText, issues)
        else Interpretation(None, narrationOf(answer).getOrElse(data.narrationText), issues)
      case Left(error) =>
        Interpretation(None, narrationOf(answer).getOrElse(FallbackNarration), Some(List(error.message)))
    }

  /** The narration in `answer` when it cannot be used as structured data. */
  private def narrationOf(answer: String): Option[String] = {
    val trimmed = answer.trim
    trimmed.indexOf(GameResponseParser.JsonMarker) match {
      case -1 if trimmed.startsWith("{") => extractNarrationTextFromJson(trimmed)
      case -1 => Some(trimmed).filter(_.nonEmpty)
      case at =>
        Some(trimmed.substring(0, at).trim)
          .filter(_.nonEmpty)
          .orElse(extractNarrationTextFromJson(trimmed.substring(at + GameResponseParser.JsonMarker.length).trim))
    }
  }

  /** Extract textual content from assistant messages, properly handling optional content. Returns the last non-empty
    * assistant response, or empty string if none found.
    */
  def extractLastAssistantResponse(messages: Seq[Message]): String =
    messages.reverse
      .collectFirst {
        case a: AssistantMessage if a.contentOpt.exists(_.nonEmpty) => a.content
      }
      .getOrElse("")

  /** Extract and combine all textual responses from a sequence of messages. Only includes assistant messages that have
    * actual text content.
    */
  def extractAssistantResponses(messages: Seq[Message]): String =
    messages
      .collect {
        case a: AssistantMessage if a.contentOpt.exists(_.nonEmpty) => a.content
      }
      .mkString("\n\n")
}
