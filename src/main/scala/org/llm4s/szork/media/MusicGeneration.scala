package org.llm4s.szork.media
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

import org.slf4j.{Logger, LoggerFactory}
import requests._
import org.llm4s.config.EnvLoader
import org.llm4s.szork.api.MediaNetworkConfig
import org.llm4s.szork.api.MusicPollConfig
import java.util.Base64
import ujson._
import java.net.URI
import scala.util.{Failure, Success, Try, Using}

class MusicGeneration {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val net = MediaNetworkConfig.instance
  private val replicateApiKey =
    EnvLoader.get("REPLICATE_API_KEY").filter(key => key.nonEmpty && !key.contains("YOUR_REPLICATE_API_KEY"))

  def isAvailable: Boolean = replicateApiKey.isDefined

  case class MusicMood(
    name: String,
    basePrompt: String,
    duration: Int = 10, // seconds
    temperature: Double = 1.0, // Generation randomness
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
    val ENTRANCE = MusicMood(
      "entrance",
      "mysterious cave entrance, deep ambient drones, distant echoes, foreboding orchestral, dark fantasy atmosphere",
      duration = 12,
      temperature = 0.9
    )

    val EXPLORATION = MusicMood(
      "exploration",
      "adventurous fantasy exploration, light orchestral strings, curious woodwinds, hopeful melodies, discovery theme",
      duration = 10,
      temperature = 1.0
    )

    val COMBAT = MusicMood(
      "combat",
      "intense battle music, war drums, aggressive strings, brass stabs, epic orchestral combat, fast tempo action",
      duration = 8,
      temperature = 0.8,
      cfGuidance = 4
    )

    val VICTORY = MusicMood(
      "victory",
      "triumphant fanfare, heroic brass section, celebratory orchestral, epic victory theme, uplifting crescendo",
      duration = 8,
      temperature = 0.7)

    val DUNGEON = MusicMood(
      "dungeon",
      "dark dungeon ambience, ominous low strings, dripping water, distant chains, suspenseful horror atmosphere",
      duration = 12,
      temperature = 1.1)

    val FOREST = MusicMood(
      "forest",
      "enchanted forest ambience, bird songs, rustling leaves, soft Celtic flutes, mystical harp, nature magic",
      duration = 12,
      temperature = 1.0)

    val TOWN = MusicMood(
      "town",
      "medieval town square, lute and mandolin, bustling marketplace, folk dance rhythm, cheerful tavern music",
      duration = 10,
      temperature = 0.9)

    val MYSTERY = MusicMood(
      "mystery",
      "mysterious puzzle atmosphere, subtle piano, ethereal pads, contemplative strings, building intrigue",
      duration = 10,
      temperature = 1.2)

    // Additional atmospheric moods
    val CASTLE = MusicMood(
      "castle",
      "grand castle hall, regal horns, medieval court music, stone echoes, nobility theme, harpsichord",
      duration = 10,
      temperature = 0.8)

    val UNDERWATER = MusicMood(
      "underwater",
      "underwater cavern, muffled sounds, whale songs, bubbling effects, mysterious aquatic ambience",
      duration = 12,
      temperature = 1.3)

    val TEMPLE = MusicMood(
      "temple",
      "ancient temple, Gregorian chants, sacred bells, divine atmosphere, ethereal choir, spiritual resonance",
      duration = 12,
      temperature = 0.9)

    val BOSS = MusicMood(
      "boss",
      "epic boss battle, massive orchestral, choir vocals, intense percussion, dark powerful theme, climactic",
      duration = 10,
      temperature = 0.7,
      cfGuidance = 5
    )

    val STEALTH = MusicMood(
      "stealth",
      "stealthy infiltration, minimal percussion, tense strings, quiet footsteps, suspenseful atmosphere",
      duration = 10,
      temperature = 1.0)

    val TREASURE = MusicMood(
      "treasure",
      "treasure discovery, magical chimes, sparkling sounds, wonder and awe, mystical revelation",
      duration = 8,
      temperature = 0.8)

    val DANGER = MusicMood(
      "danger",
      "imminent danger, rising tension, warning drums, dissonant strings, heart-pounding suspense",
      duration = 8,
      temperature = 0.9,
      cfGuidance = 4)

    val PEACEFUL = MusicMood(
      "peaceful",
      "peaceful resting place, soft piano, gentle strings, calm breathing space, meditative atmosphere",
      duration = 12,
      temperature = 1.0)

    val all: List[MusicMood] = List(
      ENTRANCE,
      EXPLORATION,
      COMBAT,
      VICTORY,
      DUNGEON,
      FOREST,
      TOWN,
      MYSTERY,
      CASTLE,
      UNDERWATER,
      TEMPLE,
      BOSS,
      STEALTH,
      TREASURE,
      DANGER,
      PEACEFUL)

    /** The mood called `name` (as the Dungeon Master's scenes name them). */
    def byName(name: String): Option[MusicMood] = all.find(_.name.equalsIgnoreCase(name.trim))
  }

