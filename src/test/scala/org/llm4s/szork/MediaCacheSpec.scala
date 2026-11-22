package org.llm4s.szork

import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._
import org.llm4s.szork.game.IdGenerator
import org.llm4s.szork.media.MediaCache

/** Regression tests for issue #26: concurrent writes, index integrity and pruning. */
class MediaCacheSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {
  private val root: Path = Files.createTempDirectory("szork-cache-test")

  override def beforeAll(): Unit = MediaCache.useCacheDirectory(root)
  override def afterAll(): Unit =
    Files.walk(root).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)

  private def b64(s: String) = Base64.getEncoder.encodeToString(s.getBytes)

  test("concurrent image and music writes for one game keep every entry") {
    val game = IdGenerator.gameId()
    val pool = Executors.newFixedThreadPool(8)
    val start = new CountDownLatch(1)
    (1 to 20).foreach { i =>
      pool.execute { () =>
        start.await()
        if (i % 2 == 0) MediaCache.cacheImage(game, s"loc$i", s"image $i", "pixel", b64(s"img$i"))
        else MediaCache.cacheMusic(game, s"loc$i", s"music $i", "exploration", b64(s"mus$i"))
      }
    }
    start.countDown()
    pool.shutdown()
    pool.awaitTermination(30, TimeUnit.SECONDS) shouldBe true

    (1 to 20).foreach { i =>
      val cached =
        if (i % 2 == 0) MediaCache.getCachedImage(game, s"loc$i", s"image $i", "pixel")
        else MediaCache.getCachedMusic(game, s"loc$i", s"music $i", "exploration")
      withClue(s"loc$i")(cached shouldBe Some(b64(if (i % 2 == 0) s"img$i" else s"mus$i")))
    }
  }

  test("looking up a game that has no cache creates nothing") {
    val game = IdGenerator.gameId()
    MediaCache.getCachedImage(game, "loc", "desc", "pixel") shouldBe None
    Files.exists(root.resolve(game)) shouldBe false
  }

  test("media finished after a game's cache is cleared is not written") {
    val game = IdGenerator.gameId()
    MediaCache.cacheImage(game, "a", "first", "pixel", b64("one"))
    MediaCache.clearGameCache(game) shouldBe Right(())

    // An image or music job still running when the game was deleted
    MediaCache.cacheImage(game, "b", "second", "pixel", b64("two"))
    MediaCache.cacheMusic(game, "b", "tune", "exploration", b64("three"))
    Files.exists(root.resolve(game)) shouldBe false
  }

  test("a corrupt index is rebuilt by the next write") {
    val game = IdGenerator.gameId()
    MediaCache.cacheImage(game, "a", "first", "pixel", b64("one"))
    Files.writeString(root.resolve(game).resolve("metadata.json"), "{ not json")
    MediaCache.getCachedImage(game, "a", "first", "pixel") shouldBe None

    MediaCache.cacheImage(game, "b", "second", "pixel", b64("two"))
    MediaCache.getCachedImage(game, "b", "second", "pixel") shouldBe Some(b64("two"))
  }

  test("pruning drops expired files and then the least recently used, across games") {
    val (g1, g2) = (IdGenerator.gameId(), IdGenerator.gameId())
    MediaCache.cacheImage(g1, "old", "old", "pixel", b64("x" * 100))
    Thread.sleep(20)
    MediaCache.cacheImage(g2, "mid", "mid", "pixel", b64("y" * 100))
    Thread.sleep(20)
    MediaCache.cacheImage(g1, "new", "new", "pixel", b64("z" * 100))
    // Using "old" makes it the most recently used
    Thread.sleep(20)
    MediaCache.getCachedImage(g1, "old", "old", "pixel") shouldBe defined

    MediaCache.prune(maxBytes = 250, ttlMs = Long.MaxValue)
    MediaCache.getCachedImage(g2, "mid", "mid", "pixel") shouldBe None // least recently used
    MediaCache.getCachedImage(g1, "old", "old", "pixel") shouldBe defined

    MediaCache.prune(now = System.currentTimeMillis() + 10000, ttlMs = 1000, maxBytes = Long.MaxValue)
    MediaCache.getCachedImage(g1, "old", "old", "pixel") shouldBe None
    MediaCache.getCachedImage(g1, "new", "new", "pixel") shouldBe None
  }
}
