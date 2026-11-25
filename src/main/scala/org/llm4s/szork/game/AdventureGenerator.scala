package org.llm4s.szork.game

import org.llm4s.agent.{Agent, AgentStatus}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{CompletionOptions, ReasoningEffort}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.trace.Tracing
import org.slf4j.{Logger, LoggerFactory}
import upickle.default.ReadWriter
import upickle.implicits.serializeDefaults

/** An adventure's design, as [[AdventureGenerator]] generates it, a save stores it and the Dungeon Master's system
  * prompt states it. Its JSON is the outline schema's ([[AdventureOutlineSchema]]), with every field written.
  */
@serializeDefaults(true)
case class AdventureOutline(
  title: String,
  tagline: Option[String] = None,
  mainQuest: String,
  subQuests: List[String],
  keyLocations: List[LocationOutline],
  importantItems: List[ItemOutline],
  keyCharacters: List[CharacterOutline],
  adventureArc: String,
  specialMechanics: Option[String] = None
) derives ReadWriter

/** A key location. Its exits, interactables, puzzles and secrets are what the designer planned; they are optional so
  * that an outline written without them still reads.
  */
@serializeDefaults(true)
case class LocationOutline(
  id: String,
  name: String,
  description: String,
  significance: String,
  exits: List[ExitOutline] = Nil,
  interactables: List[String] = Nil,
  puzzles: Option[String] = None,
  secrets: Option[String] = None,
  // What a picture of the location shows, written by the [[ArtDirector]] (None: drawn from the description)
  image: Option[String] = None
) derives ReadWriter

/** An exit from a location: its direction (one of [[DungeonMasterOutput.Directions]]), where it leads, and whether it
  * is passable (one of [[AdventureOutlineSchema.ExitStates]]) or what blocks it.
  */
@serializeDefaults(true)
case class ExitOutline(
  direction: String,
  targetId: String,
  description: String,
  state: String,
  obstacle: Option[String] = None
) derives ReadWriter

case class ItemOutline(
  name: String,
  description: String,
  purpose: String
) derives ReadWriter

@serializeDefaults(true)
case class CharacterOutline(
  name: String,
  role: String,
  description: String,
  location: Option[String] = None
) derives ReadWriter

/** Designs adventures with an llm4s agent that answers in the outline schema.
  *
  * The designer agent has no tools: it is given the design brief and answers with an outline, which llm4s checks
  * against [[AdventureOutlineSchema.schema]] (and that it reads as an [[AdventureOutline]]), asking once for a
  * correction of an invalid answer.
  */
object AdventureGenerator {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** The id of the adventure designer agent. */
  val AgentId: String = "szork-adventure-designer"

  /** Model calls for one outline: the answer (llm4s's one correction is part of the same step). */
  val MaxModelCalls: Int = 1

  /** The most tokens the designer may answer with. An outline is several thousand tokens, and a model that thinks
    * spends its thinking from the same allowance; llm4s's Anthropic default (2048) cuts the outline short.
    */
  val MaxOutputTokens: Int = 16000

  private val DesignerPrompt: String =
    """You are a master interactive fiction designer combining the wit of Infocom classics with modern game design sensibilities. You create text adventures that balance atmospheric prose with clear interactivity, respect player intelligence through fair puzzles, and deliver memorable stories full of personality. Your games are known for clever environmental storytelling, satisfying 'aha!' moments, and descriptions that make players want to examine everything.
      |
      |Answer with the complete adventure outline; do not truncate it, however long it is.""".stripMargin

