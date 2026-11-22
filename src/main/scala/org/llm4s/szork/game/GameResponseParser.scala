package org.llm4s.szork.game

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

/** Reads the Dungeon Master's answer: a JSON object matching [[DungeonMasterOutput.schema]]. */
object GameResponseParser {

  /** The JSON object in `response`: the whole text, or its outermost braces if the model wrapped it in prose. */
  def extractJson(response: String): Option[String] =
    Option(response).map(_.trim).filter(_.nonEmpty).flatMap { trimmed =>
      val start = trimmed.indexOf('{')
      val end = trimmed.lastIndexOf('}')
      Option.when(start >= 0 && end > start)(trimmed.substring(start, end + 1))
    }

  def parse(response: String): SzorkResult[GameResponseData] =
    extractJson(response) match {
      case Some(json) => GameResponseData.fromJson(json)
      case None => Left(ParseError("No JSON found in response"))
    }

  def parseAndValidate(response: String): SzorkResult[GameResponseData] =
    parse(response).flatMap(data => GameResponseValidator.validate(data))
}
