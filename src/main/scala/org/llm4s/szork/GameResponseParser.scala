package org.llm4s.szork

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

object GameResponseParser {
  private val JsonMarker = "<<<JSON>>>"

  // Extract JSON string; if a marker is present, inject narrationText from prefix
  def extractJsonWithNarration(response: String): Option[String] = {
    if (response == null || response.trim.isEmpty) return None

    val trimmed = response.trim
    val markerIndex = trimmed.indexOf(JsonMarker)
    if (markerIndex >= 0) {
      val narration = trimmed.substring(0, markerIndex).trim
      val jsonStart = markerIndex + JsonMarker.length
      val jsonStr = trimmed.substring(jsonStart).trim
      try {
        val json = ujson.read(jsonStr)
        json("narrationText") = narration
        Some(json.toString())
      } catch {
        case _: Throwable => Some(jsonStr) // fall back to original JSON segment
      }
    } else {
      // Fallback: take outermost JSON braces
      val start = trimmed.indexOf('{')
      val end = trimmed.lastIndexOf('}')
      if (start >= 0 && end > start) Some(trimmed.substring(start, end + 1)) else None
    }
  }

  def parse(response: String): SzorkResult[GameResponseData] =
    extractJsonWithNarration(response) match {
      case Some(json) => GameResponseData.fromJson(json)
      case None => Left(ParseError("No JSON found in response"))
    }

  def parseAndValidate(response: String): SzorkResult[GameResponseData] =
    parse(response).flatMap(data => GameResponseValidator.validate(data))
}
