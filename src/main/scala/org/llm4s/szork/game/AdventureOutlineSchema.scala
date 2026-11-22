package org.llm4s.szork.game

import org.llm4s.toolapi.{ObjectSchema, Schema}

/** The adventure outline as an llm4s output schema: the structure [[AdventureGenerator]]'s designer agent answers with,
  * and that [[AdventureOutline]] reads.
  *
  * The agent is built with `withOutput(AdventureOutlineSchema.schema)`: llm4s states the schema in the system prompt,
  * checks the answer against it (and that it reads as an [[AdventureOutline]]), and asks the model to correct an
  * invalid answer once. The schema is strict, so every property is present; optional ones are null.
  */
object AdventureOutlineSchema {

  /** The states an exit may be in (an enum in the schema). */
  val ExitStates: Seq[String] = Seq("open", "closed", "locked", "sealed", "hidden")

  private def nullableString(description: String) = Schema.nullable(Schema.string(description))

  private val exit: ObjectSchema[ExitOutline] = Schema
    .`object`[ExitOutline]("An exit from the location")
    .withProperty(
      Schema.property("direction", Schema.string("Direction of the exit").withEnum(DungeonMasterOutput.Directions)))
    .withProperty(Schema.property("targetId", Schema.string("Id of the location the exit leads to")))
    .withProperty(Schema.property(
      "description",
      Schema.string("Natural prose for the exit, e.g. 'An iron door leads north' or 'Stone stairs climb upward'")))
    .withProperty(Schema.property("state", Schema.string("Whether the way is passable").withEnum(ExitStates)))
    .withProperty(Schema.property(
      "obstacle",
      nullableString("What blocks passage when not open (e.g. 'locked with a brass padlock', 'rusted shut'), or null")))

  private val location: ObjectSchema[LocationOutline] = Schema
    .`object`[LocationOutline]("A key location")
    .withProperty(Schema.property("id", Schema.string("Location id, lower_snake_case")))
    .withProperty(Schema.property("name", Schema.string("Location name")))
    .withProperty(Schema.property(
      "description",
      Schema.string(
        "First-visit description: 2-4 concise sentences (400-600 characters, at most 750) that work every exit " +
          "naturally into the prose, e.g. 'To the north, a wooden door stands closed'")
    ))
    .withProperty(Schema.property("exits", Schema.array("Every exit from the location", exit)))
    .withProperty(
      Schema.property(
        "interactables",
        Schema.array(
          "Examinable objects beyond takeable items: furniture, mechanisms, containers, decorations",
          Schema.string("An object"))
      ))
    .withProperty(Schema.property(
      "puzzles",
      nullableString(
        "Puzzle elements here: locked containers, hidden compartments, mechanisms needing tools, combination locks " +
          "with clues nearby; or null")
    ))
    .withProperty(Schema.property(
      "secrets",
      nullableString(
        "Hidden elements found by careful examination, e.g. 'examining the bookshelf reveals a hidden switch'; or null")
    ))
    .withProperty(Schema.property(
      "significance",
      Schema.string("How the location advances the story, what puzzles it holds, and what makes it memorable")))

  private val item: ObjectSchema[ItemOutline] = Schema
    .`object`[ItemOutline]("An important item")
    .withProperty(Schema.property("name", Schema.string("Item name")))
    .withProperty(
      Schema.property(
        "description",
        Schema.string("How it appears in room descriptions, e.g. 'A brass lantern sits on the dusty shelf'")))
    .withProperty(Schema.property("purpose", Schema.string("How it is used to solve puzzles and why it matters")))

  private val character: ObjectSchema[CharacterOutline] = Schema
    .`object`[CharacterOutline]("A key character")
    .withProperty(Schema.property("name", Schema.string("Character name")))
    .withProperty(
      Schema.property("role", Schema.string("Their function in the story: mentor, guardian, trickster, ...")))
    .withProperty(
      Schema.property("description", Schema.string("Personality, and how they appear when first encountered")))

  val schema: ObjectSchema[AdventureOutline] = Schema
    .`object`[AdventureOutline]("A text adventure design")
    .withProperty(Schema.property("title", Schema.string("The adventure's title")))
    .withProperty(Schema.property("tagline", nullableString("A memorable one-line hook, or null")))
    .withProperty(
      Schema.property("mainQuest", Schema.string("The primary objective: 1-2 sentences, clear but intriguing")))
    .withProperty(Schema.property("subQuests", Schema.array("Side quests", Schema.string("A side quest"))))
    .withProperty(Schema
      .property("keyLocations", Schema.array("The key locations; the first is where the player starts", location)))
    .withProperty(Schema.property("importantItems", Schema.array("The important items", item)))
    .withProperty(Schema.property("keyCharacters", Schema.array("The key characters", character)))
    .withProperty(Schema.property(
      "adventureArc",
      Schema.string(
        "Act 1: setup and mystery. Act 2: escalation and revelation. Act 3: climax and resolution. Include the " +
          "emotional journey and the major plot twist")
    ))
    .withProperty(Schema.property(
      "specialMechanics",
      nullableString("Unique puzzles, environmental storytelling and special interactions, or null")))
}
