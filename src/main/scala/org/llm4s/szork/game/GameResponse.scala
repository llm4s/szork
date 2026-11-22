package org.llm4s.szork.game

/** What a player's command produced.
  *
  * Scene images and music are not part of it: they are generated afterwards, off the turn, for a turn that moved the
  * player (see `GameEngine.locationChangedLastTurn`).
  *
  * @param text
  *   The narration to show the player
  * @param audioBase64
  *   The narration as Base64-encoded speech, if it was requested and a text-to-speech client is configured
  * @param scene
  *   The player's current scene
  */
final case class GameResponse(text: String, audioBase64: Option[String], scene: Option[GameScene])
