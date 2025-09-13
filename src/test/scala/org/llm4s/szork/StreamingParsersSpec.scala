package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class StreamingParsersSpec extends AnyFunSuite with Matchers {

  test("StreamingTextParser extracts narration before JSON marker across chunks") {
    val p = new StreamingTextParser()
    // Feed in chunks; we don't assert intermediate chunk sizes (implementation-dependent)
    p.processChunk("You enter a room.")
    p.processChunk(" More detail")
    p.processChunk("\n<<<JS")
    p.processChunk("ON>>>\n{\"responseType\":\"simple\"}")
    val json = p.getJson()
    json.isDefined shouldBe true
    val narration = p.getNarration().getOrElse("")
    narration should include("You enter a room. More detail")
  }

  test("StreamingJsonParser extracts narrationText from partial JSON") {
    val p = new StreamingJsonParser()
    val chunk1 = "{\"responseType\":\"fullScene\",\"narrationText\":\"Dark hall"
    val chunk2 = " with torches\",\"locationId\":\"hall\"}"
    p.processChunk(chunk1)
    p.processChunk(chunk2)
    val acc = p.getAccumulatedJson
    acc should include("Dark hall")
    acc should include("with torches")
  }
}
