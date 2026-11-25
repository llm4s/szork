package org.llm4s.szork.adventures

import org.llm4s.szork.api.{ArtStyle, GameTheme}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.game.{AdventureGenerator, AdventureOutline}
import org.llm4s.szork.media.{AtomicFiles, MediaRef, PictureFiles}
import org.slf4j.LoggerFactory
import ujson.{Null, Obj, Value}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}
import scala.jdk.CollectionConverters._
import scala.util.{Try, Using}

/** A ready-made adventure: an outline to play as it is, with the theme and art style it was designed for. */
final case class Adventure(
  id: String,
  outline: AdventureOutline,
  theme: Option[GameTheme],
  artStyle: Option[ArtStyle],
  createdAt: Long)

/** What the setup screen shows of an adventure; `images` counts the locations pictured in its art style. */
final case class AdventureSummary(
  id: String,
  title: String,
  tagline: Option[String],
  mainQuest: String,
  theme: Option[String],
  artStyle: Option[String],
  locations: Int,
  images: Int)

/** The adventures kept on disk to be played again, and the media made once for them.
  *
  * Layout under `root` (SZORK_ADVENTURES_DIR):
  *   - `<adventure-id>/adventure.json`: the outline, theme and art style
  *   - `<adventure-id>/images/<location>.<style>.jpg`: one picture per location and art style
  *   - `_music/<mood>.mp3`: one track per music mood, shared by every game on the server
  *
  * Unlike the [[org.llm4s.szork.media.MediaCache]], nothing here expires: an image or track is made once (by
  * [[BakeAdventure]], or the first time a game needs it) and every later game uses the file. The browser fetches these
  * files by URL ([[MediaRef.Stored]], served by [[resolve]]), so it caches them too.
  *
  * Adventure ids, location ids and moods reach the filesystem only as names this class has checked or made.
  */
final class AdventureLibrary(val root: Path) {
  import AdventureLibrary._

  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  // The media being made, by file: games that need a file already being made wait for that one result
  private val inFlight = new ConcurrentHashMap[String, CompletableFuture[SzorkResult[MediaRef.Stored]]]()

  private def adventureDir(id: String): Option[Path] =
    Option.when(isValidId(id))(root.resolve(id))

  // ============= Adventures =============

  /** Every adventure in the library, by title. */
  def list: List[AdventureSummary] =
    adventureIds.flatMap(load).map(summary).sortBy(_.title.toLowerCase)

  private def adventureIds: List[String] =
    if (!Files.isDirectory(root)) Nil
    else
      Using
        .resource(Files.list(root))(_.iterator().asScala.toList)
        .filter(dir => Files.isRegularFile(dir.resolve(AdventureFile)))
        .map(_.getFileName.toString)
        .filter(isValidId)

  private def summary(adventure: Adventure): AdventureSummary = {
    val style = styleOf(adventure.artStyle.map(_.id))
    AdventureSummary(
      id = adventure.id,
      title = adventure.outline.title,
      tagline = adventure.outline.tagline,
      mainQuest = adventure.outline.mainQuest,
      theme = adventure.theme.map(_.name),
      artStyle = adventure.artStyle.map(_.id),
      locations = adventure.outline.keyLocations.size,
      images = adventure.outline.keyLocations.count(l => imageFile(adventure.id, l.id, style).isDefined)
    )
  }

