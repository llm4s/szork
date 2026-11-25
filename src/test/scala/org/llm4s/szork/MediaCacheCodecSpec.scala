package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import org.llm4s.szork.media.{ImageEntry, LocationEntries, MediaCacheCodec, MediaIndex}

class MediaCacheCodecSpec extends AnyFunSuite with Matchers {
  test("MediaCacheCodec roundtrip save/load") {
    val tmp = Files.createTempFile("media_index_", ".json")
    val idx = MediaIndex(
      Map(
        "loc1" -> LocationEntries(
          images = List(ImageEntry("k1", "style", "prov", "desc", "images/1.jpg", 1700000000000L))
        )
      ))
    MediaCacheCodec.save(tmp, idx)
    val loaded = MediaCacheCodec.load(tmp).get
    loaded.locations.keySet should contain("loc1")
    val l = loaded.locations("loc1")
    l.images.head.key shouldBe "k1"
    l.images.head.generatedAt shouldBe 1700000000000L
  }

  test("an unreadable index is reported, not silently treated as empty") {
    val tmp = Files.createTempFile("media_index_", ".json")
    Files.writeString(tmp, "{\"locations\": {")
    MediaCacheCodec.load(tmp) shouldBe None
  }
}
