package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.game.{GameResponseParser, GameScene, SimpleResponse}
import org.llm4s.szork.stubs.DmAnswers

class ParserValidatorSpec extends AnyFunSuite with Matchers {

  test("parse and validate a full scene") {
    val response = DmAnswers.scene(
      "You stand at a cavern entrance.",
      "cavern_entrance",
      "Cavern Entrance",
      Seq("north" -> "narrow_passage", "east" -> "side_chamber"))
    val scene = GameResponseParser.parseAndValidate(response).toOption.get.asInstanceOf[GameScene]
    scene.locationId shouldBe "cavern_entrance"
    scene.exits.map(_.direction).toSet shouldBe Set("north", "east")
    scene.exits.head.description shouldBe None // null description
    scene.narrationText should include("cavern")
  }

  test("reject an invalid exit direction") {
    val response = DmAnswers.scene("A strange hallway.", "strange_hall", "Strange Hall", Seq("north-east" -> "unknown"))
    val parsed = GameResponseParser.parseAndValidate(response)
    parsed.left.toOption.get.message.toLowerCase should include("invalid exit direction")
  }

  test("parse and validate a simple response, ignoring its null scene fields") {
    val parsed =
      GameResponseParser.parseAndValidate(DmAnswers.simple("You examine the lantern.", "cavern_entrance", "examine"))
    parsed.toOption.get shouldBe SimpleResponse("You examine the lantern.", "cavern_entrance", "examine")
  }

  test("a JSON answer wrapped in prose is still read") {
    val response = "Here is the scene:\n" + DmAnswers.simple("You wait.", "hall", "wait")
    GameResponseParser.parseAndValidate(response).toOption.get.narrationText shouldBe "You wait."
  }
}
