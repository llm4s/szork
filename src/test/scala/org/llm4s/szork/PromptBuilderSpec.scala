package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

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
}
