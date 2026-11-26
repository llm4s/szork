package org.llm4s.szork.game

/** What a player's command produced.
  *
  * Scene images and music are not part of it: they are generated afterwards, off the turn, for a turn that moved the
  * player (see `GameEngine.locationChangedLastTurn`).
  *
  * `audioBase64` is the narration as speech, if it was requested and a text-to-speech client is configured. `refused`
  * means an input guardrail refused the command: the text is the Dungeon Master's in-character refusal, the model never
  * saw the command, and the game is unchanged (so there is nothing to save).
  */
final case class GameResponse(text: String, audioBase64: Option[String], scene: Option[GameScene], refused: Boolean)
