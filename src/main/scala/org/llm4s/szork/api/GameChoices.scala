package org.llm4s.szork.api

/** Game theme configuration with prompt text.
  *
  * @param id
  *   Theme identifier (e.g., "fantasy", "sci-fi")
  * @param name
  *   Display name, what a saved game is listed with
  * @param prompt
  *   Description text for AI generation, what the Dungeon Master is told the adventure is about
  */
case class GameTheme(
  id: String,
  name: String,
  prompt: String
)

/** Visual art style for generated images.
  *
  * @param id
  *   Style identifier (e.g., "pixel", "painting")
  * @param name
  *   Display name
  */
case class ArtStyle(
  id: String,
  name: String
)
