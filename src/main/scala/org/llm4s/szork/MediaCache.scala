package org.llm4s.szork

import ujson._
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.slf4j.LoggerFactory
import org.llm4s.config.EnvLoader
import java.security.MessageDigest
import scala.jdk.CollectionConverters._
import scala.util.Using

case class CacheMetadata(
  locationId: String,
  imageDescription: String,
  musicDescription: String,
  musicMood: String,
  imageGenerated: Long,
  musicGenerated: Long,
  artStyle: String
)

case class CachedAsset(
  filePath: Path,
  base64Content: String,
  generatedAt: Long
)

object MediaCache {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val CACHE_DIR = "szork-cache"
  private val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
  private val DEFAULT_MAX_BYTES = 500L * 1024 * 1024 // 500MB
  private def cacheTtlMs: Long = EnvLoader.get("SZORK_CACHE_TTL_MS").flatMap(s => scala.util.Try(s.toLong).toOption).getOrElse(DEFAULT_TTL_MS)
  private def cacheMaxBytes: Long = EnvLoader.get("SZORK_CACHE_MAX_BYTES").flatMap(s => scala.util.Try(s.toLong).toOption).getOrElse(DEFAULT_MAX_BYTES)
  // Simple lock for metadata updates to avoid concurrent corruption
  private object MetaLock
  
  // Ensure cache directory structure exists
  private def ensureCacheDir(gameId: String): Path = {
    val gameDir = Paths.get(CACHE_DIR, gameId)
    val imagesDir = gameDir.resolve("images")
    val musicDir = gameDir.resolve("music")
    
    if (!Files.exists(gameDir)) {
      Files.createDirectories(gameDir)
      Files.createDirectories(imagesDir)
      Files.createDirectories(musicDir)
      logger.info(s"Created cache directories for game: $gameId at path: ${gameDir.toAbsolutePath}")
    }
    gameDir
  }
  
