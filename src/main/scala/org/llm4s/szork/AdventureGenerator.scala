package org.llm4s.szork

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory
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
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  
  def generateAdventureOutline(theme: String, artStyle: String)(implicit client: LLMClient): Either[String, AdventureOutline] = {
    val _ = artStyle // Acknowledge parameter (could be used for style-specific adventure elements)
    logger.info(s"Generating adventure outline for theme: $theme")
    
    val prompt = s"""Create an immersive and engaging text adventure design following classic interactive fiction conventions while incorporating modern accessibility and fun factor.
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
      |      "description": "First-visit description (2-3 sentences establishing atmosphere and key interactive elements)",
      |      "significance": "How this location advances the story and what makes it memorable"
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
      SystemMessage("You are a master interactive fiction designer combining the wit of Infocom classics with modern game design sensibilities. You create text adventures that balance atmospheric prose with clear interactivity, respect player intelligence through fair puzzles, and deliver memorable stories full of personality. Your games are known for clever environmental storytelling, satisfying 'aha!' moments, and descriptions that make players want to examine everything."),
      UserMessage(prompt)
    )
    
    client.complete(Conversation(messages)) match {
      case Right(completion) =>
        parseAdventureOutline(completion.message.content)
      case Left(error) =>
        logger.error(s"Failed to generate adventure outline: $error")
        Left(s"Failed to generate adventure outline: $error")
    }
  }
  
  private def parseAdventureOutline(response: String): Either[String, AdventureOutline] = {
    if (response == null || response.isEmpty) return Left("Received empty response from LLM")
    logger.info(s"Parsing adventure outline from response (length: ${response.length} chars)")
    logger.debug(s"Response preview: ${response.take(200)}")
    val parsed = AdventureOutlineParser.parse(response)
    parsed.foreach(o => logger.info(s"Successfully parsed adventure outline: ${o.title}"))
    parsed
  }
  
  def outlineToSystemPrompt(outline: AdventureOutline): String = {
    val locationsStr = outline.keyLocations.map { loc =>
      s"- ${loc.name} (${loc.id}): ${loc.description} - ${loc.significance}"
    }.mkString("\n")
    
    val itemsStr = outline.importantItems.map { item =>
      s"- ${item.name}: ${item.description} (Purpose: ${item.purpose})"
    }.mkString("\n")
    
    val charactersStr = outline.keyCharacters.map { char =>
      s"- ${char.name} (${char.role}): ${char.description}"
    }.mkString("\n")
    
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
  
  def outlineToJson(outline: AdventureOutline): Value = {
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
}
