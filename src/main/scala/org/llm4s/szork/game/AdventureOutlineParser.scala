package org.llm4s.szork.game

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import ujson._
import org.slf4j.LoggerFactory

object AdventureOutlineParser {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** Fix incomplete JSON by adding missing closing brackets and braces */
  private def fixIncompleteJson(json: String): String = {
    // Count open vs close braces to determine how many we need
    val openBraces = json.count(_ == '{')
    val closeBraces = json.count(_ == '}')
    val openBrackets = json.count(_ == '[')
    val closeBrackets = json.count(_ == ']')

    val needCloseBrackets = openBrackets - closeBrackets
    val needCloseBraces = openBraces - closeBraces

    if (needCloseBrackets > 0 || needCloseBraces > 0) {
      logger.warn(s"JSON appears incomplete, adding $needCloseBrackets ] and $needCloseBraces }")
      val fixed = json +
        ("]" * needCloseBrackets) +
        ("}" * needCloseBraces)
      return fixed
    }

    json
  }

  /** Extract JSON from LLM response handling various formats (markdown blocks, plain JSON, etc.) */
  private def extractJson(response: String): Option[String] = {
    val trimmed = response.trim

    // Strategy 1: Try to extract from markdown code block (```json ... ```)
    val markdownPattern = "```(?:json)?\\s*\\n?([\\s\\S]*?)```".r
    val extracted = markdownPattern.findFirstMatchIn(trimmed).map(_.group(1).trim) match {
      case Some(json) if json.nonEmpty => json
      case _ =>
        // Strategy 2: Find first { to last } (handles text before/after JSON)
        val start = trimmed.indexOf('{')
        if (start >= 0) {
          val end = trimmed.lastIndexOf('}')
          if (end > start) {
            trimmed.substring(start, end + 1)
          } else {
            // No closing brace found at all - take from first { to end
            trimmed.substring(start)
          }
        } else {
          // Strategy 3: Try the whole response if it looks like JSON
          if (trimmed.startsWith("{")) trimmed else return None
        }
    }

    // Always validate and fix incomplete JSON
    Some(fixIncompleteJson(extracted))
  }

  def parse(response: String): SzorkResult[AdventureOutline] = {
    if (response == null || response.trim.isEmpty) {
      return Left(ParseError("Empty response from LLM"))
    }

    val jsonStr = extractJson(response) match {
      case Some(json) =>
        logger.info(s"Extracted JSON (length: ${json.length} chars)")
        logger.info(s"JSON preview: ${json.take(300)}")
        logger.info(s"JSON ending: ...${json.takeRight(200)}")

        // Debug: Save extracted JSON to temp file for inspection
        try {
          val debugFile = java.nio.file.Paths.get("debug-extracted-json.txt")
          java.nio.file.Files.write(debugFile, json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          logger.info(s"Saved extracted JSON to: ${debugFile.toAbsolutePath}")
        } catch {
          case e: Exception => logger.warn(s"Failed to save debug JSON: ${e.getMessage}")
        }

        json
      case None =>
        val preview = if (response.length > 200) response.take(200) + "..." else response
        return Left(ParseError(s"No valid JSON found in response. Preview: $preview"))
    }

    try {
      logger.info("Attempting to parse JSON with ujson.read()...")
      val json = read(jsonStr)
      logger.info("JSON parsing successful")

      // Validate required fields exist
      if (!json.obj.contains("title")) {
        return Left(ParseError("JSON missing required field: 'title'"))
      }
      if (!json.obj.contains("mainQuest")) {
        return Left(ParseError("JSON missing required field: 'mainQuest'"))
      }

      val outline = AdventureOutline(
        title = json("title").str,
        tagline = json.obj.get("tagline").flatMap {
          case Null => None
          case s => Some(s.str)
        },
        mainQuest = json("mainQuest").str,
        subQuests = json.obj.get("subQuests") match {
          case Some(arr) => arr.arr.map(_.str).toList
          case None => List.empty
        },
        keyLocations = json.obj.get("keyLocations") match {
          case Some(arr) => arr.arr.map { loc =>
            LocationOutline(
              id = loc("id").str,
              name = loc("name").str,
              description = loc("description").str,
              significance = loc("significance").str
            )
          }.toList
          case None => List.empty
        },
        importantItems = json.obj.get("importantItems") match {
          case Some(arr) => arr.arr.map { item =>
            ItemOutline(
              name = item("name").str,
              description = item("description").str,
              purpose = item("purpose").str
            )
          }.toList
          case None => List.empty
        },
        keyCharacters = json.obj.get("keyCharacters") match {
          case Some(arr) => arr.arr.map { ch =>
            CharacterOutline(
              name = ch("name").str,
              role = ch("role").str,
              description = ch("description").str
            )
          }.toList
          case None => List.empty
        },
        adventureArc = json.obj.get("adventureArc").map(_.str).getOrElse(""),
        specialMechanics = json.obj.get("specialMechanics").flatMap {
          case Null => None
          case s => Some(s.str)
        }
      )
      Right(outline)
    } catch {
      case e: ujson.ParseException =>
        val preview = if (jsonStr.length > 300) jsonStr.take(300) + "..." else jsonStr
        Left(ParseError(s"JSON parsing failed: ${e.getMessage}\nJSON preview: $preview", Some(e)))
      case e: NoSuchElementException =>
        Left(ParseError(s"Missing required field in JSON: ${e.getMessage}", Some(e)))
      case e: Throwable =>
        Left(ParseError(s"Failed to parse adventure outline: ${e.getMessage}", Some(e)))
    }
  }
}
