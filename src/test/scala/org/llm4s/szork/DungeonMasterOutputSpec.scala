package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.game._
import org.llm4s.szork.media.MusicGeneration
import org.llm4s.szork.stubs.DmAnswers

/** The Dungeon Master's answer: its schema (derived from `DungeonMasterOutput.Answer`), how an answer is read into game
  * data, the game's rules for it ([[GameResponseValidator]]) and how the engine interprets it.
  */
class DungeonMasterOutputSpec extends AnyFunSuite with Matchers {

  private def answer(json: String): DungeonMasterOutput.Answer = DungeonMasterOutput.read(json).toOption.get

  private def edited(json: String)(edit: ujson.Value => Unit): DungeonMasterOutput.Answer = {
    val value = ujson.read(json)
    edit(value)
    answer(ujson.write(value))
  }

  private val Scene = DmAnswers.scene(
    "You stand at a cavern entrance.",
    "cavern_entrance",
    "Cavern Entrance",
    Seq("north" -> "narrow_passage", "east" -> "side_chamber"))

  test("a full scene is read into game data") {
    val scene = GameResponseValidator.read(answer(Scene)).toOption.get.asInstanceOf[GameScene]
    scene.locationId shouldBe "cavern_entrance"
    scene.locationName shouldBe "Cavern Entrance"
    scene.musicMood shouldBe "exploration"
    scene.exits shouldBe List(Exit("north", "narrow_passage"), Exit("east", "side_chamber"))
    GameResponseValidator.problems(answer(Scene)) shouldBe Nil
  }

  test("a simple response is read, ignoring its null scene fields") {
    GameResponseValidator.read(
      answer(DmAnswers.simple("You examine the lantern.", "cavern_entrance", "examine"))) shouldBe
      Right(SimpleResponse("You examine the lantern.", "cavern_entrance", "examine"))
  }

  test("the game's rules are problems llm4s sends back to the model: each kind of answer gives what it needs") {
    // a scene without the fields a scene needs, and an exit that does not lead to a location id
    val bare = edited(Scene) { json =>
      json("locationName") = ujson.Null
      json("imageDescription") = "  "
      json("exits")(1)("locationId") = "Side Chamber"
    }
    GameResponseValidator.problems(bare) shouldBe List(
      "$.locationName: a fullScene answer must give it, not null",
      "$.imageDescription: must not be blank",
      "$.exits[1].locationId: 'Side Chamber' is not a location id: lower_snake_case, [a-z0-9_-]+"
    )
    // a simple response that does not say what was done, with no narration
    val silent = edited(DmAnswers.simple("", "hall", "wait"))(_("actionTaken") = ujson.Null)
    GameResponseValidator.problems(silent) shouldBe List(
      "$.narrationText: must not be blank",
      "$.actionTaken: a simple answer must give it, not null")
  }

  test("narration over the schema's length limits is a warning, not a problem") {
    val long = "x" * (DungeonMasterOutput.MaxSceneNarrationChars + 1)
    val scene = GameResponseValidator.read(answer(DmAnswers.scene(long, "hall", "Hall", Seq("north" -> "x"))))
    GameResponseValidator.warnings(scene.toOption.get).mkString should include(
      s"> ${DungeonMasterOutput.MaxSceneNarrationChars}")

    def simple(chars: Int) =
      GameResponseValidator.read(answer(DmAnswers.simple("x" * chars, "hall", "wait"))).toOption.get
    GameResponseValidator.warnings(simple(DungeonMasterOutput.MaxSimpleNarrationChars)) shouldBe Nil
    GameResponseValidator.warnings(simple(DungeonMasterOutput.MaxSimpleNarrationChars + 1)).mkString should include(
      s"> ${DungeonMasterOutput.MaxSimpleNarrationChars}")
  }

  test("the schema, the reader and music generation share the same directions, moods and limits") {
    // The schema as llm4s sends it (withOutput renders it strict): every property required, the unused ones null
    val schema = DungeonMasterOutput.schema.toJsonSchema(strict = true)
    val properties = schema("properties")
    schema("required").arr.map(_.str).toList shouldBe properties.obj.keys.toList
    properties.obj.keys.head shouldBe "narrationText"
    properties("responseType")("enum").arr.map(_.str).toList shouldBe List("fullScene", "simple")
    // every field has its description (SchemaOf falls back to the field's name for one it cannot read)
    val exitProperties = properties("exits")("items")("properties")
    (properties.obj ++ exitProperties.obj).foreach { case (name, property) =>
      withClue(name)(property("description").str.length should be > name.length)
    }

    // Exit directions and music moods are enums of the shared lists (a mood may be null, in a simple response)
    properties("exits")("items")("properties")("direction")("enum").arr.map(_.str).toSeq shouldBe
      DungeonMasterOutput.Directions
    properties("musicMood")("enum").arr.toSeq shouldBe
      (DungeonMasterOutput.MusicMoods.map(ujson.Str(_)) :+ ujson.Null)
    MusicGeneration.MusicMoods.all.map(_.name) shouldBe DungeonMasterOutput.MusicMoods
    MusicGeneration.MusicMoods.orDefault("tense").name shouldBe DungeonMasterOutput.DefaultMusicMood
    DungeonMasterOutput.read(DmAnswers.scene("A hall.", "hall", "Hall", Nil, mood = "tense")).isLeft shouldBe true

    // Narration limits: the schema states the ones the warnings are given at
    val narration = ujson.write(properties("narrationText"))
    narration should include(s"at most ${DungeonMasterOutput.MaxSceneNarrationChars} characters")
    narration should include(s"${DungeonMasterOutput.MaxSimpleNarrationChars} for a simple response")
  }

  test("a model's reply is read from its first brace, whatever is round the JSON") {
    DungeonMasterOutput.answerIn(s"```json\n$Scene\n```").map(_.locationId) shouldBe Right("cavern_entrance")
    DungeonMasterOutput.answerIn(s"Here is the scene: $Scene Enjoy!").map(_.locationId) shouldBe Right(
      "cavern_entrance")
    DungeonMasterOutput.answerIn(Scene.take(Scene.length / 2)).isLeft shouldBe true
    DungeonMasterOutput.answerIn("The arch looms.").isLeft shouldBe true
  }

  test("an answer that is not game data shows its narration, never raw JSON") {
    val unusable = ujson.read(Scene)
    unusable("locationName") = ujson.Null
    val interpreted = ResponseInterpreter.interpret(ujson.write(unusable))
    interpreted.data shouldBe None
    interpreted.narration shouldBe "You stand at a cavern entrance."
    interpreted.issues.get.mkString should include("locationName")

    ResponseInterpreter.interpret("""{"narrationText": ""}""").narration shouldBe ResponseInterpreter.FallbackNarration
    ResponseInterpreter.interpret("not json").narration shouldBe ResponseInterpreter.FallbackNarration
  }

  test("an answer that asks for a new picture says how the place looks now") {
    val burnt = DmAnswers.simple("It burns.", "hall", "burn", imageUpdate = Some("  A scorched hall. "))
    ResponseInterpreter.interpret(burnt).imageUpdate shouldBe Some("A scorched hall.")
    ResponseInterpreter.interpret(DmAnswers.simple("It burns.", "hall", "burn")).imageUpdate shouldBe None
  }
}
