package org.llm4s.szork

import org.slf4j.LoggerFactory
import requests._
import org.llm4s.config.EnvLoader
import java.util.Base64
import ujson._
import java.io.ByteArrayOutputStream
import java.net.URI

class MusicGeneration {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val replicateApiKey = EnvLoader.get("REPLICATE_API_KEY").filter(key => 
    key.nonEmpty && !key.contains("YOUR_REPLICATE_API_KEY")
  )
  
  def isAvailable: Boolean = replicateApiKey.isDefined
  
  case class MusicMood(
    name: String,
    basePrompt: String,
    duration: Int = 10,  // seconds
    temperature: Double = 1.0,  // Generation randomness
    topK: Int = 250,
    topP: Double = 0.0,
    cfGuidance: Int = 3
  ) {
    def generatePrompt(context: String = ""): String = {
      // Add context-aware variations to the base prompt
      val contextualAdditions = extractContextualElements(context)
      s"$basePrompt${if (contextualAdditions.nonEmpty) s", $contextualAdditions" else ""}"
    }
    
    private def extractContextualElements(text: String): String = {
      val lowerText = text.toLowerCase
      val elements = scala.collection.mutable.ListBuffer[String]()
      
      // Add atmospheric elements based on context
      if (lowerText.contains("rain") || lowerText.contains("storm")) elements += "rain sounds"
      if (lowerText.contains("wind")) elements += "wind effects"
      if (lowerText.contains("fire") || lowerText.contains("torch")) elements += "crackling fire"
      if (lowerText.contains("water") || lowerText.contains("stream")) elements += "flowing water"
      if (lowerText.contains("echo")) elements += "reverb effects"
      
      // Add emotional modifiers
      if (lowerText.contains("tense")) elements += "building tension"
      if (lowerText.contains("peaceful")) elements += "serene atmosphere"
      if (lowerText.contains("danger")) elements += "threatening undertones"
      if (lowerText.contains("ancient")) elements += "ancient mystical"
      if (lowerText.contains("magic")) elements += "magical sparkles"
      
      elements.mkString(", ")
    }
  }
  
  object MusicMoods {
    // Main location moods with more variety
    val ENTRANCE = MusicMood("entrance", 
      "mysterious cave entrance, deep ambient drones, distant echoes, foreboding orchestral, dark fantasy atmosphere", 
      duration = 12, temperature = 0.9)
    
    val EXPLORATION = MusicMood("exploration", 
      "adventurous fantasy exploration, light orchestral strings, curious woodwinds, hopeful melodies, discovery theme",
      duration = 10, temperature = 1.0)
    
    val COMBAT = MusicMood("combat", 
      "intense battle music, war drums, aggressive strings, brass stabs, epic orchestral combat, fast tempo action",
      duration = 8, temperature = 0.8, cfGuidance = 4)
    
    val VICTORY = MusicMood("victory", 
      "triumphant fanfare, heroic brass section, celebratory orchestral, epic victory theme, uplifting crescendo",
      duration = 8, temperature = 0.7)
    
    val DUNGEON = MusicMood("dungeon", 
      "dark dungeon ambience, ominous low strings, dripping water, distant chains, suspenseful horror atmosphere",
      duration = 12, temperature = 1.1)
    
    val FOREST = MusicMood("forest", 
      "enchanted forest ambience, bird songs, rustling leaves, soft Celtic flutes, mystical harp, nature magic",
      duration = 12, temperature = 1.0)
    
    val TOWN = MusicMood("town", 
      "medieval town square, lute and mandolin, bustling marketplace, folk dance rhythm, cheerful tavern music",
      duration = 10, temperature = 0.9)
    
    val MYSTERY = MusicMood("mystery", 
      "mysterious puzzle atmosphere, subtle piano, ethereal pads, contemplative strings, building intrigue",
      duration = 10, temperature = 1.2)
    
    // Additional atmospheric moods
    val CASTLE = MusicMood("castle",
      "grand castle hall, regal horns, medieval court music, stone echoes, nobility theme, harpsichord",
      duration = 10, temperature = 0.8)
    
