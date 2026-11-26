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

  test("fullSystemPrompt says when to use every tool, and that their state outlasts the conversation") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should include("=== TOOLS - THE GAME'S MEMORY ===")
    prompt should not include "RARELY"
    prompt should include("older turns are dropped from what you see")
    val tools = (GameTools.forInventory(new PlayerInventory()).toOption.get ++
      GameTools.forWorld(new WorldState()).toOption.get).map(_.name)
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

  test("the exit rules are stated once, for every exit state") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    def count(text: String) = prompt.sliding(text.length).count(_ == text)
    count("EXITS - THE GAME ENFORCES THEM:") shouldBe 1
    count("the player moves ONLY through an exit that is") shouldBe 1
    count("rejects a fullScene that takes the player through an exit that is not open") shouldBe 1
    prompt should not include "PLAYER MOVEMENT VALIDATION"
    prompt should not include "EXIT STATE ENFORCEMENT"
    // every state the world state keeps is defined
    org.llm4s.szork.game.ExitState.names.foreach(state => prompt should include(s"\"$state\""))
  }

  test("objects are told apart by prominence, not by repeating them in a short narration") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt should not include "mentioned 3 times"
    prompt should include("Show importance by prominence, not repetition")
  }

  test("the prompt's guidance is wrapped: only its quoted example narrations run long") {
    val prompt = PromptBuilder.fullSystemPrompt(None, None, None)
    prompt.linesIterator.filterNot(_.trim.startsWith("• \"")).filter(_.length > 120).toList shouldBe Nil
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
