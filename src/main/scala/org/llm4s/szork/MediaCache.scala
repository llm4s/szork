package org.llm4s.szork

import ujson._
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.slf4j.LoggerFactory

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
  def getCachedImage(gameId: String, locationId: String, imageDescription: String, artStyle: String): Option[String] = {
    try {
      val gameDir = ensureCacheDir(gameId)
      val imagePath = gameDir.resolve("images").resolve(s"${locationId}.png")
      val metadataPath = gameDir.resolve("metadata.json")
      
      if (Files.exists(imagePath) && Files.exists(metadataPath)) {
        // Check if metadata matches current request
        val metadataJson = new String(Files.readAllBytes(metadataPath), StandardCharsets.UTF_8)
        val metadata = read(metadataJson)
        
        val locationMetadata = metadata.obj.get(locationId)
        locationMetadata match {
          case Some(locationData) =>
            val cachedImageDesc = locationData("imageDescription").str
            val cachedArtStyle = locationData("artStyle").str
            
            // Check if description and art style match (allowing for minor variations)
            if (isSimilarDescription(cachedImageDesc, imageDescription) && cachedArtStyle == artStyle) {
              val imageBytes = Files.readAllBytes(imagePath)
              val base64Content = Base64.getEncoder.encodeToString(imageBytes)
              logger.info(s"Using cached image for game=$gameId, location=$locationId from path: ${imagePath.toAbsolutePath}")
              Some(base64Content)
            } else {
              logger.info(s"Image cache miss - description or style changed for game=$gameId, location=$locationId")
              None
            }
          case None =>
            logger.info(s"No metadata found for location=$locationId in game=$gameId")
            None
        }
      } else {
        logger.info(s"No cached image found for game=$gameId, location=$locationId")
        None
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Error reading cached image for game=$gameId, location=$locationId", e)
        None
    }
  }
  
  def cacheImage(gameId: String, locationId: String, imageDescription: String, artStyle: String, base64Content: String): Unit = {
    try {
      val gameDir = ensureCacheDir(gameId)
      val imagePath = gameDir.resolve("images").resolve(s"${locationId}.png")
      val metadataPath = gameDir.resolve("metadata.json")
      
      // Save image file
      val imageBytes = Base64.getDecoder.decode(base64Content)
      Files.write(imagePath, imageBytes)
      
      // Update metadata
      updateMetadata(metadataPath, locationId) { locationData =>
        locationData("imageDescription") = imageDescription
        locationData("artStyle") = artStyle
        locationData("imageGenerated") = System.currentTimeMillis()
      }
      
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
      val musicPath = gameDir.resolve("music").resolve(s"${locationId}_${mood}.mp3")
      val metadataPath = gameDir.resolve("metadata.json")
      
      if (Files.exists(musicPath) && Files.exists(metadataPath)) {
        // Check if metadata matches current request
        val metadataJson = new String(Files.readAllBytes(metadataPath), StandardCharsets.UTF_8)
        val metadata = read(metadataJson)
        
        val locationMetadata = metadata.obj.get(locationId)
        locationMetadata match {
          case Some(locationData) =>
            val cachedMusicDesc = locationData("musicDescription").str
            val cachedMood = locationData("musicMood").str
            
            // Check if description and mood match
            if (isSimilarDescription(cachedMusicDesc, musicDescription) && cachedMood == mood) {
              val musicBytes = Files.readAllBytes(musicPath)
              val base64Content = Base64.getEncoder.encodeToString(musicBytes)
              logger.info(s"Using cached music for game=$gameId, location=$locationId, mood=$mood from path: ${musicPath.toAbsolutePath}")
              Some(base64Content)
            } else {
              logger.info(s"Music cache miss - description or mood changed for game=$gameId, location=$locationId")
              None
            }
          case None =>
            logger.info(s"No metadata found for location=$locationId in game=$gameId")
            None
        }
      } else {
        logger.info(s"No cached music found for game=$gameId, location=$locationId, mood=$mood")
        None
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
      val musicPath = gameDir.resolve("music").resolve(s"${locationId}_${mood}.mp3")
      val metadataPath = gameDir.resolve("metadata.json")
      
      // Save music file
      val musicBytes = Base64.getDecoder.decode(base64Content)
      Files.write(musicPath, musicBytes)
      
      // Update metadata
      updateMetadata(metadataPath, locationId) { locationData =>
        locationData("musicDescription") = musicDescription
        locationData("musicMood") = mood
        locationData("musicGenerated") = System.currentTimeMillis()
      }
      
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
        val imageCount = Files.list(gameDir.resolve("images")).count()
        val musicCount = Files.list(gameDir.resolve("music")).count()
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
      Files.list(path).forEach(deleteDirectoryRecursively)
    }
    Files.deleteIfExists(path)
  }
  
  private def calculateDirectorySize(path: Path): Long = {
    if (Files.isDirectory(path)) {
      Files.list(path).mapToLong(calculateDirectorySize).sum()
    } else {
      Files.size(path)
    }
  }
}