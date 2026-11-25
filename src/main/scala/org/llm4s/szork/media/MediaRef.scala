package org.llm4s.szork.media

/** A generated image or music track on its way to the player. */
enum MediaRef {

  /** The media itself, Base64-encoded: made for this game and sent with the message. */
  case Inline(base64: String)

  /** Media kept in the adventure library, fetched by the browser (and cached there) from `url`. */
  case Stored(url: String)
}
