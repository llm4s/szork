package org.llm4s.szork.media

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.persistence.GameDirs
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.FileTime
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.slf4j.{Logger, LoggerFactory}
import org.llm4s.config.EnvLoader
import java.security.MessageDigest
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters._
import scala.util.Using

/** On-disk cache of generated images and music, so revisiting a location does not pay to generate it again.
  *
  * Layout: `<root>/<gameId>/{images,music}/<file>` plus a `metadata.json` index per game. Each game's index is updated
  * under that game's lock, and every file is written atomically, so concurrent image and music writes cannot lose each
  * other's entries or expose half-written files. A cache hit refreshes the file's timestamp, and the cache as a whole
  * is pruned oldest-first to SZORK_CACHE_MAX_BYTES, dropping files unused for SZORK_CACHE_TTL_MS.
  */
object MediaCache {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val DefaultTtlMs = 7L * 24 * 60 * 60 * 1000 // 7 days
  private val DefaultMaxBytes = 500L * 1024 * 1024 // 500MB, for the whole cache

  private def cacheTtlMs: Long =
    EnvLoader.get("SZORK_CACHE_TTL_MS").flatMap(_.toLongOption).getOrElse(DefaultTtlMs)
  private def cacheMaxBytes: Long =
    EnvLoader.get("SZORK_CACHE_MAX_BYTES").flatMap(_.toLongOption).getOrElse(DefaultMaxBytes)

  // SZORK_CACHE_DIR, default "szork-cache". Tests point it at a temporary directory.
  @volatile private var cacheRoot: Path = Paths.get(EnvLoader.get("SZORK_CACHE_DIR").getOrElse("szork-cache"))

  // SZORK_CACHE_ENABLED=false turns the cache off: nothing is read or written
  private val enabled: Boolean = !EnvLoader.get("SZORK_CACHE_ENABLED").exists(_.trim.equalsIgnoreCase("false"))

  /** Caches under `dir` from now on. */
  private[szork] def useCacheDirectory(dir: Path): Unit = cacheRoot = dir

  private val gameLocks = TrieMap[String, Object]()
  private object PruneLock

  private def gameDir(gameId: String): Path = GameDirs.resolve(cacheRoot, gameId)

  /** Runs `f` holding `gameId`'s lock, with its index; saves the index `f` returns. */
  private def updateIndex(gameId: String)(f: MediaIndex => MediaIndex): Unit =
    gameLocks.getOrElseUpdate(gameId, new Object).synchronized {
      val dir = gameDir(gameId)
      Files.createDirectories(dir.resolve("images"))
      Files.createDirectories(dir.resolve("music"))
      val metadataPath = dir.resolve("metadata.json")
      val index = MediaCacheCodec.load(metadataPath).getOrElse {
        logger.warn(s"Unreadable media index for game=$gameId; rebuilding it")
        MediaIndex(Map.empty)
      }
      // Drop entries whose files were pruned
      val live = MediaIndex(index.locations.map { case (loc, e) =>
        loc -> LocationEntries(
          e.images.filter(i => Files.exists(dir.resolve(i.path))),
          e.music.filter(m => Files.exists(dir.resolve(m.path))))
      })
      MediaCacheCodec.save(metadataPath, f(live))
    }

