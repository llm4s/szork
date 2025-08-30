package org.llm4s.szork

import ujson._

case class Exit(
  direction: String,  // "north", "south", "east", "west", "up", "down", etc.
  locationId: String, // Target location ID
  description: Option[String] = None // Optional description of what's in that direction
)

// Base trait for game responses
sealed trait GameResponseData {
  def narrationText: String
  def locationId: String
}

// Full scene response with all details
case class GameScene(
  locationId: String,           // Unique identifier for this location (e.g., "forest_entrance", "dark_cave_1")
  locationName: String,          // Human-readable location name
  narrationText: String,         // The text to display/narrate to the player
  imageDescription: String,      // Detailed description for image generation
  musicDescription: String,      // Detailed description for music generation
  musicMood: String,            // Mood keyword for music (e.g., "exploration", "combat", "mystery")
  exits: List[Exit],            // Available exits from this location
  items: List[String] = Nil,    // Items present in this location
  npcs: List[String] = Nil      // NPCs present in this location
) extends GameResponseData

// Simple response for actions that don't change scene
case class SimpleResponse(
  narrationText: String,         // The response text
  locationId: String,            // Current location (unchanged)
  actionTaken: String            // What action was performed
) extends GameResponseData

object GameResponseData {
  def fromJson(json: String): Either[String, GameResponseData] = {
    try {
      val parsed = read(json)
      
      // Check response type
      val responseType = parsed.obj.get("responseType").map(_.str).getOrElse("fullScene")
      
      responseType match {
        case "simple" =>
          // Parse as simple response
          val response = SimpleResponse(
            narrationText = parsed("narrationText").str,
            locationId = parsed("locationId").str,
            actionTaken = parsed.obj.get("actionTaken").map(_.str).getOrElse("unknown")
          )
          Right(response)
          
        case _ =>
          // Parse as full scene (default behavior)
          val exits = parsed.obj.get("exits").map(_.arr.map { exitJson =>
            Exit(
              direction = exitJson("direction").str,
              locationId = exitJson("locationId").str,
              description = exitJson.obj.get("description").map(_.str)
            )
          }.toList).getOrElse(Nil)
          
          val scene = GameScene(
            locationId = parsed("locationId").str,
            locationName = parsed("locationName").str,
            narrationText = parsed("narrationText").str,
            imageDescription = parsed.obj.get("imageDescription").map(_.str).getOrElse(""),
            musicDescription = parsed.obj.get("musicDescription").map(_.str).getOrElse(""),
            musicMood = parsed.obj.get("musicMood").map(_.str).getOrElse("exploration"),
            exits = exits,
            items = parsed.obj.get("items").map(_.arr.map(_.str).toList).getOrElse(Nil),
            npcs = parsed.obj.get("npcs").map(_.arr.map(_.str).toList).getOrElse(Nil)
          )
          
          Right(scene)
      }
    } catch {
      case e: Exception => 
        Left(s"Failed to parse GameResponseData JSON: ${e.getMessage}")
    }
  }
}

object GameScene {
  // Keep the old method for backward compatibility
  def fromJson(json: String): Either[String, GameScene] = {
    GameResponseData.fromJson(json) match {
      case Right(scene: GameScene) => Right(scene)
      case Right(_: SimpleResponse) => Left("Expected GameScene but got SimpleResponse")
      case Left(error) => Left(error)
    }
  }
  
  def toJson(scene: GameScene): String = {
    Obj(
      "locationId" -> scene.locationId,
      "locationName" -> scene.locationName,
      "narrationText" -> scene.narrationText,
      "imageDescription" -> scene.imageDescription,
      "musicDescription" -> scene.musicDescription,
      "musicMood" -> scene.musicMood,
      "exits" -> scene.exits.map { exit =>
        val exitObj = Obj(
          "direction" -> exit.direction,
          "locationId" -> exit.locationId
        )
        exit.description.foreach(desc => exitObj("description") = desc)
        exitObj
      },
      "items" -> scene.items,
      "npcs" -> scene.npcs
    ).toString
  }
}