  /** The design brief for an adventure on `theme`: what to make, and the design guidance. */
  def designBrief(theme: String): String =
    s"""Create an immersive and engaging text adventure design following classic interactive fiction conventions while incorporating modern accessibility and fun factor.
      |
      |Theme: $theme
      |
      |Design an adventure that balances evocative atmosphere with clear interactive possibilities. Every location should tell a story through its details, every object should feel purposeful, and every character should have depth beyond their function.
      |
      |KEY DESIGN REQUIREMENTS:
      |
      |ENVIRONMENTAL DESIGN:
      |- Each location has layered descriptions: immediate impression → interactive elements → hidden details
      |- Use progressive disclosure: first glance reveals essentials, examination reveals depth
      |- Include state-aware elements that change based on player actions
      |- Follow noun prominence: important objects explicitly mentioned, not buried in prose
      |
      |OBJECT & INTERACTION DESIGN:
      |- Objects appear naturally: "A brass lantern sits on the dusty shelf" not "LANTERN - take me!"
      |- Use examine/look distinction: looking gives overview, examining reveals mechanisms and hints
      |- Include state indicators: "(closed)", "(providing light)", "(nearly empty)"
      |- Every mentioned noun should reward examination
      |
      |PUZZLE & HINT INTEGRATION:
      |- Follow the rule of three for hints: introduce → establish pattern → reveal significance
      |- Embed clues in environmental details, character dialogue, and item descriptions
      |- Ensure fair play: all puzzles solvable with in-game information, no moon logic
      |- Provide multiple solution paths where logical
      |- Environmental storytelling: dust patterns, wear marks, temperature changes hint at secrets
      |
      |CHARACTER & DIALOGUE DESIGN:
      |- Characters revealed through actions and mannerisms, not just static descriptions
      |- Dialogue reflects personality and provides both entertainment and information
      |- NPCs provide contextual hints without breaking character
      |- Each character has a secret, quirk, or unexpected depth
      |
      |NARRATIVE FLOW:
      |- Opening establishes tone and teaches basic interactions implicitly
      |- Each act escalates stakes while revealing new possibilities
      |- Plot twists are foreshadowed through environmental details
      |- Ending provides satisfaction while rewarding thorough exploration
      |- Side quests interconnect with main story
      |
      |SPECIFIC REQUIREMENTS:
      |- Create exactly 5-7 key locations forming a cohesive journey
      |- Include 4-6 important items that feel essential to the world
      |- Create 3-5 memorable characters with distinct personalities
      |- Design 2-3 clever puzzles with multiple solutions
      |- Include environmental storytelling in every location
      |- Add at least one "wow moment" that surprises players
      |- Hide 2-3 easter eggs or secrets for observant players
      |
      |MAP RULES (the game is played on exactly the locations in keyLocations):
      |- Every exit's targetId is the id of ANOTHER location in keyLocations. Never give an exit to a place that is
      |  not in keyLocations: if the story needs somewhere beyond the map (the city above, the open sea, a storeroom),
      |  either make it one of the key locations or describe it in the prose without an exit
      |- The way back is the opposite direction: if north leads from A to B, B's exit to A is south (east/west,
      |  up/down, in/out likewise). Use the compass directions, up, down, in and out; never "back", "forward", "left"
      |  or "right"
      |- A location's description gives each exit the direction its exit has, and no other: stairs that go down are
      |  "down", not "down on the south side"; two exits are never described with the same direction
      |- An exit never leads back to its own location. A wall, a sealed hatch to nowhere or another corner of the same
      |  room is scenery or an interactable, not an exit
      |- Every location can be reached from the first one by following exits, and the player can get back: an exit
      |  from A to B normally has an exit from B to A in the opposite direction
      |- A location has at most one exit in each direction, and every location id is used once
      |- Every exit's state says what the player can do there, and the game enforces it - the player cannot walk
      |  through an exit that is not open:
      |  • open = passable now; its obstacle is null
      |  • closed = shut, but the player can simply open it and go through (a plain door, a gate on a latch); its
      |    obstacle is null. Give a closed exit an obstacle only if it must stay shut until the obstacle is overcome
      |  • locked or sealed = impassable until its obstacle is overcome; it MUST give the obstacle: what is in the way
      |    and what overcomes it (a key among the items, a tool, a puzzle's solution)
      |  • hidden = not shown and not usable until the player discovers it; its obstacle says how it is found
      |
      |CHARACTERS AND CONSISTENCY:
      |- Give every character the player can meet the id of the location where they are first met (location); a
      |  character known only through what they left behind (letters, records) has location null
      |- A character is present in their location from the player's first visit, unless the location's description
      |  says what brings them out
      |- Settle the timeline before you write: when each person arrived, left or died, and how long ago. Every date,
      |  age, name and count you then mention - in descriptions, puzzles, secrets, items and the arc - must agree
      |  with it and with each other. A code or answer a puzzle needs must be the same wherever it is given
      |
      |INTERACTIVITY REQUIREMENTS (Classic Text Adventure Style):
      |- At least 2-3 locked/blocked passages requiring keys or tools to open
      |- At least 3-4 containers (chests, drawers, cabinets, safes) that can be opened
      |- At least 2-3 simple mechanical puzzles (levers, buttons, wheels, dials)
      |- At least 1-2 "fetch quest" puzzles (bring item X to location Y)
      |- Every location should have 3-5 examinable objects beyond takeable items
      |- At least 1 hidden passage or secret compartment discoverable by examination
      |- Clear progression: starting area → obstacles requiring items → goal achievement
      |
      |STARTING LOCATION DESIGN (Tutorial Elements):
      |- The FIRST location in keyLocations array is the starting room - design it carefully as the player's introduction
      |- MUST have at least one obvious item to pick up (lying on table/floor, sitting on shelf)
      |  • Make it visually prominent in description: "A brass lantern sits on the dusty table"
      |  • Should be useful but not critical (flashlight, key to optional room, map, notebook)
      |- MUST have at least one clearly examinable object that rewards examination
      |  • Object should reveal interesting detail or minor clue when examined
      |  • Example: desk reveals hidden drawer, painting shows map detail, book contains hint
      |- MUST have at least ONE open/unlocked exit for immediate exploration
      |  • Player should never feel trapped in starting location
      |  • At least one direction should be freely accessible (state: "open")
      |  • Other exits can be locked/closed to teach obstacle mechanics
      |- Should introduce core mechanics implicitly through obvious opportunities:
      |  • Taking items: obvious item to pick up
      |  • Examining: interesting object that rewards closer look
      |  • Navigation: clear open passage mentioned in description
      |  • Obstacles: perhaps one locked/closed door to introduce barriers
      |- Keep starting room relatively simple - don't overwhelm with puzzles
      |  • 2-3 interactable objects maximum in starting location
      |  • Save complex puzzles for later locations
      |  • Focus on teaching "examine everything" and "try obvious things"
      |- Establish tone and atmosphere immediately but prioritize clarity
      |  • Players should quickly understand: where they are, what they can do, where they can go
      |
      |DESIGN PHILOSOPHY:
      |- Every detail serves atmosphere OR gameplay, ideally both
      |- Players feel clever for noticing connections, not frustrated by obscurity
      |- The world feels alive and reactive to player choices
      |- Humor, wonder, or drama emerge naturally from situations
      |- 30-45 minutes to complete, but 60-90 minutes to fully explore
      |- Replay reveals new details and alternate solutions
      |
      |Remember: You're creating an experience that respects player intelligence while sparking their imagination. Channel the best of classic IF while embracing modern design sensibilities. Make it clever, make it fair, make it memorable!
      |""".stripMargin