  /** The adventure `id`, or None if there is none (or its file cannot be read). */
  def load(id: String): Option[Adventure] =
    adventureDir(id).map(_.resolve(AdventureFile)).filter(Files.isRegularFile(_)).flatMap { file =>
      Try(fromJson(id, ujson.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)))).toEither match {
        case Right(adventure) => Some(adventure)
        case Left(e) =>
          logger.warn(s"Unreadable adventure '$id': ${e.getMessage}")
          None
      }
    }

  /** Adds `outline` to the library under an id made from its title. */
  def keep(
    outline: AdventureOutline,
    theme: Option[GameTheme],
    artStyle: Option[ArtStyle],
    now: Long = System.currentTimeMillis()): SzorkResult[Adventure] =
    synchronized {
      Try {
        val base = slug(outline.title)
        val id = Iterator
          .from(1)
          .map(n => if (n == 1) base else s"${base.take(MaxIdLength - 4)}-$n")
          .find(candidate => !Files.exists(root.resolve(candidate)))
          .get
        val adventure = Adventure(id, outline, theme, artStyle, now)
        write(adventure)
        logger.info(s"Kept adventure '${outline.title}' as $id")
        adventure
      }.toEither.left.map(e => PersistenceError(s"Could not keep the adventure: ${e.getMessage}", operation = "save"))
    }

  private def write(adventure: Adventure): Unit = {
    Files.createDirectories(root.resolve(adventure.id))
    AtomicFiles.write(
      root.resolve(adventure.id).resolve(AdventureFile),
      ujson.write(toJson(adventure), indent = 2).getBytes(StandardCharsets.UTF_8))
  }

  /** Replaces the outline of the adventure `id` (to add picture briefs to it, say). */
  def update(id: String, outline: AdventureOutline): SzorkResult[Adventure] =
    synchronized {
      load(id).toRight(NotFoundError(s"No adventure '${id.take(40)}'")).flatMap { adventure =>
        val updated = adventure.copy(outline = outline)
        Try(write(updated)).toEither
          .map(_ => updated)
          .left
          .map(e => PersistenceError(s"Could not update the adventure: ${e.getMessage}", operation = "save"))
      }
    }

  /** Removes the adventure `id` and its images; false if there was none. It takes the lock [[keep]], [[update]] and the
    * keeping of a picture take, so none of them writes into a directory being deleted.
    */
  def delete(id: String): Boolean =
    synchronized {
      adventureDir(id).filter(Files.isDirectory(_)).exists { dir =>
        deleteRecursively(dir)
        logger.info(s"Deleted adventure $id")
        true
      }
    }

  // ============= Images =============

  private def imagePath(adventureId: String, locationId: String, style: String): Option[Path] =
    adventureDir(adventureId).map(_.resolve(ImagesDir).resolve(imageName(locationId, style)))

  private def imageFile(adventureId: String, locationId: String, style: String): Option[Path] =
    imagePath(adventureId, locationId, style).filter(Files.isRegularFile(_))

  /** The picture of `locationId` in `style` (an art style id), if the library has one. */
  def image(adventureId: String, locationId: String, style: String): Option[MediaRef.Stored] =
    imageFile(adventureId, locationId, style).map(file => stored(s"adventures/$adventureId/$ImagesDir", file))

  /** The picture of `locationId` in `style`; a missing one is made with `generate` (a Base64 image) and kept. The
    * adventure must exist: a picture is never kept for one that is not in the library (or was deleted meanwhile).
    */
  def imageOr(adventureId: String, locationId: String, style: String)(
    generate: => SzorkResult[String]): SzorkResult[MediaRef.Stored] =
    (adventureDir(adventureId), imagePath(adventureId, locationId, style)) match {
      case (Some(dir), Some(path)) if Files.isRegularFile(dir.resolve(AdventureFile)) =>
        storedOr(path, s"adventures/$adventureId/$ImagesDir", Some(dir.resolve(AdventureFile)))(generate)
      case _ => Left(NotFoundError(s"No adventure '${adventureId.take(40)}'"))
    }

  // ============= Music =============

  private def musicPath(mood: String): Path = root.resolve(MusicDir).resolve(s"${fileSafe(mood)}.mp3")

  /** The track for `mood`, if the library has one. */
  def music(mood: String): Option[MediaRef.Stored] =
    Some(musicPath(mood)).filter(Files.isRegularFile(_)).map(stored("music", _))

  /** The track for `mood`; a missing one is made with `generate` (Base64 MP3) and kept. */
  def musicOr(mood: String)(generate: => SzorkResult[String]): SzorkResult[MediaRef.Stored] =
    storedOr(musicPath(mood), "music", None)(generate)

  /** Whether any adventure has a picture: such a server shows images even with no image provider. */
  def hasImages: Boolean = adventureIds.exists { id =>
    val dir = root.resolve(id).resolve(ImagesDir)
    Files.isDirectory(dir) && Using.resource(Files.list(dir))(_.iterator().asScala.exists(isMedia))
  }

  /** Whether any mood has a track: such a server plays music even with no music provider. */
  def hasMusic: Boolean = {
    val dir = root.resolve(MusicDir)
    Files.isDirectory(dir) && Using.resource(Files.list(dir))(_.iterator().asScala.exists(isMedia))
  }

  // ============= Serving =============

  /** The media file a [[MediaRef.Stored]] URL names, given the URL's path below [[UrlPrefix]], and its content type.
    * Only `adventures/<id>/images/<file>` and `music/<file>` name files, each part checked before it is resolved.
    */
  def resolve(segments: Seq[String]): Option[(Path, String)] = {
    val file = segments match {
      case Seq("adventures", id, ImagesDir, name) if isMediaName(name) =>
        adventureDir(id).map(_.resolve(ImagesDir).resolve(name))
      case Seq("music", name) if isMediaName(name) => Some(root.resolve(MusicDir).resolve(name))
      case _ => None
    }
    file.filter(Files.isRegularFile(_)).map { f =>
      // A picture's type is read from the file itself: it is whatever the image provider answered with
      f -> (if (f.toString.endsWith(".mp3")) "audio/mpeg" else PictureFiles.mediaType(firstBytes(f)))
    }
  }

  private def stored(urlDir: String, file: Path): MediaRef.Stored =
    // The file's time in the URL lets the browser cache it for good and still see a re-baked one
    MediaRef.Stored(s"$UrlPrefix/$urlDir/${file.getFileName}?v=${Files.getLastModifiedTime(file).toMillis}")

  /** The file at `path` as a [[MediaRef.Stored]], made with `generate` if it is missing. Games needing the same missing
    * file share one `generate` (and its result, a failure too): the first to ask makes it, the rest wait. The file is
    * only kept while `owner` (the adventure's file, if the file belongs to an adventure) still exists.
    */
  private def storedOr(path: Path, urlDir: String, owner: Option[Path])(
    generate: => SzorkResult[String]): SzorkResult[MediaRef.Stored] = {
    val key = path.toString
    if (Files.isRegularFile(path)) Right(stored(urlDir, path))
    else {
      val mine = new CompletableFuture[SzorkResult[MediaRef.Stored]]()
      inFlight.putIfAbsent(key, mine) match {
        case null =>
          var result: SzorkResult[MediaRef.Stored] = Left(CacheError(s"Could not keep ${path.getFileName}", None))
          try {
            result =
              if (Files.isRegularFile(path)) Right(stored(urlDir, path))
              else generate.flatMap(writeMedia(path, urlDir, owner, _))
            result
          } catch {
            case e: Exception =>
              result = Left(CacheError(s"Could not keep ${path.getFileName}: ${e.getMessage}", Some(e)))
              throw e
          } finally {
            // Taken out before the result is given, so a game that asks after sees the file, or makes it again
            inFlight.remove(key)
            mine.complete(result)
          }
        case making => making.get()
      }
    }
  }

  private def writeMedia(
    path: Path,
    urlDir: String,
    owner: Option[Path],
    base64: String): SzorkResult[MediaRef.Stored] =
    synchronized {
      if (!owner.forall(Files.isRegularFile(_))) Left(NotFoundError(s"${path.getFileName}: the adventure is gone"))
      else
        Try {
          Files.createDirectories(path.getParent)
          AtomicFiles.write(path, Base64.getDecoder.decode(base64))
          logger.info(s"Added ${root.relativize(path)} to the adventure library")
          stored(urlDir, path)
        }.toEither.left.map(e => CacheError(s"Could not keep ${path.getFileName}: ${e.getMessage}", Some(e)))
    }

  private def firstBytes(file: Path): Array[Byte] =
    Using.resource(Files.newInputStream(file))(_.readNBytes(4))

  private def isMedia(file: Path): Boolean = isMediaName(file.getFileName.toString)

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Using.resource(Files.list(path))(_.forEach(deleteRecursively))
    Files.deleteIfExists(path)
  }
}

