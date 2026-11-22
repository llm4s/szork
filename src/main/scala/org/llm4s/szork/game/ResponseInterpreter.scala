package org.llm4s.szork.game

import scala.util.Try

object ResponseInterpreter {

  /** A turn's answer, interpreted: the structured data if it is usable, the narration to show the player, and any
    * problems found (errors or warnings) for diagnostics.
    */
  final case class Interpretation(data: Option[GameResponseData], narration: String, issues: Option[List[String]])

  val FallbackNarration: String =
    "I apologize, but there was an error processing the game response. Please try your command again."

  /** Interprets the Dungeon Master's answer to a completed turn.
    *
    * llm4s accepts an answer only once it is JSON matching [[DungeonMasterOutput.schema]] (stripped of any code fence
    * or prose round it), so `answer` is that JSON and always has a string `narrationText`. Structured data with only
    * warnings is used as-is. If it breaks the game's own rules or cannot be read as game data, the player still sees
    * its narration - never raw JSON.
    */
  def interpret(answer: String): Interpretation =
    GameResponseData.fromJson(answer) match {
      case Right(data) =>
        val findings = GameResponseValidator.check(data)
        val issues = Option(findings.errors ++ findings.warnings).filter(_.nonEmpty)
        if (findings.errors.isEmpty) Interpretation(Some(data), data.narrationText, issues)
        else Interpretation(None, data.narrationText, issues)
      case Left(error) =>
        Interpretation(None, narrationOf(answer).getOrElse(FallbackNarration), Some(List(error.message)))
    }

  /** The narration of an answer that could not be read as game data. */
  private def narrationOf(answer: String): Option[String] =
    Try(ujson.read(answer)).toOption
      .flatMap(_.objOpt)
      .flatMap(_.get("narrationText"))
      .flatMap(_.strOpt)
      .filter(_.trim.nonEmpty)
}
