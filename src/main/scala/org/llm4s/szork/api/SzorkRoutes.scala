package org.llm4s.szork.api

import cask._
import org.slf4j.LoggerFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.trace.Tracing
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import org.llm4s.szork.game._
import org.llm4s.szork.websocket.TypedWebSocketServer
import org.llm4s.szork.persistence.StepPersistence
import org.llm4s.szork.error.PersistenceError
import org.llm4s.szork.media.MediaCache
import org.llm4s.szork.adventures.AdventureLibrary

/** The HTTP API: feature flags, saved games, theme validation and adventure generation. All gameplay goes through the
  * WebSocket server.
  */
final class SzorkRoutes(
  config: SzorkConfig,
  capabilities: Capabilities,
  llmClient: LLMClient,
  tracing: Option[Tracing],
  wsServer: TypedWebSocketServer,
  library: AdventureLibrary,
  deleteWait: FiniteDuration = SzorkRoutes.DeleteWait
) extends cask.Routes {

  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  @get("/api/feature-flags")
  def featureFlags(): ujson.Value = {
    def flag(enabled: Boolean, available: Boolean) = ujson.Obj("enabled" -> enabled, "available" -> available)
    ujson.Obj(
      "llm" -> ujson.Obj(
        "provider" -> config.llmConfig.map(_.provider).getOrElse(""),
        "available" -> config.llmConfig.isDefined
      ),
      "image" -> ujson.Obj(
        "enabled" -> config.imageGenerationEnabled,
        "provider" -> ImageProvider.toString(config.imageProvider),
        "available" -> capabilities.image
      ),
      "music" -> flag(config.musicEnabled, capabilities.music),
      "tts" -> flag(config.ttsEnabled, capabilities.tts),
      "stt" -> flag(config.sttEnabled, capabilities.stt)
    )
  }

  // POST endpoints accept only JSON bodies: a cross-site page can send a "simple" text/plain POST without a CORS
  // preflight, but not an application/json one, so this stops other sites spending the server's API credits.
  private def isJson(request: Request): Boolean =
    request.headers.get("content-type").exists(_.exists(_.toLowerCase.startsWith("application/json")))

  private val notJson: ujson.Value =
    ujson.Obj("status" -> "error", "message" -> "Content-Type must be application/json")

  @post("/api/game/validate-theme")
  def validateTheme(request: Request): ujson.Value = {
    if (!isJson(request)) return notJson
    Try(ujson.read(request.text())("theme").str).toOption match {
      case None => ujson.Obj("valid" -> false, "message" -> "Request must be a JSON object with a \"theme\" string")
      case Some(description) if description.length > SzorkConfig.MaxThemeChars =>
        ujson.Obj("valid" -> false, "message" -> SzorkRoutes.ThemeTooLong)
      case Some(description) => ThemeValidator.validate(description)(using llmClient).toJson
    }
  }

  @get("/api/health")
  def health(): ujson.Value =
    ujson.Obj(
      "status" -> "healthy",
      "service" -> "szork-server"
    )

  @get("/api/games")
  def listSavedGames(): ujson.Value = {
    val gamesJson = StepPersistence.listGames().map { metadata =>
      ujson.Obj(
        "gameId" -> metadata.gameId,
        "title" -> metadata.adventureTitle,
        "theme" -> metadata.theme,
        "artStyle" -> metadata.artStyle,
        // As numbers: ujson writes a Long as a string, which the browser cannot read as a date
        "createdAt" -> ujson.Num(metadata.createdAt.toDouble),
        "lastPlayed" -> ujson.Num(metadata.lastPlayed.toDouble),
        "totalPlayTime" -> ujson.Num(metadata.totalPlayTime.toDouble)
      )
    }
    ujson.Obj(
      "status" -> "success",
      "games" -> gamesJson
    )
  }

  @post("/api/game/generate-adventure")
  def generateAdventure(request: Request): ujson.Value = {
    if (!isJson(request)) return notJson
    AdventureRequest.parse(request.text()) match {
      case Left(message) =>
        logger.warn(s"Rejected generate-adventure request: $message")
        ujson.Obj("status" -> "error", "message" -> message)
      case Right((theme, _)) if theme.exists(_.prompt.length > SzorkConfig.MaxThemeChars) =>
        ujson.Obj("status" -> "error", "message" -> SzorkRoutes.ThemeTooLong)
      case Right((theme, _)) =>
        generateAdventureFor(theme)
    }
  }

  private def generateAdventureFor(theme: Option[GameTheme]): ujson.Value = {
    val themePrompt = theme.map(_.prompt).getOrElse("classic fantasy adventure")
    logger.info(s"Generating adventure for theme: ${theme.map(_.name).getOrElse("default")}")

    AdventureGenerator.generateAdventureOutline(themePrompt, tracing, config.reasoningEffort)(using llmClient) match {
      case Right(outline) =>
        logger.info(s"Adventure outline generated: ${outline.title}")
        ujson.Obj(
          "status" -> "success",
          "outline" -> AdventureGenerator.outlineToJson(outline)
        )
      case Left(error) =>
        logger.error(s"Failed to generate adventure outline: $error")
        ujson.Obj(
          "status" -> "error",
          "message" -> error.userMessage
        )
    }
  }

  // ============= The adventure library =============

  @get("/api/adventures")
  def adventures(): ujson.Value =
    ujson.Obj("status" -> "success", "adventures" -> library.list.map(AdventureLibrary.summaryJson))

  /** Keeps a designed adventure in the library, to be played again: `{outline, theme?, artStyle?}`. */
  @post("/api/adventures")
  def keepAdventure(request: Request): ujson.Value = {
    if (!isJson(request)) return notJson
    val kept = for {
      choice <- AdventureRequest.parse(request.text())
      outline <- Try(ujson.read(request.text())("outline")).toEither
        .flatMap(AdventureGenerator.parseOutline)
        .map(AdventureGenerator.withSoundMap)
        .left
        .map(_ => "Request must have an \"outline\" that is an adventure outline")
      // The art director briefs each location's picture; without its briefs pictures are drawn from the descriptions
      directed = ArtDirector.direct(outline, tracing, config.reasoningEffort)(using llmClient).getOrElse(outline)
      adventure <- library.keep(directed, choice._1, choice._2).left.map { error =>
        logger.error(s"Could not keep an adventure: ${error.message}")
        error.userMessage
      }
    } yield adventure
    kept match {
      case Right(adventure) => ujson.Obj("status" -> "success", "id" -> adventure.id)
      case Left(message) =>
        logger.warn(s"Could not keep an adventure: $message")
        ujson.Obj("status" -> "error", "message" -> message)
    }
  }

  @delete("/api/adventures/:adventureId")
  def deleteAdventure(adventureId: String): ujson.Value =
    if (library.delete(adventureId)) ujson.Obj("status" -> "success")
    else ujson.Obj("status" -> "error", "message" -> s"No adventure '${adventureId.take(60)}'")

  /** The library's pictures and mood tracks, by the URLs it gives out ([[org.llm4s.szork.media.MediaRef.Stored]]). Each
    * URL carries its file's version (`v`, which only makes the URL new), so browsers may keep what they fetch.
    */
  @get(AdventureLibrary.UrlPrefix, subpath = true)
  def libraryMedia(request: Request, v: String = ""): Response[Array[Byte]] =
    library.resolve(request.remainingPathSegments) match {
      case Some((file, contentType)) =>
        Response(
          java.nio.file.Files.readAllBytes(file),
          headers = Seq("Content-Type" -> contentType, "Cache-Control" -> "public, max-age=31536000, immutable"))
      case None => Response(Array.emptyByteArray, statusCode = 404)
    }

  /** The server's own page, which points to the frontend: the game is played there, not here. */
  @get("/")
  def serveApp(): Response[String] =
    Response(SzorkRoutes.homePage(config), headers = Seq("Content-Type" -> "text/html; charset=utf-8"))

  @delete("/api/game/:gameId")
  def deleteGame(gameId: String): ujson.Value = {
    if (!IdGenerator.isValidGameId(gameId))
      return ujson.Obj("status" -> "error", "error" -> s"Invalid game id: ${gameId.take(40)}")
    logger.info(s"Deleting game: $gameId")

    // End any live session for the game first, so it cannot save the game again once its files are gone. The files
    // are deleted after the session's turn in progress, if it has one, has finished; that happens whether or not this
    // request is still waiting for it.
    val deletion = wsServer.endGame(gameId) {
      val deleted = StepPersistence.deleteGame(gameId)
      deleted.foreach { _ =>
        logger.info(s"Deleted game: $gameId")
        // Clearing the media cache is best effort: the game is gone either way
        MediaCache.clearGameCache(gameId).left.foreach { error =>
          logger.warn(s"Failed to clear cache for deleted game $gameId: $error")
        }
      }
      deleted
    }

    Try(Await.result(deletion, deleteWait)) match {
      case Success(Right(_)) =>
        ujson.Obj("status" -> "success", "message" -> s"Game deleted: $gameId")
      case Success(Left(error)) =>
        logger.error(s"Failed to delete game $gameId: ${error.message}")
        ujson.Obj("status" -> "error", "error" -> error.userMessage)
      case Failure(_: java.util.concurrent.TimeoutException) =>
        logger.info(s"Game $gameId will be deleted when its turn in progress ends")
        ujson.Obj("status" -> "pending", "message" -> SzorkRoutes.DeletePending)
      case Failure(e) =>
        logger.error(s"Failed to delete game $gameId", e)
        ujson.Obj(
          "status" -> "error",
          "error" -> PersistenceError(e.toString, Some(e), operation = "delete").userMessage)
    }
  }

  initialize()
}