  /** Generates an outline for `theme` with the designer agent on `client`, traced with `tracing` when given, reasoning
    * with effort `reasoning` on a model that reasons (None: the model's default).
    */
  def generateAdventureOutline(
    theme: String,
    tracing: Option[Tracing] = None,
    reasoning: Option[ReasoningEffort] = None)(using client: LLMClient): SzorkResult[AdventureOutline] = {
    logger.info(s"Generating adventure outline for theme: $theme")
    val builder = Agent
      .builder(AgentId, client)
      .withSystemPrompt(DesignerPrompt)
      .withMaxSteps(MaxModelCalls)
      .withCompletionOptions(CompletionOptions().withMaxTokens(MaxOutputTokens).withReasoning(reasoning))
      .withOutput(AdventureOutlineSchema.schema)
    val outline = for {
      agent <- tracing.fold(builder)(builder.withTracing).build()
      result <- agent.run(designBrief(theme))
      _ = logger.info(s"Outline designer finished ${result.status.productPrefix}: ${result.runUsage}")
      _ = agent.forget(result.threadId)
      outline <- result.status match {
        // llm4s does not decode an answer the model's output limit cut short
        case AgentStatus.Truncated(_) => Right(None)
        case _ => result.decodeOutput[AdventureOutline].map(Some(_))
      }
    } yield outline.map(withSoundMap)
    outline match {
      case Right(Some(o)) =>
        logger.info(s"Generated adventure outline: ${o.title} (${o.keyLocations.size} locations)")
        Right(o)
      case Right(None) =>
        logger.error(s"Failed to generate adventure outline: cut off at the output limit of $MaxOutputTokens tokens")
        Left(
          AdventureGenerationError(
            "The adventure outline was cut off at the model's output limit",
            FailureReasons.CutOff))
      case Left(error) =>
        logger.error(s"Failed to generate adventure outline: ${error.message}")
        error match {
          case invalid: org.llm4s.agent.graph.GraphError.OutputInvalid =>
            invalid.errors.foreach(problem => logger.error(s"  the outline's problem: $problem"))
          case _ => ()
        }
        Left(AdventureGenerationError(s"Failed to generate adventure outline: ${error.message}", failureReason(error)))
    }
  }

