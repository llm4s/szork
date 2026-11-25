package org.llm4s.szork.media

import org.llm4s.config.{ConfigReader, EnvLoader}
import org.llm4s.security.Secret
import org.llm4s.szork.api.{MediaNetworkConfig, MusicPollConfig}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.game.DungeonMasterOutput
import org.slf4j.{Logger, LoggerFactory}

import java.util.Base64
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** Background music from Replicate's MusicGen: one short loop per mood.
  *
  * A track is made by creating a prediction, polling it until it succeeds, and downloading the audio. Whenever the
  * prediction does not succeed (the wait times out, polling keeps failing, the thread is interrupted) it is cancelled,
  * so it does not keep running and being billed.
  *
  * @param replicateApiKey
  *   the Replicate key (None: music is unavailable)
  * @param net
  *   timeouts for the Replicate calls
  * @param poll
  *   how long to wait for a prediction: `maxAttempts` intervals of `pollIntervalMs`, as a deadline (a poll that is
  *   itself slow does not stretch it)
  * @param baseUrl
  *   Replicate's API (a local server in tests)
  */
class MusicGeneration(
  replicateApiKey: Option[Secret],
  net: MediaNetworkConfig,
  poll: MusicPollConfig,
  baseUrl: String = MusicGeneration.ReplicateApi) {
  import MusicGeneration._

  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  def isAvailable: Boolean = replicateApiKey.isDefined

  /** The track for `mood` as Base64 MP3, made now (nothing is cached here: the adventure library keeps the tracks). */
  def generate(mood: MusicMood): SzorkResult[String] =
    replicateApiKey match {
      case None =>
        logger.warn("Music generation disabled - REPLICATE_API_KEY not configured")
        Left(ConfigurationError("Music generation not available - REPLICATE_API_KEY not configured"))
      case Some(key) =>
        val start = System.currentTimeMillis()
        logger.info(s"Starting music generation for mood: ${mood.name}")
        val result = create(key, mood).flatMap { predictionId =>
          await(key, predictionId) match {
            case Outcome.Done(audioUrl) => download(audioUrl)
            case Outcome.Failed(error) => Left(error)
            case Outcome.GaveUp(error) =>
              cancel(key, predictionId)
              Left(error)
            case Outcome.Interrupted =>
              cancel(key, predictionId)
              Thread.currentThread().interrupt()
              Left(MusicGenerationError("Interrupted while waiting for music generation", retryable = false))
          }
        }
        val elapsed = System.currentTimeMillis() - start
        result.fold(
          error => logger.error(s"Music generation for ${mood.name} failed after ${elapsed}ms: ${error.message}"),
          _ => logger.info(s"Music generation for ${mood.name} completed in ${elapsed}ms")
        )
        result
    }

  private def authorization(key: Secret): Map[String, String] = Map("Authorization" -> s"Bearer ${key.reveal}")

  /** Starts a prediction; the failure says whether making the same request again could work. */
  private def create(key: Secret, mood: MusicMood): SzorkResult[String] =
    Try(
      requests.post(
        s"$baseUrl/predictions",
        headers = authorization(key) + ("Content-Type" -> "application/json"),
        data = ujson
          .Obj(
            "version" -> ModelVersion,
            "input" -> ujson.Obj(
              "prompt" -> mood.basePrompt,
              "duration" -> mood.duration,
              // The default is WAV, ten times the size; tracks are kept and served as MP3
              "output_format" -> "mp3",
              "temperature" -> mood.temperature,
              "top_k" -> mood.topK,
              "top_p" -> mood.topP,
              "classifier_free_guidance" -> mood.cfGuidance
            )
          )
          .toString,
        readTimeout = net.readTimeoutMs,
        connectTimeout = net.connectTimeoutMs,
        check = false
      )) match {
      case Failure(e) =>
        Left(NetworkError(s"Could not reach Replicate: ${e.getMessage}", "replicate", Some(e), retryable = true))
      case Success(response) if response.is2xx =>
        Try(ujson.read(response.text())("id").str).toEither.left.map(e =>
          MusicGenerationError(s"Replicate's answer has no prediction id: ${e.getMessage}", Some(e), retryable = false))
      case Success(response) =>
        Left(
          NetworkError(
            s"Failed to create prediction: ${response.statusCode} - ${response.text().take(300)}",
            "replicate",
            retryable = isRetryableStatus(response.statusCode)))
    }

  /** Polls `predictionId` until it ends, or the poll budget (`maxAttempts` intervals) is spent. */
  private def await(key: Secret, predictionId: String): Outcome = {
    val deadline = System.nanoTime() + poll.maxAttempts.toLong * poll.pollIntervalMs * 1000000L

    // Waits one interval, then polls again, unless the budget is spent
    def pause(failures: Int): Outcome =
      if (System.nanoTime() >= deadline)
        Outcome.GaveUp(MusicGenerationError("Timeout waiting for music generation", retryable = true))
      else
        try {
          Thread.sleep(poll.pollIntervalMs)
          next(failures)
        } catch { case _: InterruptedException => Outcome.Interrupted }

    def next(failures: Int): Outcome =
      pollOnce(key, predictionId) match {
        case Right(Some(ended)) => ended
        case Right(None) => pause(0)
        case Left((error, true)) if failures + 1 < TolerableConsecutivePollFailures =>
          logger.warn(s"Polling prediction $predictionId failed (${error.message}); trying again")
          pause(failures + 1)
        case Left((error, _)) => Outcome.GaveUp(error)
      }

    next(0)
  }

  /** One poll: the outcome if the prediction has ended (None: still running), or the error and whether it is transient.
    */
  private def pollOnce(key: Secret, predictionId: String): Either[(SzorkError, Boolean), Option[Outcome]] =
    Try(
      requests.get(
        s"$baseUrl/predictions/$predictionId",
        headers = authorization(key),
        readTimeout = net.readTimeoutMs,
        connectTimeout = net.connectTimeoutMs,
        check = false)) match {
      case Failure(e) =>
        Left((NetworkError(s"Failed to get prediction status: ${e.getMessage}", "replicate", Some(e)), true))
      case Success(response) if response.is2xx =>
        Try(ujson.read(response.text())).toOption match {
          case Some(json) => Right(statusOf(json))
          case None => Left((MusicGenerationError("Replicate's status is not JSON", retryable = true), true))
        }
      case Success(response) =>
        val transient = isRetryableStatus(response.statusCode)
        Left((
          NetworkError(s"Failed to get prediction status: ${response.statusCode}", "replicate", retryable = transient),
          transient))
    }

  /** The outcome a prediction's JSON says it has reached; None while it is still running. */
  private def statusOf(json: ujson.Value): Option[Outcome] =
    json.obj.get("status").collect { case ujson.Str(status) => status } match {
      case Some("succeeded") =>
        val url = json.obj.get("output").flatMap {
          case ujson.Str(url) => Some(url)
          case ujson.Arr(urls) => urls.headOption.collect { case ujson.Str(url) => url }
          case _ => None
        }
        Some(
          url.fold[Outcome](Outcome.Failed(MusicGenerationError("Replicate answered with no audio", retryable = true)))(
            Outcome.Done(_)))
      case Some("failed" | "canceled") =>
        val error = json.obj.get("error").collect { case ujson.Str(e) => e }.getOrElse("Unknown error")
        Some(Outcome.Failed(MusicGenerationError(s"Generation failed: $error", retryable = false)))
      case Some("starting" | "processing") => None
      case other =>
        val status = other.getOrElse("none")
        Some(Outcome.GaveUp(MusicGenerationError(s"Unknown prediction status: $status", retryable = false)))
    }

  /** Asks Replicate to cancel `predictionId`; a failure is only logged. */
  private def cancel(key: Secret, predictionId: String): Unit =
    Try(
      requests.post(
        s"$baseUrl/predictions/$predictionId/cancel",
        headers = authorization(key),
        readTimeout = net.readTimeoutMs,
        connectTimeout = net.connectTimeoutMs,
        check = false
      )) match {
      case Success(response) if response.is2xx => logger.info(s"Cancelled prediction $predictionId")
      case Success(response) => logger.warn(s"Could not cancel prediction $predictionId: ${response.statusCode}")
      case Failure(e) => logger.warn(s"Could not cancel prediction $predictionId: ${e.getMessage}")
    }

  private def download(audioUrl: String): SzorkResult[String] = {
    logger.info(s"Downloading audio from: $audioUrl")
    Try(
      requests.get(audioUrl, readTimeout = net.readTimeoutMs, connectTimeout = net.connectTimeoutMs, check = false)
    ) match {
      case Success(response) if response.is2xx =>
        logger.info(s"Downloaded audio, size: ${response.bytes.length} bytes")
        Right(Base64.getEncoder.encodeToString(response.bytes))
      case Success(response) =>
        Left(
          NetworkError(
            s"Failed to download audio: ${response.statusCode}",
            "audio-download",
            retryable = isRetryableStatus(response.statusCode)))
      case Failure(e) =>
        Left(NetworkError(s"Failed to download audio: ${e.getMessage}", "audio-download", Some(e), retryable = true))
    }
  }
}

