package org.llm4s.szork.game

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.slf4j.{Logger, LoggerFactory}
import ujson._

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
)

case class LocationOutline(
  id: String,
  name: String,
  description: String,
  significance: String
)

case class ItemOutline(
  name: String,
  description: String,
  purpose: String
)

case class CharacterOutline(
  name: String,
  role: String,
  description: String
)

object AdventureGenerator {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  def generateAdventureOutline(theme: String, artStyle: String)(implicit
    client: LLMClient): SzorkResult[AdventureOutline] = {
    val _ = artStyle // Acknowledge parameter (could be used for style-specific adventure elements)
    logger.info(s"Generating adventure outline for theme: $theme")

    val prompt =
      s"""Create an immersive and engaging text adventure design following classic interactive fiction conventions while incorporating modern accessibility and fun factor.
        |
        |Theme: $theme
        |
        |Design an adventure that balances evocative atmosphere with clear interactive possibilities. Every location should tell a story through its details, every object should feel purposeful, and every character should have depth beyond their function.
        |
        |Generate a detailed JSON response with EXACTLY this structure (keep it simple for parsing):
        |{
        |  "title": "The Adventure Title",
        |  "tagline": "A memorable one-line hook that captures the spirit",
        |  "mainQuest": "The primary objective (1-2 sentences, clear but intriguing)",
        |  "subQuests": ["Side quest 1", "Side quest 2", "Side quest 3"],
        |  "keyLocations": [
        |    {
        |      "id": "location_id",
        |      "name": "Location Name",
        |      "description": "First-visit description (2-4 concise sentences, aim for 400-600 characters, MAX 750). CRITICAL: Must integrate ALL exits naturally into prose using classic IF patterns: 'To the north, a wooden door stands closed', 'Stone stairs descend into darkness below', 'An archway opens to the west'. Never rely on separate exit lists.",
        |      "exits": [
        |        {
        |          "direction": "north/south/east/west/up/down/in/out",
        |          "targetId": "destination_location_id",
        |          "description": "Natural prose: 'An iron door leads north' or 'Stone stairs climb upward'",
        |          "state": "open/closed/locked/sealed/hidden",
        |          "obstacle": "What blocks passage if not open (e.g., 'locked with brass padlock', 'sealed with wax', 'rusted shut')"
        |        }
        |      ],
        |      "interactables": ["Explicit list of examinable objects: furniture (desk, cabinet, chest), mechanisms (lever, button, wheel), containers (drawer, box, safe), decorations (painting, statue, tapestry)"],
        |      "puzzles": "Specific puzzle elements: locked containers needing keys, hidden compartments revealed by examination, mechanisms requiring tools, combination locks with clues nearby",
        |      "secrets": "Hidden elements discoverable through careful examination (e.g., 'examining the bookshelf reveals a hidden switch', 'the desk drawer is locked but the key is under the lamp')",
        |      "significance": "How this location advances the story, what puzzles it contains, and what makes it memorable"
        |    }
        |  ],
        |  "importantItems": [
        |    {
        |      "name": "Item Name",
        |      "description": "How it appears in room descriptions (e.g., 'A brass lantern sits on the dusty shelf')",
        |      "purpose": "How it's used to solve puzzles and why it matters"
        |    }
        |  ],
        |  "keyCharacters": [
        |    {
        |      "name": "Character Name",
        |      "role": "Their function in the story (mentor, guardian, trickster, etc.)",
        |      "description": "Personality and how they appear when first encountered"
        |    }
        |  ],
        |  "adventureArc": "Act 1: Setup and mystery. Act 2: Escalation and revelation. Act 3: Climax and resolution. Include the emotional journey and major plot twist.",
        |  "specialMechanics": "Unique puzzles, environmental storytelling elements, and special interactions that make this adventure memorable"
        |}
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

    val messages = Seq(
      SystemMessage(
        """You are a master interactive fiction designer combining the wit of Infocom classics with modern game design sensibilities. You create text adventures that balance atmospheric prose with clear interactivity, respect player intelligence through fair puzzles, and deliver memorable stories full of personality. Your games are known for clever environmental storytelling, satisfying 'aha!' moments, and descriptions that make players want to examine everything.
          |
          |CRITICAL: You MUST output valid, complete JSON. Ensure all arrays and objects are properly closed with ] and }. Do not truncate your response - complete the entire JSON structure even if it's long.""".stripMargin),
      UserMessage(prompt)
    )

    // Retry logic for better reliability
    val maxRetries = 2
    var attempt = 0
    var lastError: Option[SzorkError] = None

    while (attempt <= maxRetries) {
      attempt += 1
      logger.info(s"Adventure generation attempt $attempt of ${maxRetries + 1}")

      client.complete(Conversation(messages)) match {
        case Right(completion) =>
          val content = completion.content
          parseAdventureOutline(content) match {
            case Right(outline) =>
              logger.info(s"Successfully generated adventure outline on attempt $attempt: ${outline.title}")
              return Right(outline)
            case Left(error) =>
              logger.warn(s"Attempt $attempt failed to parse outline: ${error.message}")
              lastError = Some(error)
              if (attempt > maxRetries) {
                return Left(error)
              }
              // Wait a bit before retrying
              Thread.sleep(500)
          }
        case Left(error) =>
          logger.error(s"Attempt $attempt failed to call LLM: $error")
          lastError = Some(LLMError(s"Failed to generate adventure outline: $error", retryable = true))
          if (attempt > maxRetries) {
            return Left(lastError.get)
          }
          Thread.sleep(500)
      }
    }

    Left(lastError.getOrElse(LLMError("Unknown error during adventure generation", retryable = false)))
  }

  private def parseAdventureOutline(response: String): SzorkResult[AdventureOutline] = {
    if (response == null || response.isEmpty)
      return Left(LLMError("Received empty response from LLM", retryable = true))
    logger.info(s"Parsing adventure outline from response (length: ${response.length} chars)")
    logger.info(s"Response preview: ${response.take(500)}")
    logger.info(s"Response ending: ...${response.takeRight(200)}")

    // Debug: Save full LLM response
    try {
      val debugFile = java.nio.file.Paths.get("debug-llm-response.txt")
      java.nio.file.Files.write(debugFile, response.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      logger.info(s"Saved full LLM response to: ${debugFile.toAbsolutePath}")
    } catch {
      case e: Exception => logger.warn(s"Failed to save debug response: ${e.getMessage}")
    }

    val parsed = AdventureOutlineParser.parse(response)
    parsed match {
      case Right(o) => logger.info(s"Successfully parsed adventure outline: ${o.title}")
      case Left(err) => logger.error(s"Parse error: ${err.message}")
    }
    parsed
  }

  def outlineToSystemPrompt(outline: AdventureOutline): String = {
    val locationsStr = outline.keyLocations
      .map { loc =>
        s"- ${loc.name} (${loc.id}): ${loc.description} - ${loc.significance}"
      }
      .mkString("\n")

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
      |KEY LOCATIONS (use these consistently):
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
      |""".stripMargin
  }

  def outlineToJson(outline: AdventureOutline): Value =
    Obj(
      "title" -> outline.title,
      "tagline" -> outline.tagline.map(t => Str(t)).getOrElse(Null),
      "mainQuest" -> outline.mainQuest,
      "subQuests" -> outline.subQuests,
      "keyLocations" -> outline.keyLocations.map { loc =>
        Obj(
          "id" -> loc.id,
          "name" -> loc.name,
          "description" -> loc.description,
          "significance" -> loc.significance
        )
      },
      "importantItems" -> outline.importantItems.map { item =>
        Obj(
          "name" -> item.name,
          "description" -> item.description,
          "purpose" -> item.purpose
        )
      },
      "keyCharacters" -> outline.keyCharacters.map { char =>
        Obj(
          "name" -> char.name,
          "role" -> char.role,
          "description" -> char.description
        )
      },
      "adventureArc" -> outline.adventureArc,
      "specialMechanics" -> outline.specialMechanics.map(s => Str(s)).getOrElse(Null)
    )
}
