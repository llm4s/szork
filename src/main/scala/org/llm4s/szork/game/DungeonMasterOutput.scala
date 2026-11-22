package org.llm4s.szork.game

import org.llm4s.toolapi.{ObjectSchema, Schema}

/** The structured answer the Dungeon Master gives every turn, as an llm4s output schema.
  *
  * The agent is built with `withOutput(DungeonMasterOutput.schema)`: llm4s states the schema in the system prompt,
  * checks each final answer against it, and asks the model to correct an invalid answer once. A turn is either a
  * `fullScene` (the player is somewhere new, or looked around) or a `simple` response; fields a simple response has no
  * use for are null. `narrationText` comes first so it can be streamed to the player while the rest is written.
  */
object DungeonMasterOutput {
  val Directions: Seq[String] =
    Seq("north", "south", "east", "west", "up", "down", "in", "out", "left", "right", "forward", "back")

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
        "What the player reads and hears: terse classic text-adventure prose, mentioning every exit for a scene " +
          "(at most 750 characters for a fullScene, 450 for a simple response)")
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
    .withProperty(
      Schema.property("musicDescription", nullableString("fullScene: atmosphere for background music; simple: null")))
    .withProperty(Schema.property(
      "musicMood",
      nullableString(
        "fullScene: one of entrance, exploration, combat, victory, dungeon, forest, town, mystery, castle, " +
          "underwater, temple, boss, stealth, treasure, danger, peaceful; simple: null")
    ))
    .withProperty(Schema.property("exits", Schema.nullable(Schema.array("fullScene: every exit; simple: null", exit))))
    .withProperty(Schema.property(
      "items",
      Schema.nullable(Schema.array("fullScene: items here; simple: null", Schema.string("An item")))))
    .withProperty(Schema.property(
      "npcs",
      Schema.nullable(Schema.array("fullScene: characters here; simple: null", Schema.string("A character")))))
    .withProperty(Schema
      .property("actionTaken", nullableString("simple: the action, e.g. examine, take, talk; fullScene: null")))
}
