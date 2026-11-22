package org.llm4s.szork.game

import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
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
  secrets: Option[String] = None
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

case class CharacterOutline(
  name: String,
  role: String,
  description: String
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

  /** Generates an outline for `theme` with the designer agent on `client`, traced with `tracing` when given. */
  def generateAdventureOutline(theme: String, tracing: Option[Tracing] = None)(using
    client: LLMClient): SzorkResult[AdventureOutline] = {
    logger.info(s"Generating adventure outline for theme: $theme")
    val builder = Agent
      .builder(AgentId, client)
      .withSystemPrompt(DesignerPrompt)
      .withMaxSteps(MaxModelCalls)
      .withOutput(AdventureOutlineSchema.schema)
    val outline = for {
      agent <- tracing.fold(builder)(builder.withTracing).build()
      result <- agent.run(designBrief(theme))
      _ = agent.forget(result.threadId)
      outline <- result.decodeOutput[AdventureOutline]
    } yield outline
    outline match {
      case Right(o) =>
        logger.info(s"Generated adventure outline: ${o.title} (${o.keyLocations.size} locations)")
        Right(o)
      case Left(error) =>
        logger.error(s"Failed to generate adventure outline: ${error.message}")
        Left(LLMError(s"Failed to generate adventure outline: ${error.message}", retryable = true))
    }
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
        s"- ${char.name} (${char.role}): ${char.description}"
      }
      .mkString("\n")

    val subQuestsStr = outline.subQuests.map(q => s"- $q").mkString("\n")

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
  def outlineToJson(outline: AdventureOutline): ujson.Value = upickle.default.writeJs(outline)
}
