package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.game.{
  AdventureOutline,
  CharacterOutline,
  DungeonMasterOutput,
  GameTools,
  ItemOutline,
  LocationOutline,
  PlayerInventory,
  PromptBuilder,
  WorldState,
  WorldStateMiddleware
}

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

  test("fullSystemPrompt says when to use every tool, and that their state outlasts the conversation") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("=== TOOLS - THE GAME'S MEMORY ===")
    prompt should not include "RARELY"
    prompt should include("older turns are dropped from what you see")
    val tools = (GameTools.forInventory(new PlayerInventory()) ++ GameTools.forWorld(new WorldState())).map(_.name)
    tools should contain allOf ("get_location_state", "set_exit_state", "record_npc_met", "update_quest_progress")
    tools.foreach(name => withClue(name)(prompt should include(s"- $name:")))
  }

  test("fullSystemPrompt reads the current game state instead of the read tools, and batches tool calls") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include(s"The ${WorldStateMiddleware.Title} section at the end of this prompt")
    prompt should include("the read tools are only for OTHER locations")
    // the section itself is added by the middleware on each model call, not by the static prompt
    prompt should not include WorldStateMiddleware.Heading
    prompt should include("set_exit_state and update_quest_progress in the same step")
    prompt should not include "list_inventory (is the key held?)"
  }

  test("fullSystemPrompt has rules for tool usage") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("TOOL RULES:")
    prompt should include("Movement commands (north/south/east/west/up/down/in/out)")
    prompt should include("Movement needs no tool: your fullScene answer moves the player")
    prompt should include("call set_exit_state")
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

  test("fullSystemPrompt leaves the answer's JSON format to the output schema llm4s states") {
    val prompt = PromptBuilder.fullSystemPrompt(None, Some("pixel"), None)
    // No hand-written JSON example of the answer
    prompt should not include "\"responseType\""
    prompt should not include "\"narrationText\":"
    // The prose guidance stays: when to use each response type, the art style for images, exits in prose
    prompt should include("RESPONSE FORMAT:")
    prompt should include("fullScene")
    prompt should include("simple")
    prompt should include("imageDescription: describe the scene for an image in pixel art style")
    prompt should include("players read narrationText")
    prompt should include(s"narrationText must be ≤${DungeonMasterOutput.MaxSceneNarrationChars} characters")
  }
}
