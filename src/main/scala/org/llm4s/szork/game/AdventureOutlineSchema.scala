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

  /** The states an exit may be in (an enum in the schema): every state the world state keeps ([[ExitState]]). */
  val ExitStates: Seq[String] = ExitState.names

  /** The directions an outline's exit may take (an enum in the schema): the compass points, up, down, in and out. The
    * Dungeon Master's answers may also use directions relative to the player ([[DungeonMasterOutput.Directions]]); a
    * planned map does not, since "back" from a place depends on how the player came.
    */
  val Directions: Seq[String] = Seq("north", "south", "east", "west", "up", "down", "in", "out")

  private def nullableString(description: String) = Schema.nullable(Schema.string(description))

  private val exit: ObjectSchema[ExitOutline] = Schema
    .`object`[ExitOutline]("An exit from the location")
    .withProperty(Schema.property("direction", Schema.string("Direction of the exit").withEnum(Directions)))
    .withProperty(
      Schema.property("targetId", Schema.string("Id of another location in keyLocations that the exit leads to")))
    .withProperty(Schema.property(
      "description",
      Schema.string("Natural prose for the exit, e.g. 'An iron door leads north' or 'Stone stairs climb upward'")))
    .withProperty(Schema.property(
      "state",
      Schema
        .string("open: passable now. closed: shut, but the player can simply open it (unless it has an obstacle). " +
          "locked, sealed or blocked: impassable until its obstacle is overcome. hidden: not shown or usable until " +
          "the player discovers it")
        .withEnum(ExitStates)
    ))
    .withProperty(
      Schema.property(
        "obstacle",
        nullableString(
          "open: null. locked, sealed or blocked: required - what must be overcome and how (e.g. 'a brass padlock; " +
            "the brass key opens it', 'rusted shut; needs the crowbar', 'a fallen beam; lever it aside with the " +
            "iron bar'). hidden: how the player finds it (e.g. 'revealed by pulling the red book'). closed: null " +
            "for a door the player can simply open, or what keeps it shut until overcome (e.g. 'swollen in its " +
            "frame; needs oiling')")
      ))

  private val location: ObjectSchema[LocationOutline] = Schema
    .`object`[LocationOutline]("A key location")
    .withProperty(Schema.property("id", Schema.string("Location id, lower_snake_case")))
    .withProperty(Schema.property("name", Schema.string("Location name")))
    .withProperty(Schema.property(
      "description",
      Schema.string(
        "First-visit description: a vivid paragraph of 3-5 flowing sentences (400-600 characters, at most 750) " +
          "with a sensory detail or two, that works every exit into the prose by its own direction, e.g. 'To " +
          "the north, a wooden door stands closed'")
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
    .withProperty(Schema.property(
      "location",
      nullableString(
        "Id of the location in keyLocations where the player first finds it, or null if they are given it, " +
          "make it or carry it from the start")
    ))

  private val character: ObjectSchema[CharacterOutline] = Schema
    .`object`[CharacterOutline]("A key character")
    .withProperty(Schema.property("name", Schema.string("Character name")))
    .withProperty(
      Schema.property("role", Schema.string("Their function in the story: mentor, guardian, trickster, ...")))
    .withProperty(
      Schema.property("description", Schema.string("Personality, and how they appear when first encountered")))
    .withProperty(
      Schema.property(
        "location",
        nullableString(
          "Id of the location in keyLocations where the player first meets them, or null if they are never met in " +
            "person")))

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