object MusicGeneration {

  /** Where Replicate's API is. */
  val ReplicateApi = "https://api.replicate.com/v1"

  /** The MusicGen model version predictions are made with. */
  private val ModelVersion = "671ac645ce5e552cc63a54a2bbff63fcf798043055d2dac5fc9e36a837eedcfb"

  /** How many polls in a row may fail (a timeout, a 5xx) before the wait is given up. */
  private val TolerableConsecutivePollFailures = 3

  /** Music with REPLICATE_API_KEY from `reader` (unavailable without it) and the given timeouts and polling. */
  def apply(net: MediaNetworkConfig, poll: MusicPollConfig, reader: ConfigReader = EnvLoader): MusicGeneration =
    new MusicGeneration(
      reader
        .get("REPLICATE_API_KEY")
        .filter(key => key.nonEmpty && !key.contains("YOUR_REPLICATE_API_KEY"))
        .map(Secret(_)),
      net,
      poll)

  /** Whether a request that got `status` is worth making again: the service was busy or failing, the request not wrong.
    */
  private[media] def isRetryableStatus(status: Int): Boolean = status == 408 || status == 429 || status >= 500

  /** How a wait for a prediction ended. */
  private enum Outcome {

    /** The prediction succeeded; its output is at this URL. */
    case Done(audioUrl: String)

    /** Replicate says the prediction failed or was cancelled: nothing is running, so there is nothing to cancel. */
    case Failed(error: SzorkError)

    /** The wait was given up while the prediction may still be running: it must be cancelled. */
    case GaveUp(error: SzorkError)

    /** The thread was interrupted while waiting. */
    case Interrupted
  }

  case class MusicMood(
    name: String,
    basePrompt: String,
    duration: Int = 10, // seconds
    temperature: Double = 1.0, // Generation randomness
    topK: Int = 250,
    topP: Double = 0.0,
    cfGuidance: Int = 3
  )

  /** Music settings for each of the Dungeon Master's moods ([[DungeonMasterOutput.MusicMoods]]). */
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

    /** Every mood, in the order [[DungeonMasterOutput.MusicMoods]] lists their names. */
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

    /** The mood played for a scene whose mood is not one of [[all]]. */
    def orDefault(name: String): MusicMood =
      byName(name).orElse(byName(DungeonMasterOutput.DefaultMusicMood)).getOrElse(EXPLORATION)
  }
}