    val UNDERWATER = MusicMood("underwater",
      "underwater cavern, muffled sounds, whale songs, bubbling effects, mysterious aquatic ambience",
      duration = 12, temperature = 1.3)
    
    val TEMPLE = MusicMood("temple",
      "ancient temple, Gregorian chants, sacred bells, divine atmosphere, ethereal choir, spiritual resonance",
      duration = 12, temperature = 0.9)
    
    val BOSS = MusicMood("boss",
      "epic boss battle, massive orchestral, choir vocals, intense percussion, dark powerful theme, climactic",
      duration = 10, temperature = 0.7, cfGuidance = 5)
    
    val STEALTH = MusicMood("stealth",
      "stealthy infiltration, minimal percussion, tense strings, quiet footsteps, suspenseful atmosphere",
      duration = 10, temperature = 1.0)
    
    val TREASURE = MusicMood("treasure",
      "treasure discovery, magical chimes, sparkling sounds, wonder and awe, mystical revelation",
      duration = 8, temperature = 0.8)
    
    val DANGER = MusicMood("danger",
      "imminent danger, rising tension, warning drums, dissonant strings, heart-pounding suspense",
      duration = 8, temperature = 0.9, cfGuidance = 4)
    
    val PEACEFUL = MusicMood("peaceful",
      "peaceful resting place, soft piano, gentle strings, calm breathing space, meditative atmosphere",
      duration = 12, temperature = 1.0)
  }
  
  def generateMusic(mood: MusicMood, context: String = ""): Either[String, String] = {
    generateMusicWithCache(mood, context, None, None)
  }
  
  def generateMusicWithCache(mood: MusicMood, context: String = "", gameId: Option[String] = None, locationId: Option[String] = None): Either[String, String] = {
    if (!isAvailable) {
      logger.warn("Music generation disabled - REPLICATE_API_KEY not configured")
      return Left("Music generation not available")
    }
    
    val prompt = mood.generatePrompt(context)
    
    // Check cache first if gameId and locationId are provided
    (gameId, locationId) match {
      case (Some(gId), Some(lId)) =>
        MediaCache.getCachedMusic(gId, lId, prompt, mood.name) match {
          case Some(cachedMusic) =>
            logger.info(s"Using cached music for game=$gId, location=$lId, mood=${mood.name} (0ms - from cache)")
            return Right(cachedMusic)
          case None =>
            logger.info(s"No cached music found for game=$gId, location=$lId, mood=${mood.name} - generating new music")
        }
      case _ =>
        logger.info(s"No cache info provided - generating music directly")
    }
    
    val musicStartTime = System.currentTimeMillis()
    logger.info(s"Starting music generation for mood: ${mood.name}, prompt: ${prompt.take(100)}...")
    
    try {
      // Create prediction
      val createResponse = post(
        "https://api.replicate.com/v1/predictions",
        headers = Map(
          "Authorization" -> s"Bearer ${replicateApiKey.get}",
          "Content-Type" -> "application/json"
        ),
        data = Obj(
          "version" -> "671ac645ce5e552cc63a54a2bbff63fcf798043055d2dac5fc9e36a837eedcfb",
          "input" -> Obj(
            "prompt" -> prompt,
            "duration" -> mood.duration,
            "temperature" -> mood.temperature,
            "top_k" -> mood.topK,
            "top_p" -> mood.topP,
            "classifier_free_guidance" -> mood.cfGuidance
          )
        ).toString
      )
      
      if (createResponse.statusCode != 201) {
        val error = s"Failed to create prediction: ${createResponse.statusCode} - ${createResponse.text()}"
        logger.error(error)
        return Left(error)
      }
      
      val predictionId = read(createResponse.text())("id").str
      logger.info(s"Created prediction: $predictionId")
      
      // Poll for completion
      val result = pollPrediction(predictionId)
      result match {
        case Right(audioUrl) =>
          downloadAndEncodeAudio(audioUrl) match {
            case Right(base64Audio) =>
              val musicGenerationTime = System.currentTimeMillis() - musicStartTime
              logger.info(s"Music generation completed in ${musicGenerationTime}ms (${base64Audio.length} bytes base64)")
              
              // Cache the generated music if gameId and locationId are provided
              (gameId, locationId) match {
                case (Some(gId), Some(lId)) =>
                  MediaCache.cacheMusic(gId, lId, prompt, mood.name, base64Audio)
                  logger.info(s"Cached generated music for game=$gId, location=$lId, mood=${mood.name}")
                case _ =>
                  logger.debug("No cache info provided - skipping music caching")
              }
              Right(base64Audio)
            case Left(error) =>
              val musicGenerationTime = System.currentTimeMillis() - musicStartTime
              logger.error(s"Music download/encoding failed after ${musicGenerationTime}ms: $error")
              Left(error)
          }
        case Left(error) =>
          val musicGenerationTime = System.currentTimeMillis() - musicStartTime
          logger.error(s"Music generation failed after ${musicGenerationTime}ms: $error")
          Left(error)
      }
      
    } catch {
      case e: Exception =>
        val musicGenerationTime = System.currentTimeMillis() - musicStartTime
        logger.error(s"Error during music generation after ${musicGenerationTime}ms", e)
        Left(s"Music generation error: ${e.getMessage}")
    }
  }
  
