package org.llm4s.szork.api

import scala.util.Try

/** The body of a generate-adventure request: an optional theme and art style. */
object AdventureRequest {

  /** The theme and art style in `body`, or a message saying why it is not a valid request. */
  def parse(body: String): Either[String, (Option[GameTheme], Option[ArtStyle])] =
    Try {
      val json = ujson.read(body)
      val theme = json.obj.get("theme").filterNot(_.isNull).map { t =>
        GameTheme(id = t("id").str, name = t("name").str, prompt = t("prompt").str)
      }
      val artStyle = json.obj.get("artStyle").filterNot(_.isNull).map { s =>
        ArtStyle(id = s("id").str, name = s("name").str)
      }
      (theme, artStyle)
    }.toEither.left.map(_ =>
      "Request must be a JSON object with optional \"theme\" {id, name, prompt} and \"artStyle\" {id, name}")
}
