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
    val music = MusicGeneration()
    music.MusicMoods.byName("combat") shouldBe Some(music.MusicMoods.COMBAT)
    music.MusicMoods.byName(" Mystery ") shouldBe Some(music.MusicMoods.MYSTERY)
    music.MusicMoods.byName("tense") shouldBe None
  }
}
