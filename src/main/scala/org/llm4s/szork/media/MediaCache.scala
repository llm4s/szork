package org.llm4s.szork.media

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.persistence.GameDirs
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.FileTime
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.slf4j.{Logger, LoggerFactory}
import org.llm4s.szork.api.MediaCacheConfig
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters._
import scala.util.Using

/** On-disk cache of the pictures made for one game, so revisiting a location does not pay to draw it again.
  *
  * Layout: `<root>/<gameId>/images/<file>` (JPEG or PNG, by what the picture is) plus a `metadata.json` index per game.
  * Each game's index is updated under that game's lock, and every file is written atomically, so concurrent writes
  * cannot lose each other's entries or expose half-written files. A cache hit refreshes the file's timestamp, and the
  * cache as a whole is pruned oldest-first to its maximum size, dropping files unused for longer than its TTL
  * ([[MediaCacheConfig]]); a game left with no pictures loses its index and directory too. Music is not cached here:
  * the adventure library keeps one track per mood.
  *
  * Clearing a game's cache (when the game is deleted) also stops anything being cached for it afterwards: a picture job
  * still running for the deleted game finds it marked deleted and writes nothing. Game ids are never reused, so the
  * mark is permanent.
  */
object MediaCache {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)
  // The server's settings (SzorkConfig.cache), set at startup; tests point the directory at a temporary one
  @volatile private var settings: MediaCacheConfig = MediaCacheConfig()

  private def cacheRoot: Path = Paths.get(settings.directory)
  // When disabled, nothing is read or written
  private def enabled: Boolean = settings.enabled

  /** Uses `config` from now on. */
  def configure(config: MediaCacheConfig): Unit = settings = config

  /** Caches under `dir` from now on. */
  private[szork] def useCacheDirectory(dir: Path): Unit = settings = settings.copy(directory = dir.toString)

  private val gameLocks = TrieMap[String, Object]()
  // Games whose cache was cleared: nothing is cached for them again. Set and checked under the game's lock.
  private val deletedGames = TrieMap[String, Unit]()
  private object PruneLock
  // When the cache was last pruned, and how long to leave between prunes (a prune scans every game's files)
  private val lastPruneAt = new AtomicLong(0L)
  private val PruneIntervalMs = 60000L

  private def gameDir(gameId: String): Path = GameDirs.resolve(cacheRoot, gameId)

  /** Runs `f` holding `gameId`'s lock, with its index; saves the index `f` returns. Returns false, doing nothing, if
    * the game was deleted.
    */
  private def updateIndex(gameId: String)(f: MediaIndex => MediaIndex): Boolean =
    gameLocks.getOrElseUpdate(gameId, new Object).synchronized {
      if (deletedGames.contains(gameId)) false
      else updateLiveIndex(gameId)(f)
    }

  private def updateLiveIndex(gameId: String)(f: MediaIndex => MediaIndex): Boolean = {
    val dir = gameDir(gameId)
    Files.createDirectories(dir.resolve("images"))
    val metadataPath = dir.resolve("metadata.json")
    val index = MediaCacheCodec.load(metadataPath).getOrElse {
      logger.warn(s"Unreadable media index for game=$gameId; rebuilding it")
      MediaIndex(Map.empty)
    }
    // Drop entries whose files were pruned
    val live = MediaIndex(index.locations.map { case (loc, e) =>
      loc -> LocationEntries(e.images.filter(i => Files.exists(dir.resolve(i.path))))
    })
    MediaCacheCodec.save(metadataPath, f(live))
    true
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

  private def store(gameId: String, locationId: String, key: String, base64Content: String)(
    addEntry: (MediaIndex, String) => MediaIndex): Unit =
    if (enabled) try {
      val bytes = Base64.getDecoder.decode(base64Content)
      val fileName = s"${locationId}_$key.${PictureFiles.extension(bytes)}"
      val stored = updateIndex(gameId) { index =>
        val relative = s"images/$fileName"
        AtomicFiles.write(gameDir(gameId).resolve(relative), bytes)
        addEntry(index, relative)
      }
      if (stored) {
        logger.info(s"Cached images/$fileName for game=$gameId")
        pruneIfDue()
      } else logger.info(s"Not caching images/$fileName: game=$gameId was deleted")
    } catch {
      case e: Exception => logger.error(s"Failed to cache a picture of $locationId for game=$gameId", e)
    }

  private def entriesAt(index: MediaIndex, locationId: String): LocationEntries =
    index.locations.getOrElse(locationId, LocationEntries(Nil))

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

  /** The picture of `locationId` most recently made for this game in `artStyle`: how the place looks now. */
  def latestImage(gameId: String, locationId: String, artStyle: String): Option[String] =
    lookup(gameId)(
      _.locations
        .get(locationId)
        .flatMap(_.images.filter(_.artStyle == artStyle).maxByOption(_.generatedAt))
        .map(_.path))

  def cacheImage(
    gameId: String,
    locationId: String,
    imageDescription: String,
    artStyle: String,
    base64Content: String,
    provider: String = ""): Unit = {
    val key = hashKey(s"$provider|$artStyle|$imageDescription")
    store(gameId, locationId, key, base64Content) { (index, path) =>
      val entries = entriesAt(index, locationId)
      val entry = ImageEntry(key, artStyle, provider, imageDescription, path, System.currentTimeMillis())
      MediaIndex(
        index.locations + (locationId -> entries.copy(images = entries.images.filterNot(_.key == key) :+ entry)))
    }
  }

  // ============= Management =============

  /** Deletes `gameId`'s cached media, and stops anything being cached for it from now on (see the class comment). */
  def clearGameCache(gameId: String): SzorkResult[Unit] =
    try {
      val dir = gameDir(gameId)
      gameLocks.getOrElseUpdate(gameId, new Object).synchronized {
        deletedGames.put(gameId, ())
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

  /** Prunes unless the cache was pruned within the last minute (or `intervalMs`); whether it did. */
  private[szork] def pruneIfDue(
    now: Long = System.currentTimeMillis(),
    intervalMs: Long = PruneIntervalMs,
    ttlMs: Long = settings.ttlMs,
    maxBytes: Long = settings.maxBytes): Boolean = {
    val last = lastPruneAt.get()
    val due = now - last >= intervalMs && lastPruneAt.compareAndSet(last, now)
    if (due) prune(now, ttlMs, maxBytes)
    due
  }

  /** Removes cached media unused for longer than the TTL, then the least recently used until the whole cache fits, then
    * the games left with no media.
    */
  private[szork] def prune(
    now: Long = System.currentTimeMillis(),
    ttlMs: Long = settings.ttlMs,
    maxBytes: Long = settings.maxBytes): Unit =
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
        gameDirs().foreach(removeIfEmpty)
      } catch { case e: Exception => logger.warn(s"Media cache pruning failed: ${e.getMessage}") }
    }

  private def gameDirs(): List[Path] =
    if (!Files.isDirectory(cacheRoot)) Nil
    else Using.resource(Files.list(cacheRoot))(_.iterator().asScala.toList).filter(Files.isDirectory(_))

  // A file being written (see AtomicFiles) is a dot file ending in .tmp: it is neither media nor evidence of it
  private def isMedia(file: Path): Boolean = {
    val name = file.getFileName.toString
    !name.startsWith(".") && !name.endsWith(".tmp") && Files.isRegularFile(file)
  }

  // The directories a game's media may be in (`music` only in a cache an older version made)
  private def mediaDirs(game: Path): List[Path] =
    List("images", "music").map(game.resolve).filter(Files.isDirectory(_))

  // Under the game's lock, so a picture being stored for it is not deleted along with its directory
  private def removeIfEmpty(game: Path): Unit = {
    val id = game.getFileName.toString
    gameLocks.getOrElseUpdate(id, new Object).synchronized {
      val hasMedia =
        mediaDirs(game).exists(dir => Using.resource(Files.list(dir))(_.iterator().asScala.exists(isMedia)))
      if (!hasMedia) {
        deleteRecursively(game)
        logger.info(s"Removed the empty media cache of game=$id")
      }
    }
  }

  /** Every cached media file: path, last used, size. */
  private def mediaFiles(): List[(Path, Long, Long)] =
    gameDirs().flatMap { game =>
      mediaDirs(game).flatMap { dir =>
        Using.resource(Files.list(dir))(_.iterator().asScala.toList).filter(isMedia).map { p =>
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
