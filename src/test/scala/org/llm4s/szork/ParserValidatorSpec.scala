package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.game._
import org.llm4s.szork.media.MusicGeneration
import org.llm4s.szork.stubs.DmAnswers

class ParserValidatorSpec extends AnyFunSuite with Matchers {

  private def read(answer: String): GameResponseData = GameResponseData.fromJson(answer).toOption.get

  test("read a full scene") {
    val response = DmAnswers.scene(
      "You stand at a cavern entrance.",
      "cavern_entrance",
      "Cavern Entrance",
      Seq("north" -> "narrow_passage", "east" -> "side_chamber"))
    val scene = read(response).asInstanceOf[GameScene]
    scene.locationId shouldBe "cavern_entrance"
    scene.exits.map(_.direction).toSet shouldBe Set("north", "east")
    scene.exits.head.description shouldBe None // null description
    scene.narrationText should include("cavern")
    GameResponseValidator.check(scene) shouldBe GameResponseValidator.Findings(Nil, Nil)
  }

  test("read a simple response, ignoring its null scene fields") {
    read(DmAnswers.simple("You examine the lantern.", "cavern_entrance", "examine")) shouldBe
      SimpleResponse("You examine the lantern.", "cavern_entrance", "examine")
  }

  test("the validator reports what the schema cannot express: id format and empty text are errors") {
    val badId = read(DmAnswers.scene("A pit.", "Bad Id!", "Pit", Seq("down" -> "pit")))
    GameResponseValidator.check(badId).errors.mkString should include("must match [a-z0-9_-]+")

    val blank = read(DmAnswers.simple("  ", "hall", "wait"))
    GameResponseValidator.check(blank).errors shouldBe List("narrationText must be non-empty")
  }

  test("narration over the schema's length limits is a warning, not an error") {
    val scene =
      read(DmAnswers.scene("x" * (DungeonMasterOutput.MaxSceneNarrationChars + 1), "hall", "Hall", Seq("north" -> "x")))
    val sceneFindings = GameResponseValidator.check(scene)
    sceneFindings.errors shouldBe Nil
    sceneFindings.warnings.mkString should include(s"> ${DungeonMasterOutput.MaxSceneNarrationChars}")

    val atLimit = read(DmAnswers.simple("x" * DungeonMasterOutput.MaxSimpleNarrationChars, "hall", "wait"))
    GameResponseValidator.check(atLimit) shouldBe GameResponseValidator.Findings(Nil, Nil)
    val overLimit = read(DmAnswers.simple("x" * (DungeonMasterOutput.MaxSimpleNarrationChars + 1), "hall", "wait"))
    GameResponseValidator.check(overLimit).warnings.mkString should include(
      s"> ${DungeonMasterOutput.MaxSimpleNarrationChars}")
  }

  test("an unknown music mood is a warning") {
    val scene = read(DmAnswers.scene("A hall.", "hall", "Hall", Seq("north" -> "x"), mood = "tense"))
    val findings = GameResponseValidator.check(scene)
    findings.errors shouldBe Nil
    findings.warnings.mkString should include("musicMood 'tense'")
  }

  test("the schema, the validator and music generation share the same directions, moods and limits") {
    // The schema as llm4s sends it (withOutput renders it strict)
    val properties = DungeonMasterOutput.schema.toJsonSchema(strict = true)("properties")

    // Exit directions: the schema's enum is the shared list
    properties("exits")("items")("properties")("direction")("enum").arr.map(_.str).toSeq shouldBe
      DungeonMasterOutput.Directions

    // Moods: the schema lists the shared moods, the validator accepts each, and each has music settings
    val moodDescription = ujson.write(properties("musicMood"))
    DungeonMasterOutput.MusicMoods.foreach(mood => moodDescription should include(mood))
    MusicGeneration.MusicMoods.all.map(_.name) shouldBe DungeonMasterOutput.MusicMoods
    DungeonMasterOutput.MusicMoods.foreach { mood =>
      val scene = read(DmAnswers.scene("A hall.", "hall", "Hall", Seq("north" -> "x"), mood = mood))
      GameResponseValidator.check(scene).warnings shouldBe Nil
    }
    MusicGeneration.MusicMoods.orDefault("tense").name shouldBe DungeonMasterOutput.DefaultMusicMood

    // Narration limits: the schema states the ones the validator warns at
    val narration = ujson.write(properties("narrationText"))
    narration should include(s"at most ${DungeonMasterOutput.MaxSceneNarrationChars} characters")
    narration should include(s"${DungeonMasterOutput.MaxSimpleNarrationChars} for a simple response")
  }

  test("an answer that is not game data still shows its narration, never raw JSON") {
    // Valid against the schema (locationName may be null) but not a readable scene
    val json = ujson.read(DmAnswers.scene("The floor tilts.", "hall", "Hall", Nil))
    json("locationName") = ujson.Null
    val answer = ujson.write(json)
    val interpreted = ResponseInterpreter.interpret(answer)
    interpreted.data shouldBe None
    interpreted.narration shouldBe "The floor tilts."
    interpreted.issues shouldBe defined

    ResponseInterpreter.interpret("""{"narrationText": ""}""").narration shouldBe ResponseInterpreter.FallbackNarration
    ResponseInterpreter.interpret("not json").narration shouldBe ResponseInterpreter.FallbackNarration
  }
}