object AdventureLibrary {

  /** The library at `config.adventuresDirectory`. */
  def at(config: org.llm4s.szork.api.SzorkConfig): AdventureLibrary =
    new AdventureLibrary(java.nio.file.Paths.get(config.adventuresDirectory))

  /** Where the library's media is served from (`SzorkRoutes.libraryMedia`). */
  val UrlPrefix = "/api/library"

  /** The art style id a game with no art style is pictured in. */
  val DefaultStyle = "fantasy"

  private val AdventureFile = "adventure.json"
  private val ImagesDir = "images"
  // Not a valid adventure id, so no adventure can take its place
  private val MusicDir = "_music"
  private val MaxIdLength = 60
  private val Id = "[a-z0-9][a-z0-9-]*".r
  private val MediaName = """[a-z0-9_-]+(\.[a-z0-9_-]+)?\.(jpg|mp3)""".r

  def isValidId(id: String): Boolean = id.length <= MaxIdLength && Id.matches(id)

  private def isMediaName(name: String): Boolean = name.length <= 200 && MediaName.matches(name)

  /** The art style id pictures are kept under for a game in `artStyle`. */
  def styleOf(artStyle: Option[String]): String = artStyle.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultStyle)

  /** An adventure id from a title: lower-case words joined by hyphens. */
  def slug(title: String): String = {
    val words = title.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "")
    val cut = words.take(MaxIdLength).replaceAll("-+$", "")
    if (cut.isEmpty) "adventure" else cut
  }

  /** `name` as part of a file name: lower-case letters, digits, underscores and hyphens (so `old-mill` and `old_mill`
    * stay two files).
    */
  private def fileSafe(name: String): String = {
    val safe = name.trim.toLowerCase.replaceAll("[^a-z0-9_-]+", "_").take(80)
    if (safe.isEmpty) "_" else safe
  }

  private def imageName(locationId: String, style: String): String = s"${fileSafe(locationId)}.${fileSafe(style)}.jpg"

  private[adventures] def toJson(adventure: Adventure): Value =
    Obj(
      "theme" -> adventure.theme.map(t => Obj("id" -> t.id, "name" -> t.name, "prompt" -> t.prompt)).getOrElse(Null),
      "artStyle" -> adventure.artStyle.map(a => Obj("id" -> a.id, "name" -> a.name)).getOrElse(Null),
      "createdAt" -> adventure.createdAt.toDouble,
      "outline" -> AdventureGenerator.outlineToJson(adventure.outline)
    )

  private[adventures] def fromJson(id: String, json: Value): Adventure = {
    def field(name: String): Option[Value] = json.obj.get(name).filter(_ != Null)
    Adventure(
      id = id,
      // Read as a game reads an outline it is sent: with the map's loose ends repaired
      outline = AdventureGenerator.withSoundMap(AdventureGenerator.outlineFromJson(json("outline"))),
      theme = field("theme").map(t => GameTheme(t("id").str, t("name").str, t("prompt").str)),
      artStyle = field("artStyle").map(a => ArtStyle(a("id").str, a("name").str)),
      createdAt = field("createdAt").map(_.num.toLong).getOrElse(0L)
    )
  }

  def summaryJson(s: AdventureSummary): Value =
    Obj(
      "id" -> s.id,
      "title" -> s.title,
      "tagline" -> s.tagline.map(ujson.Str(_)).getOrElse(Null),
      "mainQuest" -> s.mainQuest,
      "theme" -> s.theme.map(ujson.Str(_)).getOrElse(Null),
      "artStyle" -> s.artStyle.map(ujson.Str(_)).getOrElse(Null),
      "locations" -> s.locations,
      "images" -> s.images
    )
}
