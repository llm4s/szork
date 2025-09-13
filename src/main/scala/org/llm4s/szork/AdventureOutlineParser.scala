package org.llm4s.szork

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import ujson._

object AdventureOutlineParser {
  def parse(response: String): SzorkResult[AdventureOutline] = {
    if (response == null || response.trim.isEmpty) return Left(ParseError("Empty response"))

    val trimmed = response.trim
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start < 0 || end <= start) return Left(ParseError("No valid JSON found in response"))

    val jsonStr = trimmed.substring(start, end + 1)
    try {
      val json = read(jsonStr)
      val outline = AdventureOutline(
        title = json("title").str,
        tagline = json.obj.get("tagline").flatMap {
          case Null => None
          case s => Some(s.str)
        },
        mainQuest = json("mainQuest").str,
        subQuests = json("subQuests").arr.map(_.str).toList,
        keyLocations = json("keyLocations").arr.map { loc =>
          LocationOutline(
            id = loc("id").str,
            name = loc("name").str,
            description = loc("description").str,
            significance = loc("significance").str
          )
        }.toList,
        importantItems = json("importantItems").arr.map { item =>
          ItemOutline(
            name = item("name").str,
            description = item("description").str,
            purpose = item("purpose").str
          )
        }.toList,
        keyCharacters = json("keyCharacters").arr.map { ch =>
          CharacterOutline(
            name = ch("name").str,
            role = ch("role").str,
            description = ch("description").str
          )
        }.toList,
        adventureArc = json("adventureArc").str,
        specialMechanics = json.obj.get("specialMechanics").flatMap {
          case Null => None
          case s => Some(s.str)
        }
      )
      Right(outline)
    } catch {
      case e: Throwable => Left(ParseError(s"Failed to parse adventure outline: ${e.getMessage}", Some(e)))
    }
  }
}