  private def pollPrediction(predictionId: String, maxAttempts: Int = 30): Either[String, String] = {
    if (!isAvailable) {
      return Left("Music generation not available")
    }
    
    var attempts = 0
    
    while (attempts < maxAttempts) {
      val response = get(
        s"https://api.replicate.com/v1/predictions/$predictionId",
        headers = Map("Authorization" -> s"Bearer ${replicateApiKey.get}")
      )
      
      if (response.statusCode == 200) {
        val json = read(response.text())
        val status = json("status").str
        
        logger.debug(s"Prediction status: $status")
        
        status match {
          case "succeeded" =>
            val audioUrl = json("output").str
            logger.info(s"Music generation succeeded: $audioUrl")
            return Right(audioUrl)
            
          case "failed" =>
            val error = json.obj.get("error").map(_.str).getOrElse("Unknown error")
            logger.error(s"Music generation failed: $error")
            return Left(s"Generation failed: $error")
            
          case "processing" | "starting" =>
            Thread.sleep(1000)  // Wait 1 second before polling again
            attempts += 1
            
          case _ =>
            return Left(s"Unknown status: $status")
        }
      } else {
        return Left(s"Failed to get prediction status: ${response.statusCode}")
      }
    }
    
    Left("Timeout waiting for music generation")
  }
  
  private def downloadAndEncodeAudio(audioUrl: String): Either[String, String] = {
    try {
      logger.info(s"Downloading audio from: $audioUrl")
      
      val uri = new URI(audioUrl)
      val url = uri.toURL()
      val connection = url.openConnection()
      val inputStream = connection.getInputStream
      
      val outputStream = new ByteArrayOutputStream()
      val buffer = new Array[Byte](4096)
      var bytesRead = 0
      
      while ({bytesRead = inputStream.read(buffer); bytesRead != -1}) {
        outputStream.write(buffer, 0, bytesRead)
      }
      
      inputStream.close()
      val audioBytes = outputStream.toByteArray
      outputStream.close()
      
      val base64Audio = Base64.getEncoder.encodeToString(audioBytes)
      logger.info(s"Downloaded and encoded audio, size: ${audioBytes.length} bytes")
      
      Right(base64Audio)
      
    } catch {
      case e: Exception =>
        logger.error("Error downloading audio", e)
        Left(s"Failed to download audio: ${e.getMessage}")
    }
  }
  
