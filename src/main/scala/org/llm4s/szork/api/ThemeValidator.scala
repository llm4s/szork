package org.llm4s.szork.api

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, SystemMessage, UserMessage}
import org.slf4j.LoggerFactory
import scala.util.Try

/** Asks the LLM whether a custom theme the player described would make a good text adventure, and for a version of it
  * that brings out its adventure. The game never depends on it: when the LLM cannot be asked, or its answer cannot be
  * read, the player goes ahead with the theme as they wrote it.
  */
object ThemeValidator {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** The answer to `/api/game/validate-theme`.
    *
    * @param validated
    *   Whether the LLM judged the theme (false: it could not, and the theme is let through as written)
    * @param valid
    *   Whether the theme may be played
    * @param message
    *   Why it may not, if it may not
    * @param enhancedTheme
    *   The theme to play: the LLM's richer version, or the player's own
    */
  final case class Verdict(validated: Boolean, valid: Boolean, message: String, enhancedTheme: String) {
    def toJson: ujson.Value =
      ujson.Obj("validated" -> validated, "valid" -> valid, "message" -> message, "enhancedTheme" -> enhancedTheme)
  }

  private val SystemPrompt =
    "You are an expert game designer specializing in text adventure games. Evaluate theme ideas and enhance them."

  private def prompt(description: String): String =
    s"""Analyze this adventure game theme idea and determine if it would work well for a text-based adventure game:
      |
      |Theme: $description
      |
      |Please respond with a JSON object:
      |{
      |  "valid": true/false,
      |  "message": "Brief explanation if not valid",
      |  "enhancedTheme": "Enhanced version of the theme if valid, focusing on adventure elements"
      |}
      |
      |A good theme should have:
      |- Clear setting and atmosphere
      |- Potential for exploration and discovery
      |- Opportunities for puzzles, challenges, or mysteries
      |- Interesting locations to visit
      |""".stripMargin

  /** The LLM's verdict on `description`, or the theme let through unjudged if there is none. */
  def validate(description: String)(using llm: LLMClient): Verdict = {
    logger.info(s"Validating custom theme: ${description.take(100)}...")
    val conversation = Conversation(Seq(SystemMessage(SystemPrompt), UserMessage(prompt(description))))
    Try(llm.complete(conversation)).toEither.left.map(_.getMessage).flatMap(_.left.map(_.message)) match {
      case Right(completion) =>
        readVerdict(completion.content, description).getOrElse {
          logger.warn("The theme validator's answer could not be read; the theme is let through")
          unjudged(description)
        }
      case Left(error) =>
        logger.error(s"Failed to validate theme: $error")
        unjudged(description)
    }
  }

  /** The theme let through as the player wrote it. */
  private def unjudged(description: String): Verdict =
    Verdict(validated = false, valid = true, message = "", enhancedTheme = description)

  /** The verdict in the LLM's `answer`: the JSON object in it (the model may wrap it in prose or a code fence), which
    * must say whether the theme is `valid`. An enhanced theme that is empty or longer than a theme may be is not used.
    */
  private[szork] def readVerdict(answer: String, description: String): Option[Verdict] =
    for {
      json <- jsonObjectIn(answer)
      valid <- json.value.get("valid").flatMap(_.boolOpt)
    } yield {
      def text(field: String) = json.value.get(field).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
      Verdict(
        validated = true,
        valid = valid,
        message = text("message").getOrElse(""),
        enhancedTheme = text("enhancedTheme").filter(_.length <= SzorkConfig.MaxThemeChars).getOrElse(description)
      )
    }

  /** The JSON object from the first `{` to the last `}` of `text`, if that is one. */
  private def jsonObjectIn(text: String): Option[ujson.Obj] = {
    val (start, end) = (text.indexOf('{'), text.lastIndexOf('}'))
    Option
      .when(start >= 0 && end > start)(text.substring(start, end + 1))
      .flatMap(json => Try(ujson.read(json)).toOption)
      .collect { case obj: ujson.Obj => obj }
  }
}
