package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.media.{MediaPlanner, MusicGeneration}

class MediaPlannerSpec extends AnyFunSuite with Matchers {
  test("styledImagePrompt frames the description in the chosen art style") {
    MediaPlanner.styledImagePrompt(Some("pixel"), "a cave mouth", "") should (include("a cave mouth") and include(
      "pixel art"))
    MediaPlanner.styledImagePrompt(None, "a cave mouth", "watercolour") shouldBe "a cave mouth, rendered in watercolour"
  }

  test("scene music moods are looked up by name") {
    import MusicGeneration.MusicMoods
    MusicMoods.byName("combat") shouldBe Some(MusicMoods.COMBAT)
    MusicMoods.byName(" Mystery ") shouldBe Some(MusicMoods.MYSTERY)
    MusicMoods.byName("tense") shouldBe None
    MusicMoods.orDefault("tense") shouldBe MusicMoods.EXPLORATION
  }
}