  def generateMusic(mood: MusicMood, context: String = ""): SzorkResult[String] =
    generateMusicWithCache(mood, context, None, None)

  def generateMusicWithCache(
    mood: MusicMood,
    context: String = "",
    gameId: Option[String] = None,
    locationId: Option[String] = None): SzorkResult[String] = {
    if (!isAvailable) {
      logger.warn("Music generation disabled - REPLICATE_API_KEY not configured")
      return Left(ConfigurationError("Music generation not available - REPLICATE_API_KEY not configured"))
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
        ).toString,
        readTimeout = net.readTimeoutMs,
        connectTimeout = net.connectTimeoutMs
      )

      if (createResponse.statusCode != 201) {
        val errorMsg = s"Failed to create prediction: ${createResponse.statusCode} - ${createResponse.text()}"
        logger.error(errorMsg)
        return Left(NetworkError(errorMsg, "replicate", retryable = createResponse.statusCode >= 500))
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
              logger.info(
                s"Music generation completed in ${musicGenerationTime}ms (${base64Audio.length} bytes base64)")

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
              Left(MusicGenerationError(s"Audio download failed: $error", retryable = true))
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
        Left(MusicGenerationError(s"Music generation error: ${e.getMessage}", Some(e), retryable = true))
    }
  }

  private def pollPrediction(
    predictionId: String,
    maxAttempts: Int = MusicPollConfig.instance.maxAttempts): SzorkResult[String] = {
    if (!isAvailable) {
      return Left(ConfigurationError("Music generation not available - REPLICATE_API_KEY not configured"))
    }

    var attempts = 0

    while (attempts < maxAttempts) {
      val response = get(
        s"https://api.replicate.com/v1/predictions/$predictionId",
        headers = Map("Authorization" -> s"Bearer ${replicateApiKey.get}"),
        readTimeout = net.readTimeoutMs,
        connectTimeout = net.connectTimeoutMs
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
            return Left(MusicGenerationError(s"Generation failed: $error", retryable = false))

          case "processing" | "starting" =>
            Thread.sleep(MusicPollConfig.instance.pollIntervalMs) // Wait before polling again
            attempts += 1

          case _ =>
            return Left(MusicGenerationError(s"Unknown prediction status: $status", retryable = false))
        }
      } else {
        return Left(
          NetworkError(
            s"Failed to get prediction status: ${response.statusCode}",
            "replicate",
            retryable = response.statusCode >= 500))
      }
    }

    // Stop the prediction rather than let it run on (and be billed) after we have given up on it
    cancelPrediction(predictionId)
    Left(MusicGenerationError("Timeout waiting for music generation", retryable = true))
  }

  /** Asks Replicate to cancel `predictionId`; a failure is only logged. */
  private def cancelPrediction(predictionId: String): Unit =
    replicateApiKey.foreach { key =>
      Try(
        post(
          s"https://api.replicate.com/v1/predictions/$predictionId/cancel",
          headers = Map("Authorization" -> s"Bearer $key"),
          readTimeout = net.readTimeoutMs,
          connectTimeout = net.connectTimeoutMs,
          check = false
        )) match {
        case Success(response) if response.statusCode == 200 => logger.info(s"Cancelled prediction $predictionId")
        case Success(response) => logger.warn(s"Could not cancel prediction $predictionId: ${response.statusCode}")
        case Failure(e) => logger.warn(s"Could not cancel prediction $predictionId: ${e.getMessage}")
      }
    }

  private def downloadAndEncodeAudio(audioUrl: String): SzorkResult[String] =
    try {
      logger.info(s"Downloading audio from: $audioUrl")

      val uri = new URI(audioUrl)
      val url = uri.toURL()
      val connection = url.openConnection()
      try {
        connection.setConnectTimeout(net.connectTimeoutMs)
        connection.setReadTimeout(net.readTimeoutMs)
      } catch { case _: Throwable => () }
      // Closed even when the read fails part way
      val audioBytes = Using.resource(connection.getInputStream)(_.readAllBytes())

      val base64Audio = Base64.getEncoder.encodeToString(audioBytes)
      logger.info(s"Downloaded and encoded audio, size: ${audioBytes.length} bytes")

      Right(base64Audio)

    } catch {
      case e: Exception =>
        logger.error("Error downloading audio", e)
        Left(NetworkError(s"Failed to download audio: ${e.getMessage}", "audio-download", retryable = true))
    }
}

object MusicGeneration {
  def apply(): MusicGeneration = new MusicGeneration()
}