  // Image Caching
  def getCachedImage(gameId: String, locationId: String, imageDescription: String, artStyle: String, provider: String = ""): Option[String] = {
    try {
      val gameDir = ensureCacheDir(gameId)
      val metadataPath = gameDir.resolve("metadata.json")
      val index = MediaCacheCodec.load(metadataPath)
      val key = hashKey(s"$provider|$artStyle|$imageDescription")
      index.locations.get(locationId).flatMap(_.images.find(_.key == key)) match {
        case Some(entry) =>
          val path = gameDir.resolve(entry.path)
          if (Files.exists(path)) {
            val bytes = Files.readAllBytes(path)
            Some(Base64.getEncoder.encodeToString(bytes))
          } else None
        case None =>
          // Backward-compat
          val legacy = gameDir.resolve("images").resolve(s"${locationId}.png")
          if (Files.exists(legacy)) {
            val bytes = Files.readAllBytes(legacy)
            Some(Base64.getEncoder.encodeToString(bytes))
          } else None
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Error reading cached image for game=$gameId, location=$locationId", e)
        None
    }
  }
  
  def cacheImage(gameId: String, locationId: String, imageDescription: String, artStyle: String, base64Content: String, provider: String = ""): Unit = {
    try {
      val gameDir = ensureCacheDir(gameId)
      val imagesDir = gameDir.resolve("images")
      val metadataPath = gameDir.resolve("metadata.json")
      val key = hashKey(s"$provider|$artStyle|$imageDescription")
      val fileName = s"${locationId}_${key}.png"
      val imagePath = imagesDir.resolve(fileName)
      
      // Save image file
      val imageBytes = Base64.getDecoder.decode(base64Content)
      Files.write(imagePath, imageBytes)
      
      // Update metadata index
      val idx0 = MediaCacheCodec.load(metadataPath)
      val locEntries = idx0.locations.getOrElse(locationId, LocationEntries(Nil, Nil))
      val updatedImages = locEntries.images.filterNot(_.key == key) :+ ImageEntry(key, artStyle, provider, imageDescription, imagesDir.getFileName.resolve(fileName).toString, System.currentTimeMillis())
      val idx = MediaIndex(idx0.locations + (locationId -> locEntries.copy(images = updatedImages)))
      MetaLock.synchronized {
        MediaCacheCodec.save(metadataPath, idx)
      }

      prune(gameDir)
      
      logger.info(s"Cached image for game=$gameId, location=$locationId at path: ${imagePath.toAbsolutePath}")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to cache image for game=$gameId, location=$locationId", e)
    }
  }
  
  // Music Caching
  def getCachedMusic(gameId: String, locationId: String, musicDescription: String, mood: String): Option[String] = {
    try {
      val gameDir = ensureCacheDir(gameId)
      val metadataPath = gameDir.resolve("metadata.json")
      val index = MediaCacheCodec.load(metadataPath)
      val key = hashKey(s"replicate|$mood|$musicDescription")
      index.locations.get(locationId).flatMap(_.music.find(_.key == key)) match {
        case Some(entry) =>
          val path = gameDir.resolve(entry.path)
          if (Files.exists(path)) {
            val bytes = Files.readAllBytes(path)
            Some(Base64.getEncoder.encodeToString(bytes))
          } else None
        case None => None
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Error reading cached music for game=$gameId, location=$locationId", e)
        None
    }
  }
  
  def cacheMusic(gameId: String, locationId: String, musicDescription: String, mood: String, base64Content: String): Unit = {
    try {
      val gameDir = ensureCacheDir(gameId)
      val musicDir = gameDir.resolve("music")
      val metadataPath = gameDir.resolve("metadata.json")
      val key = hashKey(s"replicate|$mood|$musicDescription")
      val fileName = s"${locationId}_${mood}_${key}.mp3"
      val musicPath = musicDir.resolve(fileName)
      
      // Save music file
      val musicBytes = Base64.getDecoder.decode(base64Content)
      Files.write(musicPath, musicBytes)
      
      // Update metadata index
      val idx0 = MediaCacheCodec.load(metadataPath)
      val locEntries = idx0.locations.getOrElse(locationId, LocationEntries(Nil, Nil))
      val updatedMusic = locEntries.music.filterNot(_.key == key) :+ MusicEntry(key, mood, "replicate", musicDescription, musicDir.getFileName.resolve(fileName).toString, System.currentTimeMillis())
      val idx = MediaIndex(idx0.locations + (locationId -> locEntries.copy(music = updatedMusic)))
      MetaLock.synchronized {
        MediaCacheCodec.save(metadataPath, idx)
      }

      prune(gameDir)
      
      logger.info(s"Cached music for game=$gameId, location=$locationId, mood=$mood at path: ${musicPath.toAbsolutePath}")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to cache music for game=$gameId, location=$locationId", e)
    }
  }
  
  // Cache Management
  def clearGameCache(gameId: String): Either[String, Unit] = {
    try {
      val gameDir = Paths.get(CACHE_DIR, gameId)
      if (Files.exists(gameDir)) {
        deleteDirectoryRecursively(gameDir)
        logger.info(s"Cleared cache for game: $gameId")
        Right(())
      } else {
        Left(s"No cache found for game: $gameId")
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to clear cache for game: $gameId", e)
        Left(s"Failed to clear cache: ${e.getMessage}")
    }
  }
  
  def getCacheStats(gameId: String): Map[String, Any] = {
    try {
      val gameDir = Paths.get(CACHE_DIR, gameId)
      if (Files.exists(gameDir)) {
        val imageCount = if (Files.exists(gameDir.resolve("images"))) Using.resource(Files.list(gameDir.resolve("images")))(_.count()) else 0L
        val musicCount = if (Files.exists(gameDir.resolve("music"))) Using.resource(Files.list(gameDir.resolve("music")))(_.count()) else 0L
        val totalSize = calculateDirectorySize(gameDir)
        
        Map(
          "gameId" -> gameId,
          "imageCount" -> imageCount,
          "musicCount" -> musicCount,
          "totalSizeBytes" -> totalSize,
          "exists" -> true
        )
      } else {
        Map("gameId" -> gameId, "exists" -> false)
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to get cache stats for game: $gameId", e)
        Map("gameId" -> gameId, "exists" -> false, "error" -> e.getMessage)
    }
  }
  
  // Helper Methods
  private def updateMetadata(metadataPath: Path, locationId: String)(update: Obj => Unit): Unit = {
    val metadataValue = if (Files.exists(metadataPath)) {
      val jsonString = new String(Files.readAllBytes(metadataPath), StandardCharsets.UTF_8)
      read(jsonString)
    } else {
      Obj()
    }
    
    val metadataObj = metadataValue.obj
    val locationDataValue = metadataObj.getOrElse(locationId, Obj())
    val locationData = Obj(locationDataValue.obj)
    
    update(locationData)
    metadataObj(locationId) = locationData
    
    Files.write(metadataPath, Obj(metadataObj).toString().getBytes(StandardCharsets.UTF_8))
  }
  
  private def isSimilarDescription(cached: String, current: String): Boolean = {
    // Simple similarity check - could be enhanced with fuzzy matching
    if (cached == current) {
      true
    } else {
      // Allow for minor differences (e.g., punctuation, case)
      val cachedClean = cached.toLowerCase.replaceAll("[^a-z0-9 ]", "")
      val currentClean = current.toLowerCase.replaceAll("[^a-z0-9 ]", "")
      val similarity = calculateSimilarity(cachedClean, currentClean)
      similarity > 0.8 // 80% similarity threshold
    }
  }
  
  private def calculateSimilarity(str1: String, str2: String): Double = {
    val longer = if (str1.length > str2.length) str1 else str2
    val shorter = if (str1.length > str2.length) str2 else str1
    
    if (longer.length == 0) 1.0
    else {
      val editDistance = levenshteinDistance(longer, shorter)
      (longer.length - editDistance) / longer.length.toDouble
    }
  }
  
  private def levenshteinDistance(str1: String, str2: String): Int = {
    val dp = Array.ofDim[Int](str1.length + 1, str2.length + 1)
    
    for (i <- 0 to str1.length) dp(i)(0) = i
    for (j <- 0 to str2.length) dp(0)(j) = j
    
    for (i <- 1 to str1.length) {
      for (j <- 1 to str2.length) {
        val cost = if (str1(i - 1) == str2(j - 1)) 0 else 1
        dp(i)(j) = math.min(
          math.min(dp(i - 1)(j) + 1, dp(i)(j - 1) + 1),
          dp(i - 1)(j - 1) + cost
        )
      }
    }
    
    dp(str1.length)(str2.length)
  }
  
  private def deleteDirectoryRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      Using.resource(Files.list(path)) { stream =>
        stream.forEach(deleteDirectoryRecursively)
      }
    }
    Files.deleteIfExists(path)
  }
  
  private def calculateDirectorySize(path: Path): Long = {
    if (Files.isDirectory(path)) {
      Using.resource(Files.list(path)) { stream =>
        stream.mapToLong(calculateDirectorySize).sum()
      }
    } else {
      Files.size(path)
    }
  }

  private def prune(gameDir: Path): Unit = {
    try {
      val ttl = cacheTtlMs
      val maxBytes = cacheMaxBytes
      val now = System.currentTimeMillis()
      val subDirs = List("images", "music").map(gameDir.resolve)

      subDirs.filter(Files.exists(_)).foreach { dir =>
        Using.resource(Files.list(dir)) { stream =>
          stream.iterator().asScala.foreach { p =>
            val age = now - Files.getLastModifiedTime(p).toMillis
            if (age > ttl) Files.deleteIfExists(p)
          }
        }
      }

      var size = calculateDirectorySize(gameDir)
      if (size > maxBytes) {
        val files = subDirs
          .filter(Files.exists(_))
          .flatMap { d => Using.resource(Files.list(d))(_.iterator().asScala.toList) }
        val sorted = files.sortBy(p => Files.getLastModifiedTime(p).toMillis)
        val it = sorted.iterator
        while (size > maxBytes && it.hasNext) {
          val p = it.next()
          val s = Files.size(p)
          Files.deleteIfExists(p)
          size -= s
        }
      }
    } catch { case _: Throwable => () }
  }

  private def hashKey(s: String): String = {
    val md = MessageDigest.getInstance("SHA-1")
    val bytes = md.digest(s.getBytes(StandardCharsets.UTF_8))
    bytes.map(b => f"$b%02x").mkString.take(12)
  }
}
