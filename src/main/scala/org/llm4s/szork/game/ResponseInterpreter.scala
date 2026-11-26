package org.llm4s.szork.game

import DungeonMasterOutput.Answer

/** What the game makes of a Dungeon Master answer, decoded once: its game data, the narration to show, what to report
  * about it, and the picture update it asks for.
  */
object ResponseInterpreter {

  /** A turn's answer, interpreted: the structured data if it is usable, the narration to show the player, and any
    * issues found (guidelines it goes beyond, or why it is unusable) for diagnostics.
    */
  final case class Interpretation(
    data: Option[GameResponseData],
    narration: String,
    issues: Option[List[String]],
    imageUpdate: Option[String] = None)

  val FallbackNarration: String =
    "I apologize, but there was an error processing the game response. Please try your command again."

  /** Interprets an answer, or why there is none.
    *
    * A completed turn's answer has passed llm4s's checks - the schema, and [[GameResponseValidator.problems]] - so it
    * is game data; one that could not be decoded, or that breaks the game's rules (an answer kept from before a rule),
    * is shown by its narration if it has one, never as raw JSON.
    */
  def interpret(answer: Either[String, Answer]): Interpretation =
    answer.flatMap(a => GameResponseValidator.read(a).left.map(_.mkString("; "))) match {
      case Right(data) =>
        Interpretation(
          Some(data),
          data.narrationText,
          Option(GameResponseValidator.warnings(data)).filter(_.nonEmpty),
          answer.toOption.flatMap(_.imageUpdate).map(_.trim).filter(_.nonEmpty)
        )
      case Left(problem) =>
        val narration = answer.toOption.map(_.narrationText).filter(_.trim.nonEmpty)
        Interpretation(None, narration.getOrElse(FallbackNarration), Some(List(problem)))
    }

  /** Interprets an answer as llm4s stores it in the thread: its JSON alone. */
  def interpret(json: String): Interpretation = interpret(DungeonMasterOutput.read(json))
}
