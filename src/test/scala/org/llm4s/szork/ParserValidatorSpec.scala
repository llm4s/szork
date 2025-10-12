package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ParserValidatorSpec extends AnyFunSuite with Matchers {

  test("parse and validate full scene with exits") {
    val response =
      """
        You enter a dim cavern lit by glowing fungi.
        <<<JSON>>>
        {
          "responseType": "fullScene",
          "locationId": "cavern_entrance",
          "locationName": "Cavern Entrance",
          "imageDescription": "A rocky cavern with glowing fungi on the walls",
          "musicDescription": "Calm ambient tones with dripping water",
          "musicMood": "exploration",
          "exits": [
            {"direction": "north", "locationId": "narrow_passage"},
            {"direction": "east", "locationId": "side_chamber"}
          ],
          "items": ["lantern"],
          "npcs": []
        }
      """.stripMargin

    val parsed = GameResponseParser.parseAndValidate(response)
    parsed.isRight shouldBe true
    val scene = parsed.toOption.get.asInstanceOf[GameScene]
    scene.locationId shouldBe "cavern_entrance"
    scene.exits.map(_.direction).toSet should contain allOf ("north", "east")
    scene.narrationText.toLowerCase should include("cavern")
  }

  test("reject invalid exit direction") {
    val response =
      """
        A strange hallway extends into darkness.
        <<<JSON>>>
        {
          "responseType": "fullScene",
          "locationId": "strange_hall",
          "locationName": "Strange Hall",
          "imageDescription": "",
          "musicDescription": "",
          "musicMood": "exploration",
          "exits": [
            {"direction": "north-east", "locationId": "unknown"}
          ]
        }
      """.stripMargin

    val parsed = GameResponseParser.parseAndValidate(response)
    parsed.isLeft shouldBe true
    parsed.left.toOption.get.message.toLowerCase should include("invalid exit direction")
  }

  test("parse and validate simple response") {
    val response =
      """
        You examine the lantern closely.
        <<<JSON>>>
        {
          "responseType": "simple",
          "locationId": "cavern_entrance",
          "actionTaken": "examine"
        }
      """.stripMargin

    val parsed = GameResponseParser.parseAndValidate(response)
    parsed.isRight shouldBe true
    parsed.toOption.get.isInstanceOf[SimpleResponse] shouldBe true
  }
}