object SzorkRoutes {

  /** The page at `/`: links to the frontends this server takes requests from (its allowed origins, other than its own
    * address), as there is no telling which of them is running.
    */
  private[api] def homePage(config: SzorkConfig): String = {
    val frontends = config.allowedOrigins.filterNot(_.endsWith(s":${config.port}")).toList.sorted
    val links = frontends.map(origin => s"""<li><a href="$origin">$origin</a></li>""").mkString("\n")
    val where =
      if (frontends.isEmpty) "<p>Play through the Szork frontend.</p>"
      else s"<p>Play through the Szork frontend at:</p>\n<ul>\n$links\n</ul>"
    s"""<!DOCTYPE html>
      |<html>
      |<head><title>Szork</title></head>
      |<body>
      |<h1>Szork Server</h1>
      |$where
      |<p>In development, <code>npm run dev</code> in <code>frontend/</code> serves it.</p>
      |</body>
      |</html>""".stripMargin
  }

  val ThemeTooLong: String = s"The theme can be at most ${SzorkConfig.MaxThemeChars} characters."

  /** How long deleting a game that is being played waits for its turn in progress before answering. */
  val DeleteWait: FiniteDuration = 2.minutes

  /** The answer to a delete still waiting for the game's turn in progress. */
  val DeletePending: String =
    "The game is finishing a turn; it will be deleted as soon as the turn ends. Its session has ended."
}
