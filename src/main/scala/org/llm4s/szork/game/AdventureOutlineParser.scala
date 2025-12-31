package org.llm4s.szork.game

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import ujson._
import org.slf4j.LoggerFactory

object AdventureOutlineParser {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** Fix incomplete JSON by intelligently closing nested structures */
  private def fixIncompleteJson(json: String): String = {
    // Track open brackets and braces in order to close them properly
    val stack = scala.collection.mutable.Stack[Char]()
    var inString = false
    var escaping = false

    // Parse to find what's still open
    for (c <- json)
      if (escaping) {
        escaping = false
      } else if (c == '\\') {
        escaping = true
      } else if (c == '"' && !escaping) {
        inString = !inString
      } else if (!inString) {
        c match {
          case '{' | '[' => stack.push(c)
          case '}' if stack.nonEmpty && stack.top == '{' => stack.pop()
          case ']' if stack.nonEmpty && stack.top == '[' => stack.pop()
          case _ => // ignore
        }
      }

    // Close in reverse order
    if (stack.nonEmpty) {
      val closing = stack.map {
        case '{' => '}'
        case '[' => ']'
        case c => c
      }.mkString
      logger.warn(s"JSON appears incomplete, adding: $closing")
      json + closing
    } else {
      json
    }
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

  /** Try to extract just the title and mainQuest from malformed JSON */
  private def extractCriticalFields(jsonStr: String): Option[(String, String)] =
    try {
      // Use regex to extract title and mainQuest even if the rest is broken
      val titlePattern = """"title"\s*:\s*"([^"]+)"""".r
      val mainQuestPattern = """"mainQuest"\s*:\s*"([^"]+)"""".r

      val title = titlePattern.findFirstMatchIn(jsonStr).map(_.group(1))
      val mainQuest = mainQuestPattern.findFirstMatchIn(jsonStr).map(_.group(1))

      (title, mainQuest) match {
        case (Some(t), Some(m)) =>
          logger.info(s"Extracted critical fields: title='$t', mainQuest='${m.take(100)}'")
          Some((t, m))
        case _ => None
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to extract critical fields: ${e.getMessage}")
        None
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
          case Some(arr) =>
            arr.arr.map { loc =>
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
          case Some(arr) =>
            arr.arr.map { item =>
              ItemOutline(
                name = item("name").str,
                description = item("description").str,
                purpose = item("purpose").str
              )
            }.toList
          case None => List.empty
        },
        keyCharacters = json.obj.get("keyCharacters") match {
          case Some(arr) =>
            arr.arr.map { ch =>
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
        logger.error(s"JSON parsing failed: ${e.getMessage}")
        // Try one more time with better JSON repair
        extractCriticalFields(jsonStr) match {
          case Some((title, mainQuest)) =>
            logger.warn(s"Fallback extraction found title='$title' but returning error - generation should be retried")
            Left(
              ParseError(
                s"JSON structure is incomplete. Found title='$title' but full structure is corrupted. ${e.getMessage}",
                Some(e)))
          case None =>
            val preview = if (jsonStr.length > 300) jsonStr.take(300) + "..." else jsonStr
            Left(ParseError(s"JSON parsing failed: ${e.getMessage}\nJSON preview: $preview", Some(e)))
        }
      case e: NoSuchElementException =>
        Left(ParseError(s"Missing required field in JSON: ${e.getMessage}", Some(e)))
      case e: Throwable =>
        Left(ParseError(s"Failed to parse adventure outline: ${e.getMessage}", Some(e)))
    }
  }
}
