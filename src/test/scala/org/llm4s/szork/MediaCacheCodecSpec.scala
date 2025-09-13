package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

class MediaCacheCodecSpec extends AnyFunSuite with Matchers {
  test("MediaCacheCodec roundtrip save/load") {
    val tmp = Files.createTempFile("media_index_", ".json")
    val idx = MediaIndex(
      Map(
        "loc1" -> LocationEntries(
          images = List(ImageEntry("k1", "style", "prov", "desc", "images/1.png", System.currentTimeMillis())),
          music = List(MusicEntry("m1", "mood", "prov", "desc", "music/1.mp3", System.currentTimeMillis()))
        )
      ))
    MediaCacheCodec.save(tmp, idx)
    val loaded = MediaCacheCodec.load(tmp)
    loaded.locations.keySet should contain("loc1")
    val l = loaded.locations("loc1")
    l.images.head.key shouldBe "k1"
    l.music.head.mood shouldBe "mood"
  }
}
