package org.llm4s.szork.game

import org.llm4s.toolapi.{ObjectSchema, Schema}

/** The structured answer the Dungeon Master gives every turn, as an llm4s output schema, and the one definition of the
  * values an answer is held to: exit directions, music moods and narration lengths.
  *
  * The agent is built with `withOutput(DungeonMasterOutput.schema)`: llm4s states the schema in the system prompt on
  * every model call, checks each final answer against it, and asks the model to correct an invalid answer once; a
  * second invalid answer fails the turn. A turn is either a `fullScene` (the player is somewhere new, or looked around)
  * or a `simple` response; fields a simple response has no use for are null. `narrationText` comes first so it can be
  * streamed to the player while the rest is written.
  *
  * [[GameResponseValidator]] checks what the schema cannot express, with the same limits, and music generation has
  * settings for each of the moods listed here.
  */
object DungeonMasterOutput {

  /** The directions an exit may take (an enum in the schema). */
  val Directions: Seq[String] =
    Seq("north", "south", "east", "west", "up", "down", "in", "out", "left", "right", "forward", "back")

  /** The moods a scene's background music may have; `MusicGeneration.MusicMoods` has settings for each. */
  val MusicMoods: Seq[String] = Seq(
    "entrance",
    "exploration",
    "combat",
    "victory",
    "dungeon",
    "forest",
    "town",
    "mystery",
    "castle",
    "underwater",
    "temple",
    "boss",
    "stealth",
    "treasure",
    "danger",
    "peaceful"
  )

  /** The mood played for a scene that names none, or one not in [[MusicMoods]]. */
  val DefaultMusicMood: String = "exploration"

  /** Longest narration for a full scene, in characters: a guideline, so a longer one is used, with a warning. */
  val MaxSceneNarrationChars: Int = 750

  /** Longest narration for a simple response, in characters: a guideline, as for a scene. */
  val MaxSimpleNarrationChars: Int = 450

  private def nullableString(description: String) = Schema.nullable(Schema.string(description))

  private val exit: ObjectSchema[ujson.Value] = Schema
    .`object`[ujson.Value]("An exit from the location")
    .withProperty(Schema.property("direction", Schema.string("Direction of the exit").withEnum(Directions)))
    .withProperty(Schema.property("locationId", Schema.string("Id of the location the exit leads to")))
    .withProperty(Schema.property("description", nullableString("What lies that way, or null")))

  val schema: ObjectSchema[ujson.Value] = Schema
    .`object`[ujson.Value]("The Dungeon Master's response to the player")
    .withProperty(Schema.property(
      "narrationText",
      Schema.string(
        "What the player reads and hears: vivid, flowing second-person prose in whole sentences (never a list of " +
          "fragments), mentioning every exit for a scene by its own direction " +
          s"(at most $MaxSceneNarrationChars characters for a fullScene, $MaxSimpleNarrationChars for a simple " +
          "response)")
    ))
    .withProperty(
      Schema.property(
        "responseType",
        Schema
          .string("fullScene for a new location or a fresh look around; simple for anything else")
          .withEnum(Seq("fullScene", "simple"))))
    .withProperty(Schema.property("locationId", Schema.string("The player's location id, lower_snake_case")))
    .withProperty(Schema.property("locationName", nullableString("fullScene: the location's name; simple: null")))
    .withProperty(
      Schema.property("imageDescription", nullableString("fullScene: 2-3 sentence visual description; simple: null")))
    .withProperty(Schema.property(
      "imageUpdate",
      nullableString(
        "Normally null: a place keeps the picture it was first shown with. Only when what the player sees here has " +
          "visibly and lastingly changed since then (a wall brought down, a fire lit, a door broken open, a " +
          "character gone for good): a 2-3 sentence visual description of how the place looks now, for a new " +
          "picture. Either response type may give it")
    ))
    .withProperty(
      Schema.property("musicDescription", nullableString("fullScene: atmosphere for background music; simple: null")))
    .withProperty(Schema
      .property("musicMood", nullableString(s"fullScene: one of ${MusicMoods.mkString(", ")}; simple: null")))
    .withProperty(Schema.property("exits", Schema.nullable(Schema.array("fullScene: every exit; simple: null", exit))))
    .withProperty(Schema.property(
      "items",
      Schema.nullable(
        Schema.array(
          "fullScene: items here; simple: the items here now if the action changed them or their state (taken, " +
            "used up, opened, filled, revealed), otherwise null",
          Schema.string("An item, with its state if it has one")
        )
      )
    ))
    .withProperty(Schema.property(
      "npcs",
      Schema.nullable(
        Schema.array(
          "fullScene: characters here; simple: the characters here now if the action changed who is here (someone " +
            "appeared or left), otherwise null",
          Schema.string("A character")
        )
      )
    ))
    .withProperty(Schema
      .property("actionTaken", nullableString("simple: the action, e.g. examine, take, talk; fullScene: null")))
}