  def detectMoodFromText(text: String): MusicMood = {
    val lowerText = text.toLowerCase
    
    // Priority-based mood detection with weighted scoring
    val moodScores = scala.collection.mutable.Map[MusicMood, Int]()
    
    // Combat scenarios
    if (lowerText.contains("boss") || lowerText.contains("final battle") || lowerText.contains("powerful enemy")) {
      moodScores(MusicMoods.BOSS) = 10
    }
    if (lowerText.contains("battle") || lowerText.contains("attack") || lowerText.contains("fight") || 
        lowerText.contains("combat") || lowerText.contains("enemy")) {
      moodScores(MusicMoods.COMBAT) = 8
    }
    
    // Victory and achievement
    if (lowerText.contains("victory") || lowerText.contains("defeated") || lowerText.contains("won") || 
        lowerText.contains("triumph") || lowerText.contains("conquered")) {
      moodScores(MusicMoods.VICTORY) = 9
    }
    if (lowerText.contains("treasure") || lowerText.contains("gold") || lowerText.contains("reward") || 
        lowerText.contains("chest") || lowerText.contains("loot")) {
      moodScores(MusicMoods.TREASURE) = 8
    }
    
    // Locations - Dungeon/Cave
    if (lowerText.contains("dungeon") || lowerText.contains("dark") || lowerText.contains("cave") || 
        lowerText.contains("underground") || lowerText.contains("cavern")) {
      moodScores(MusicMoods.DUNGEON) = 7
    }
    
    // Locations - Natural
    if (lowerText.contains("forest") || lowerText.contains("trees") || lowerText.contains("woods") || 
        lowerText.contains("grove") || lowerText.contains("nature")) {
      moodScores(MusicMoods.FOREST) = 7
    }
    if (lowerText.contains("underwater") || lowerText.contains("lake") || lowerText.contains("ocean") || 
        lowerText.contains("river") || lowerText.contains("aquatic")) {
      moodScores(MusicMoods.UNDERWATER) = 8
    }
    
    // Locations - Civilized
    if (lowerText.contains("castle") || lowerText.contains("throne") || lowerText.contains("palace") || 
        lowerText.contains("royal") || lowerText.contains("court")) {
      moodScores(MusicMoods.CASTLE) = 7
    }
    if (lowerText.contains("temple") || lowerText.contains("shrine") || lowerText.contains("altar") || 
        lowerText.contains("sacred") || lowerText.contains("holy")) {
      moodScores(MusicMoods.TEMPLE) = 7
    }
    if (lowerText.contains("town") || lowerText.contains("village") || lowerText.contains("market") || 
        lowerText.contains("inn") || lowerText.contains("tavern")) {
      moodScores(MusicMoods.TOWN) = 6
    }
    
    // Atmosphere and situations
    if (lowerText.contains("sneak") || lowerText.contains("stealth") || lowerText.contains("hide") || 
        lowerText.contains("quietly") || lowerText.contains("silent")) {
      moodScores(MusicMoods.STEALTH) = 8
    }
    if (lowerText.contains("danger") || lowerText.contains("warning") || lowerText.contains("trap") || 
        lowerText.contains("careful") || lowerText.contains("threat")) {
      moodScores(MusicMoods.DANGER) = 7
    }
    if (lowerText.contains("puzzle") || lowerText.contains("mystery") || lowerText.contains("riddle") || 
        lowerText.contains("strange") || lowerText.contains("mysterious")) {
      moodScores(MusicMoods.MYSTERY) = 6
    }
    if (lowerText.contains("rest") || lowerText.contains("safe") || lowerText.contains("peaceful") || 
        lowerText.contains("calm") || lowerText.contains("relax")) {
      moodScores(MusicMoods.PEACEFUL) = 7
    }
    
    // Entry points
    if (lowerText.contains("entrance") || lowerText.contains("begin") || lowerText.contains("start") || 
        lowerText.contains("doorway") || lowerText.contains("threshold")) {
      moodScores(MusicMoods.ENTRANCE) = 5
    }
    
    // Default exploration mood gets a base score
    moodScores(MusicMoods.EXPLORATION) = 3
    
    // Return the mood with the highest score
    if (moodScores.isEmpty) {
      MusicMoods.EXPLORATION
    } else {
      moodScores.maxBy(_._2)._1
    }
  }
}

object MusicGeneration {
  def apply(): MusicGeneration = new MusicGeneration()
}