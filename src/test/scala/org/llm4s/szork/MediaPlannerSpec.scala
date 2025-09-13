package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MediaPlannerSpec extends AnyFunSuite with Matchers {
  test("detectMoodFromText maps common words to moods") {
    MediaPlanner.detectMoodFromText("An intense battle begins") shouldBe "combat"
    MediaPlanner.detectMoodFromText("You feel the victory") shouldBe "victory"
    MediaPlanner.detectMoodFromText("Dark dungeon and cavern") shouldBe "dungeon"
    MediaPlanner.detectMoodFromText("Enchanted forest path") shouldBe "forest"
    MediaPlanner.detectMoodFromText("Ancient temple altar") shouldBe "temple"
    MediaPlanner.detectMoodFromText("Try to sneak in stealth") shouldBe "stealth"
    MediaPlanner.detectMoodFromText("Treasure chest of gold") shouldBe "treasure"
    MediaPlanner.detectMoodFromText("This area is full of danger and traps") shouldBe "danger"
    // default fallback
    MediaPlanner.detectMoodFromText("You explore the area") shouldBe "exploration"
  }

  test("extractSceneDescription focuses visual details and adjusts POV") {
    val text = "You enter a large hall. You see banners. A passage leads north."
    val desc = MediaPlanner.extractSceneDescription(text)
    desc.toLowerCase should include ("hall")
    desc should not include ("You ")
  }
}