  /** The cached file `pathOf` picks from `gameId`'s index, base64-encoded; refreshes its timestamp. */
  private def lookup(gameId: String)(pathOf: MediaIndex => Option[String]): Option[String] =
    if (!enabled) None
    else
      try {
        val dir = gameDir(gameId)
        MediaCacheCodec
          .load(dir.resolve("metadata.json"))
          .flatMap(pathOf)
          .map(dir.resolve)
          .filter(Files.exists(_))
          .map { file =>
            val bytes = Files.readAllBytes(file)
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()))
            Base64.getEncoder.encodeToString(bytes)
          }
      } catch {
        case e: Exception =>
          logger.warn(s"Error reading media cache for game=$gameId: ${e.getMessage}")
          None
      }

  private def store(gameId: String, subDir: String, fileName: String, base64Content: String)(
    addEntry: (MediaIndex, String) => MediaIndex): Unit =
    if (enabled) try {
      updateIndex(gameId) { index =>
        val relative = s"$subDir/$fileName"
        AtomicFiles.write(gameDir(gameId).resolve(relative), Base64.getDecoder.decode(base64Content))
        addEntry(index, relative)
      }
      logger.info(s"Cached $subDir/$fileName for game=$gameId")
      prune()
    } catch {
      case e: Exception => logger.error(s"Failed to cache $subDir/$fileName for game=$gameId", e)
    }

  private def entriesAt(index: MediaIndex, locationId: String): LocationEntries =
    index.locations.getOrElse(locationId, LocationEntries(Nil, Nil))

  // ============= Images =============

  def getCachedImage(
    gameId: String,
    locationId: String,
    imageDescription: String,
    artStyle: String,
    provider: String = ""): Option[String] = {
    val key = hashKey(s"$provider|$artStyle|$imageDescription")
    lookup(gameId)(_.locations.get(locationId).flatMap(_.images.find(_.key == key)).map(_.path))
  }

  def cacheImage(
    gameId: String,
    locationId: String,
    imageDescription: String,
    artStyle: String,
    base64Content: String,
    provider: String = ""): Unit = {
    val key = hashKey(s"$provider|$artStyle|$imageDescription")
    store(gameId, "images", s"${locationId}_$key.png", base64Content) { (index, path) =>
      val entries = entriesAt(index, locationId)
      val entry = ImageEntry(key, artStyle, provider, imageDescription, path, System.currentTimeMillis())
      MediaIndex(
        index.locations + (locationId -> entries.copy(images = entries.images.filterNot(_.key == key) :+ entry)))
    }
  }

  // ============= Music =============

  def getCachedMusic(gameId: String, locationId: String, musicDescription: String, mood: String): Option[String] = {
    val key = hashKey(s"replicate|$mood|$musicDescription")
    lookup(gameId)(_.locations.get(locationId).flatMap(_.music.find(_.key == key)).map(_.path))
  }

  def cacheMusic(
    gameId: String,
    locationId: String,
    musicDescription: String,
    mood: String,
    base64Content: String): Unit = {
    val key = hashKey(s"replicate|$mood|$musicDescription")
    store(gameId, "music", s"${locationId}_${mood}_$key.mp3", base64Content) { (index, path) =>
      val entries = entriesAt(index, locationId)
      val entry = MusicEntry(key, mood, "replicate", musicDescription, path, System.currentTimeMillis())
      MediaIndex(index.locations + (locationId -> entries.copy(music = entries.music.filterNot(_.key == key) :+ entry)))
    }
  }

  // ============= Management =============

  def clearGameCache(gameId: String): SzorkResult[Unit] =
    try {
      val dir = gameDir(gameId)
      gameLocks.getOrElseUpdate(gameId, new Object).synchronized {
        if (Files.exists(dir)) {
          deleteRecursively(dir)
          logger.info(s"Cleared cache for game: $gameId")
          Right(())
        } else Left(NotFoundError(s"No cache found for game: $gameId"))
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to clear cache for game: $gameId", e)
        Left(CacheError(s"Failed to clear cache: ${e.getMessage}", Some(e)))
    } finally gameLocks.remove(gameId)

  /** Removes cached media unused for longer than the TTL, then the least recently used until the whole cache fits. */
  private[szork] def prune(
    now: Long = System.currentTimeMillis(),
    ttlMs: Long = cacheTtlMs,
    maxBytes: Long = cacheMaxBytes): Unit =
    PruneLock.synchronized {
      try {
        val files = mediaFiles()
        val (expired, kept) = files.partition { case (_, used, _) => now - used > ttlMs }
        expired.foreach { case (p, _, _) => Files.deleteIfExists(p) }

        var total = kept.map(_._3).sum
        val oldestFirst = kept.sortBy(_._2).iterator
        while (total > maxBytes && oldestFirst.hasNext) {
          val (p, _, size) = oldestFirst.next()
          Files.deleteIfExists(p)
          total -= size
        }
      } catch { case e: Exception => logger.warn(s"Media cache pruning failed: ${e.getMessage}") }
    }

  /** Every cached media file: path, last used, size. */
  private def mediaFiles(): List[(Path, Long, Long)] =
    if (!Files.isDirectory(cacheRoot)) Nil
    else
      Using.resource(Files.list(cacheRoot))(_.iterator().asScala.toList).filter(Files.isDirectory(_)).flatMap { game =>
        List("images", "music").map(game.resolve).filter(Files.isDirectory(_)).flatMap { dir =>
          Using.resource(Files.list(dir))(_.iterator().asScala.toList).filter(Files.isRegularFile(_)).map { p =>
            (p, Files.getLastModifiedTime(p).toMillis, Files.size(p))
          }
        }
      }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Using.resource(Files.list(path))(_.forEach(deleteRecursively))
    Files.deleteIfExists(path)
  }

  private def hashKey(s: String): String = {
    val md = MessageDigest.getInstance("SHA-1")
    val bytes = md.digest(s.getBytes(StandardCharsets.UTF_8))
    bytes.map(b => f"$b%02x").mkString.take(12)
  }
}
