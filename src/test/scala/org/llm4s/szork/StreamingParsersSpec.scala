package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.streaming.StreamingJsonParser

class StreamingParsersSpec extends AnyFunSuite with Matchers {

  private def stream(chunks: String*): (List[String], String) = {
    val p = new StreamingJsonParser()
    (chunks.toList.flatMap(p.processChunk), p.narration)
  }

  private val Quote = "\\\"" // a JSON-escaped quote: \"
  private val EAcute = "\\u00e9" // a JSON unicode escape for é
  private val Newline = "\\n" // a JSON-escaped newline

  test("narration is emitted as it arrives, and nothing after it") {
    val (emitted, narration) =
      stream("""{"narrationText":"Dark ha""", """ll with torches.","locationId":"hall","items":["torch"]}""")
    emitted shouldBe List("Dark ha", "ll with torches.")
    narration shouldBe "Dark hall with torches."
  }

  test("nothing is emitted before the narration field starts") {
    stream("""{"responseType":"simple",""", """"locationId":"x"""")._1 shouldBe empty
  }

  test("escapes split across chunks are decoded once complete") {
    val json = s"""{"narrationText":"He said ${Quote}hi$Quote ${EAcute}t$EAcute${Newline}OK"}"""
    // Split inside the escaped quote and inside the first unicode escape
    val splitQuote = json.indexOf(Quote) + 1
    val splitUnicode = json.indexOf(EAcute) + 4
    val (emitted, narration) =
      stream(json.substring(0, splitQuote), json.substring(splitQuote, splitUnicode), json.substring(splitUnicode))

    narration shouldBe "He said \"hi\" été\nOK"
    emitted.mkString shouldBe narration
  }
}
