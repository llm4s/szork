package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.api.{AdventureRequest, ArtStyle, GameTheme}

class AdventureRequestSpec extends AnyFunSuite with Matchers {

  test("a request's theme and art style are read, and either may be left out") {
    AdventureRequest.parse(
      """{"theme":{"id":"caves","name":"Caves","prompt":"deep caves"},"artStyle":{"id":"pixel","name":"Pixel"}}"""
    ) shouldBe Right((Some(GameTheme("caves", "Caves", "deep caves")), Some(ArtStyle("pixel", "Pixel"))))
    AdventureRequest.parse("{}") shouldBe Right((None, None))
  }

  test("a malformed request is an error, not an exception") {
    Seq("not json", "[]", """{"theme":"caves"}""", """{"artStyle":{"id":"pixel"}}""").foreach { body =>
      AdventureRequest.parse(body).isLeft shouldBe true
    }
  }
}
