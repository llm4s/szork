package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class StreamingParsersSpec extends AnyFunSuite with Matchers {

  test("StreamingTextParser extracts narration before JSON marker across chunks") {
    val p = new StreamingTextParser()
    p.processChunk("You enter a room.") shouldBe Some("You enter a room.")
    p.processChunk(" More detail") shouldBe Some(" More detail")
    p.processChunk("\n<<<JS") shouldBe None
    p.processChunk("ON>>>\n{\"responseType\":\"simple\"}") shouldBe None
    val json = p.getJson()
    json.isDefined shouldBe true
    p.getNarration().get should include ("You enter a room. More detail")
  }

  test("StreamingJsonParser extracts narrationText from partial JSON") {
    val p = new StreamingJsonParser()
    val chunk1 = "{\"responseType\":\"fullScene\",\"narrationText\":\"Dark hall"
    val chunk2 = " with torches\",\"locationId\":\"hall\"}"
    p.processChunk(chunk1) shouldBe Some("Dark hall")
    p.processChunk(chunk2) shouldBe Some(" with torches")
    p.finalizeExtraction().isDefined shouldBe true
  }
}

