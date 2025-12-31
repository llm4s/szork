package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.game.{AdventureOutline, CharacterOutline, ItemOutline, LocationOutline, PromptBuilder}

class PromptBuilderSpec extends AnyFunSuite with Matchers {
  test("fullSystemPrompt includes theme and art style") {
    val prompt = PromptBuilder.fullSystemPrompt(Some("underground realm"), Some("painting"), None)
    prompt should include("underground realm")
    prompt.toLowerCase should include("oil painting")
  }

  test("fullSystemPrompt includes outline content when provided") {
    val outline = AdventureOutline(
      title = "Test Adventure",
      tagline = Some("A quick tag"),
      mainQuest = "Find the artifact",
      subQuests = List("Side 1"),
      keyLocations = List(LocationOutline("loc1", "Hall", "A hall", "Important")),
      importantItems = List(ItemOutline("Lantern", "a lantern", "light")),
      keyCharacters = List(CharacterOutline("Sage", "mentor", "wise")),
      adventureArc = "Act 1 / Act 2 / Act 3",
      specialMechanics = Some("Hidden doors")
    )
    val prompt = PromptBuilder.fullSystemPrompt(None, None, Some(outline))
    prompt should include("Test Adventure")
    prompt should include("KEY LOCATIONS")
  }

  test("fullSystemPrompt clearly establishes LLM as the game engine") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("=== YOUR ROLE AND CAPABILITIES ===")
    prompt should include("YOU ARE THE GAME ENGINE")
    prompt should include("You have FULL capability to:")
    prompt should include("Generate room descriptions and scenes for ANY location")
    prompt should include("Process ALL player commands")
  }

  test("fullSystemPrompt clarifies tools are rare and only for inventory") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("=== TOOL USAGE - RARE, INVENTORY-ONLY ===")
    prompt should include("You have THREE inventory tools but should RARELY use them")
    prompt should include("You do NOT need special functions or tools for most gameplay")
  }

  test("fullSystemPrompt has critical rules for tool usage") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("CRITICAL TOOL RULES:")
    prompt should include("Movement commands (north/south/east/west/up/down/in/out)")
    prompt should include("Movement/examination/interaction = JSON response ONLY, NO TOOLS")
    prompt should include("When in doubt: Generate JSON response, DON'T use tools!")
  }

  test("fullSystemPrompt establishes LLM as full game engine") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("YOU ARE THE GAME ENGINE. You have FULL capability to:")
    prompt should include("Generate room descriptions and scenes for ANY location")
    prompt should include("Process ALL player commands")
  }

  test("fullSystemPrompt has movement validation workflow") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("PLAYER MOVEMENT VALIDATION:")
    prompt should include("Before allowing ANY movement command")
    prompt should include("Check if exit exists in that direction")
    prompt should include("Check exit state (open/closed/locked/sealed/blocked/hidden)")
    prompt should include("If not \"open\", DENY movement and explain obstacle")
    prompt should include("ONLY generate destination scene if exit is \"open\"")
  }

  test("fullSystemPrompt defines all exit states with enforcement rules") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("EXIT STATE ENFORCEMENT:")
    prompt should include("\"locked\" = IMPASSABLE until player possesses correct key/item")
    prompt should include("\"sealed\" = IMPASSABLE until specific tool/action applied")
    prompt should include("\"blocked\" = IMPASSABLE until cleared")
    prompt should include("\"closed\" = Can be opened with simple \"open [door/passage]\" command")
    prompt should include("\"open\" = Freely passable")
    prompt should include("\"hidden\" = Not visible or mentioned until discovered")
  }

  test("fullSystemPrompt emphasizes mandatory enforcement of game mechanics") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("GAME MECHANICS & OBSTACLES - MANDATORY ENFORCEMENT:")
    prompt should include("CRITICAL: You MUST respect the exit states")
    prompt should include("Physical barriers are ABSOLUTE until explicitly overcome")
    prompt should include("NEVER allow movement through locked, sealed, or blocked passages")
  }
}
