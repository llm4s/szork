package org.llm4s.szork.game

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import upickle.default.ReadWriter

import scala.util.Try

/** An exit from a scene: its direction (one of [[DungeonMasterOutput.Directions]]), the location it leads to, and what
  * lies that way, if the Dungeon Master said.
  */
case class Exit(
  direction: String,
  locationId: String,
  description: Option[String] = None
) derives ReadWriter

/** What a Dungeon Master answer tells the game: a [[GameScene]] or a [[SimpleResponse]]. Read from the answer by
  * [[GameResponseValidator.read]].
  */
sealed trait GameResponseData {
  def narrationText: String
  def locationId: String
}

/** A full scene: the player is somewhere new, or looked around. */
case class GameScene(
  locationId: String, // Unique identifier for this location (e.g., "forest_entrance", "dark_cave_1")
  locationName: String, // Human-readable location name
  narrationText: String, // The text to display/narrate to the player
  imageDescription: String, // Detailed description for image generation
  musicDescription: String, // Detailed description for music generation
  musicMood: String, // One of DungeonMasterOutput.MusicMoods
  exits: List[Exit], // Available exits from this location
  items: List[String] = Nil, // Items present in this location
  npcs: List[String] = Nil // NPCs present in this location
) extends GameResponseData derives ReadWriter

/** Any other answer: the player stays where they are. */
case class SimpleResponse(
  narrationText: String, // The response text
  locationId: String, // Current location (unchanged)
  actionTaken: String, // What action was performed
  items: Option[List[String]] = None, // The items here now, when the action changed them
  npcs: Option[List[String]] = None // The characters here now, when the action changed them
) extends GameResponseData

/** A scene as a step save stores it. */
object GameScene {
  def fromJson(json: String): SzorkResult[GameScene] =
    Try(upickle.default.read[GameScene](json)).toEither.left.map(e =>
      ParseError(s"Failed to read a scene: ${e.getMessage}", Some(e)))

  def toJson(scene: GameScene): String = upickle.default.write(scene)
}
