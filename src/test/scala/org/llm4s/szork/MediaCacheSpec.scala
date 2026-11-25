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

  test("a location's latest picture in an art style is how the place looks now") {
    val game = IdGenerator.gameId()
    MediaCache.latestImage(game, "hall", "pixel") shouldBe None
    MediaCache.cacheImage(game, "hall", "a quiet hall", "pixel", b64("first"))
    MediaCache.cacheImage(game, "hall", "a quiet hall", "painting", b64("painted"))
    MediaCache.latestImage(game, "hall", "pixel") shouldBe Some(b64("first"))
    Thread.sleep(5)
    MediaCache.cacheImage(game, "hall", "the hall, its roof fallen in", "pixel", b64("ruined"))
    MediaCache.latestImage(game, "hall", "pixel") shouldBe Some(b64("ruined"))
    MediaCache.latestImage(game, "hall", "painting") shouldBe Some(b64("painted"))
    MediaCache.latestImage(game, "cellar", "pixel") shouldBe None
  }

  test("concurrent writes for one game keep every entry") {
    val game = IdGenerator.gameId()
    val pool = Executors.newFixedThreadPool(8)
    val start = new CountDownLatch(1)
    (1 to 20).foreach { i =>
      pool.execute { () =>
        start.await()
        MediaCache.cacheImage(game, s"loc$i", s"image $i", "pixel", b64(s"img$i"))
      }
    }
    start.countDown()
    pool.shutdown()
    pool.awaitTermination(30, TimeUnit.SECONDS) shouldBe true

    (1 to 20).foreach { i =>
      withClue(s"loc$i")(MediaCache.getCachedImage(game, s"loc$i", s"image $i", "pixel") shouldBe Some(b64(s"img$i")))
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

    // A picture job still running when the game was deleted
    MediaCache.cacheImage(game, "b", "second", "pixel", b64("two"))
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

  private def png: String = {
    val out = new java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(
      new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB),
      "png",
      out)
    Base64.getEncoder.encodeToString(out.toByteArray)
  }

  test("a picture is stored under the extension of what it is") {
    val game = IdGenerator.gameId()
    val jpeg = org.llm4s.szork.media.PictureFiles.shrink(png, 4)
    MediaCache.cacheImage(game, "a", "jpeg", "pixel", jpeg)
    MediaCache.cacheImage(game, "b", "png", "pixel", png)
    val names = Files.list(root.resolve(game).resolve("images")).iterator().asScala.map(_.getFileName.toString).toList
    names.filter(_.startsWith("a_")).foreach(_ should endWith(".jpg"))
    names.filter(_.startsWith("b_")).foreach(_ should endWith(".png"))
    names should have size 2
    MediaCache.getCachedImage(game, "a", "jpeg", "pixel") shouldBe Some(jpeg)
  }

  test("pruning leaves files being written alone, and removes a game left with no media") {
    val game = IdGenerator.gameId()
    MediaCache.cacheImage(game, "a", "first", "pixel", b64("one"))
    val images = root.resolve(game).resolve("images")
    val inFlight = Files.createFile(images.resolve(".b_1234.png123.tmp"))

    // Nothing is expired: the file being written is not counted or deleted, the picture stays
    MediaCache.prune(ttlMs = Long.MaxValue, maxBytes = Long.MaxValue)
    Files.exists(inFlight) shouldBe true
    MediaCache.getCachedImage(game, "a", "first", "pixel") shouldBe defined

    // Everything expired: the game's directory goes, with its index and the stray temporary file
    MediaCache.prune(now = System.currentTimeMillis() + 10000, ttlMs = 1000, maxBytes = Long.MaxValue)
    Files.exists(root.resolve(game)) shouldBe false
    // and the game can cache again
    MediaCache.cacheImage(game, "a", "again", "pixel", b64("two"))
    MediaCache.getCachedImage(game, "a", "again", "pixel") shouldBe Some(b64("two"))
  }

  test("a prune is not run for every picture stored") {
    val now = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
    MediaCache.pruneIfDue(now = now, intervalMs = 60000) shouldBe true
    MediaCache.pruneIfDue(now = now + 1000, intervalMs = 60000) shouldBe false
    MediaCache.pruneIfDue(now = now + 59999, intervalMs = 60000) shouldBe false
    MediaCache.pruneIfDue(now = now + 60000, intervalMs = 60000) shouldBe true
  }
}
