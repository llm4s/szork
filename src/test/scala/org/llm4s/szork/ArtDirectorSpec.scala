package org.llm4s.szork

import org.llm4s.szork.game._
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The art director as an agent: how it is run, and what it makes of its answer. (What its briefs are used for is in
  * AdventureLibrarySpec.)
  */
class ArtDirectorSpec extends AnyFunSuite with Matchers {

  private val outline = AdventureOutline(
    title = "The Quiet Mill",
    mainQuest = "Start the wheel",
    subQuests = Nil,
    keyLocations = List(
      LocationOutline("yard", "Mill Yard", "A muddy yard.", "The start"),
      LocationOutline("loft", "Grain Loft", "A dusty loft.", "The goal")),
    importantItems = Nil,
    keyCharacters = Nil,
    adventureArc = "In and up"
  )

  private def briefs(pairs: (String, String)*): String =
    ujson.Obj("locations" -> ujson.Arr(pairs.map((id, image) => ujson.Obj("id" -> id, "image" -> image))*)).render()

  test("like the designer, the art director has one step: an invalid answer is corrected once, then it fails") {
    val llm = ScriptedLLMClient.always(Reply.text("""{"locations": "none"}"""))
    val result = ArtDirector.direct(outline)(using llm)
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("could not brief the pictures")
    llm.callCount shouldBe 2 // the answer, and llm4s's one correction
    llm.lastCall.get.options.maxTokens shouldBe Some(ArtDirector.MaxOutputTokens)
  }

  test("a brief for a location the outline does not have is left out, and the others are kept") {
    val llm = ScriptedLLMClient.always(
      Reply.text(briefs("yard" -> "Exterior of a muddy mill yard.", "barn" -> "Interior of a barn.", "loft" -> " ")))
    val directed = ArtDirector.direct(outline)(using llm).toOption.get
    directed.keyLocations.map(l => l.id -> l.image) shouldBe List(
      "yard" -> Some("Exterior of a muddy mill yard."),
      "loft" -> None)
    llm.callCount shouldBe 1
  }
}