  /** What to tell the player about a generation that failed with `error`. llm4s reports a failed model call as the
    * graph node that failed, with the provider's error as its `cause`, so the cause's type decides; only an error with
    * no type of its own is read by its text.
    */
  def failureReason(error: org.llm4s.error.LLMError): String = {
    import org.llm4s.agent.graph.GraphError
    import org.llm4s.error._
    error match {
      // llm4s reports a failed model call as the graph node that failed, with the provider's own error as its cause
      case GraphError.NodeFailed(_, _, cause) => failureReason(cause)
      case GraphError.ToolFailed(_, _, cause) => failureReason(cause)
      case _: GraphError.OutputInvalid =>
        "The AI's adventure design came back incomplete or malformed. Please try again."
      case _: AuthenticationError => FailureReasons.Key
      case _: RateLimitError => FailureReasons.RateLimit
      case _: TimeoutError | _: NetworkError => FailureReasons.Unreachable
      case _: InvalidInputError | _: NotFoundError | _: ValidationError => FailureReasons.Rejected
      case other => fromText(other.message)
    }
  }

  /** The reason read from an error's text, for the errors llm4s gives no type of their own. */
  private def fromText(message: String): String = {
    val text = message.toLowerCase
    def says(words: String*) = words.exists(text.contains)
    if (says("401", "403", "authentication", "api key", "api-key", "unauthorized")) FailureReasons.Key
    else if (says("429", "rate limit", "overloaded", "529")) FailureReasons.RateLimit
    else if (says("invalid request", "400", "not_found_error", "404")) FailureReasons.Rejected
    else if (says("timed out", "timeout", "connection", "unreachable")) FailureReasons.Unreachable
    else "The adventure could not be generated. See the server log for details."
  }

  private object FailureReasons {
    val Key = "The AI service rejected the server's API key. Check the key in the server's .env file."
    val RateLimit = "The AI service is busy or rate-limiting requests. Wait a moment and try again."
    val Unreachable = "The AI service could not be reached. Check the connection and try again."
    val CutOff =
      "The AI's adventure design came back incomplete: it ran past the model's output limit. Please try again."
    val Rejected = "The AI service rejected the request; the configured model may not be supported. See the server log."
  }

  /** `outline` with repeated locations and the exits that lead nowhere dropped ([[OutlineMap.repair]]), and what is
    * wrong with its map logged: a designer's outline, or one a client sends, is otherwise only known to match the
    * schema.
    */
  def withSoundMap(outline: AdventureOutline): AdventureOutline = {
    val (repaired, dropped) = OutlineMap.repair(outline)
    dropped.foreach(what => logger.warn(s"Outline '${outline.title}': repaired - $what"))
    OutlineMap.problems(repaired).foreach(problem => logger.warn(s"Outline '${outline.title}': $problem"))
    repaired
  }

