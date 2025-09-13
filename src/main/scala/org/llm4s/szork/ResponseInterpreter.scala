package org.llm4s.szork

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

  def parseAndValidate(response: String): Either[List[String], GameResponseData] =
    GameResponseParser.parseAndValidate(response)

  def parseToOption(response: String): (Option[GameResponseData], Option[List[String]]) = {
    if (response == null || response.isEmpty) return (None, None)
    parseAndValidate(response) match {
      case Right(data) => (Some(data), None)
      case Left(issues) => (None, Some(issues))
    }
  }

  def extractSceneFrom(response: String): Option[GameScene] =
    parseAndValidate(response) match {
      case Right(scene: GameScene) => Some(scene)
      case _ => None
    }

  /** Extract textual content from assistant messages, properly handling optional content. Returns the last non-empty
    * assistant response, or empty string if none found.
    */
  def extractLastAssistantResponse(messages: Seq[Message]): String =
    messages.reverse
      .collectFirst {
        case AssistantMessage(Some(content), _) if content != null && content.nonEmpty => content
      }
      .getOrElse("")

  /** Extract and combine all textual responses from a sequence of messages. Only includes assistant messages that have
    * actual text content.
    */
  def extractAssistantResponses(messages: Seq[Message]): String =
    messages
      .collect {
        case AssistantMessage(Some(content), _) if content != null && content.nonEmpty => content
      }
      .mkString("\n\n")
}