  def outlineToSystemPrompt(outline: AdventureOutline): String = {
    val locationsStr = outline.keyLocations.map(locationToPrompt).mkString("\n")

    val itemsStr = outline.importantItems
      .map { item =>
        s"- ${item.name}: ${item.description} (Purpose: ${item.purpose})"
      }
      .mkString("\n")

    val charactersStr = outline.keyCharacters
      .map { char =>
        val where = char.location.fold("never met in person")(id => s"first met at $id")
        s"- ${char.name} (${char.role}; $where): ${char.description}"
      }
      .mkString("\n")

    // the ids the world state gives them (WorldSnapshot.fromOutline), which update_quest_progress takes
    val subQuestsStr = outline.subQuests.zipWithIndex.map((q, i) => s"- side-${i + 1}: $q").mkString("\n")

    s"""
      |ADVENTURE OUTLINE:
      |==================
      |Title: ${outline.title}
      |${outline.tagline.map(t => s"Tagline: $t\n").getOrElse("")}
      |MAIN QUEST: ${outline.mainQuest}
      |
      |STORY ARC: ${outline.adventureArc}
      |
      |KEY LOCATIONS (use these consistently; the first is where the player starts):
      |$locationsStr
      |
      |IMPORTANT ITEMS (these should appear in appropriate locations):
      |$itemsStr
      |
      |KEY CHARACTERS (introduce these NPCs at appropriate moments):
      |$charactersStr
      |
      |SIDE QUESTS (optional objectives for the player):
      |$subQuestsStr
      |${outline.specialMechanics.map(m => s"\nSPECIAL MECHANICS:\n$m").getOrElse("")}
      |
      |IMPORTANT: Follow this outline to create a coherent adventure. Ensure locations connect logically,
      |items are placed where they make sense, and characters appear at appropriate times. The player
      |should be able to discover and complete the main quest through exploration and interaction.
      |Keep the planned exits: one that is not open stays impassable until the player overcomes its obstacle
      |or discovers it. The exit states above are how the adventure starts; the world state (get_location_state)
      |holds how they stand now.
      |""".stripMargin
  }

  /** A location for the system prompt: its summary, then its planned exits, objects, puzzles and secrets. */
  private def locationToPrompt(loc: LocationOutline): String = {
    val exits = loc.exits.map { e =>
      val blocked = e.obstacle.filter(_ => e.state != "open").map(o => s"; $o").getOrElse("")
      s"    - ${e.direction} to ${e.targetId} (${e.state}$blocked): ${e.description}"
    }
    val details = List(
      Option.when(exits.nonEmpty)(("  Exits (as the adventure starts):" :: exits).mkString("\n")),
      Option.when(loc.interactables.nonEmpty)(s"  Interactables: ${loc.interactables.mkString(", ")}"),
      loc.puzzles.map(p => s"  Puzzles: $p"),
      loc.secrets.map(s => s"  Secrets: $s")
    ).flatten
    (s"- ${loc.name} (${loc.id}): ${loc.description} - ${loc.significance}" :: details).mkString("\n")
  }

  /** Reads an outline written by [[outlineToJson]] (also the format the frontend sends back); throws if it is not one.
    */
  def outlineFromJson(json: ujson.Value): AdventureOutline = upickle.default.read[AdventureOutline](json)

  /** The outline's JSON, every field written (an absent optional one as null). */
  def outlineToJson(outline: AdventureOutline): ujson.Value = {
    val json = upickle.default.writeJs(outline)
    // A picture brief is the art director's, not the designer's: a location without one is written as the designer
    // answers, with no such property (the designer's schema does not have it)
    json("keyLocations").arr.foreach(location => if (location("image").isNull) location.obj.remove("image"))
    json
  }
}
