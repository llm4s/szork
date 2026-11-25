package org.llm4s.szork.adventures

import org.llm4s.szork.adapters.DefaultMusicClient
import org.llm4s.szork.api.{ArtStyle, GameTheme, SzorkConfig}
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.game.{AdventureGenerator, DungeonMasterOutput, PromptBuilder, SceneMedia}
import org.llm4s.szork.media.{MediaPlanner, SceneImages}
import org.llm4s.szork.spi.{ImageClient, MusicClient}

import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/** Makes an adventure's media ahead of play: a picture of every location, and the music track for every mood.
  *
  * A game makes whatever the library lacks the first time it needs it, so baking is never required; it means the first
  * player waits for nothing, and that a server with no image or music provider can still show and play what was baked
  * elsewhere (the library is plain files: commit an adventure's directory to ship it).
  *
  * {{{
  * sbt "runMain org.llm4s.szork.adventures.BakeAdventure --list"
  * sbt "runMain org.llm4s.szork.adventures.BakeAdventure the-sunken-archive"       # its pictures, and the mood tracks
  * sbt "runMain org.llm4s.szork.adventures.BakeAdventure --all --style pixel"      # every adventure, in another style
  * sbt 'runMain org.llm4s.szork.adventures.BakeAdventure --new --theme "a haunted lighthouse" --style painting'
  * sbt "runMain org.llm4s.szork.adventures.BakeAdventure --music"                  # only the mood tracks
  * }}}
  *
  * What already exists is kept: delete a file to have it made again.
  */
object BakeAdventure {

  /** What a bake made, already had, and could not make (with why). */
  final case class Report(made: List[String], kept: List[String], failed: List[(String, String)]) {
    def ++(other: Report): Report = Report(made ++ other.made, kept ++ other.kept, failed ++ other.failed)
    def summary: String =
      s"${made.size} made, ${kept.size} already there, ${failed.size} failed" +
        failed.map { case (what, why) => s"\n  FAILED $what: $why" }.mkString
  }
  object Report { val empty: Report = Report(Nil, Nil, Nil) }

  /** Bakes the picture of every location of `adventure` in `artStyle` (None: the adventure's own), `parallelism` at a
    * time.
    */
  def images(
    library: AdventureLibrary,
    adventure: Adventure,
    artStyle: Option[String],
    client: ImageClient,
    parallelism: Int = 4): Report = {
    val styleId = artStyle.orElse(adventure.artStyle.map(_.id))
    val style = AdventureLibrary.styleOf(styleId)
    inParallel(adventure.outline.keyLocations, parallelism) { place =>
      val what = s"${adventure.id}/${place.id} ($style)"
      if (library.image(adventure.id, place.id, style).isDefined) Report(Nil, List(what), Nil)
      else {
        val prompt = MediaPlanner.styledImagePrompt(
          styleId,
          SceneMedia.outlineImage(place),
          PromptBuilder.artStyleDescription(styleId))
        outcome(what, library.imageOr(adventure.id, place.id, style)(client.generateScene(prompt, style, None, None)))
      }
    }
  }

  /** Bakes the track for every music mood, `parallelism` at a time. */
  def music(library: AdventureLibrary, client: MusicClient, parallelism: Int = 2): Report =
    inParallel(DungeonMasterOutput.MusicMoods.toList, parallelism) { mood =>
      val what = s"music/$mood"
      if (library.music(mood).isDefined) Report(Nil, List(what), Nil)
      else outcome(what, library.musicOr(mood)(client.generate(mood, "", None, None)))
    }

  private def outcome(what: String, result: SzorkResult[?]): Report = result match {
    case Right(_) =>
      println(s"  made   $what")
      Report(List(what), Nil, Nil)
    case Left(error) =>
      println(s"  FAILED $what: ${error.message}")
      Report(Nil, Nil, List(what -> error.message))
  }

  private def inParallel[A](items: List[A], parallelism: Int)(bake: A => Report): Report = {
    val pool = Executors.newFixedThreadPool(parallelism.max(1))
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try Await.result(Future.traverse(items)(item => Future(bake(item))), Duration.Inf).foldLeft(Report.empty)(_ ++ _)
    finally pool.shutdown()
  }

  private final case class Options(
    ids: List[String] = Nil,
    all: Boolean = false,
    list: Boolean = false,
    newTheme: Option[String] = None,
    themeName: Option[String] = None,
    designNew: Boolean = false,
    style: Option[String] = None,
    images: Boolean = true,
    music: Boolean = true)

  private val Usage =
    """Usage: BakeAdventure [<adventure-id>... | --all | --new --theme <theme> [--theme-name <name>] | --list | --music]
      |                     [--style <art-style-id>] [--no-images] [--no-music]""".stripMargin

  private def parse(args: List[String], options: Options = Options()): Either[String, Options] = args match {
    case Nil => Right(options)
    case "--all" :: rest => parse(rest, options.copy(all = true))
    case "--list" :: rest => parse(rest, options.copy(list = true))
    case "--new" :: rest => parse(rest, options.copy(designNew = true))
    case "--music" :: rest => parse(rest, options.copy(images = false))
    case "--no-images" :: rest => parse(rest, options.copy(images = false))
    case "--no-music" :: rest => parse(rest, options.copy(music = false))
    case "--theme" :: theme :: rest => parse(rest, options.copy(newTheme = Some(theme)))
    case "--theme-name" :: name :: rest => parse(rest, options.copy(themeName = Some(name)))
    case "--style" :: style :: rest => parse(rest, options.copy(style = Some(style)))
    case flag :: _ if flag.startsWith("--") => Left(s"Unknown or incomplete option: $flag")
    case id :: rest => parse(rest, options.copy(ids = options.ids :+ id))
  }

  def main(args: Array[String]): Unit = {
    val options = parse(args.toList) match {
      case Right(o) => o
      case Left(problem) =>
        println(s"$problem\n$Usage")
        sys.exit(2)
    }
    val config = SzorkConfig.instance
    val library = AdventureLibrary.at(config)

    if (options.list) {
      println(s"Adventures in ${library.root.toAbsolutePath}:")
      library.list.foreach { a =>
        println(f"  ${a.id}%-40s ${a.title} (${a.images}/${a.locations} pictured, ${a.artStyle.getOrElse("no style")})")
      }
      return
    }

    val designed = Option.when(options.designNew)(design(config, library, options)).flatten
    val adventures =
      designed.toList ++ (if (options.all) library.list.map(_.id) else options.ids).flatMap { id =>
        val found = library.load(id)
        if (found.isEmpty) println(s"No adventure '$id' in ${library.root}")
        found
      }

    val pictures =
      if (!options.images || adventures.isEmpty) Report.empty
      else
        SceneImages.fromConfig(config, None) match {
          case None =>
            println("No pictures baked: no image provider is configured (SZORK_IMAGE_PROVIDER and its key)")
            Report.empty
          case Some(client) =>
            adventures
              .map { adventure =>
                println(s"Pictures for '${adventure.outline.title}':")
                images(library, adventure, options.style, client)
              }
              .foldLeft(Report.empty)(_ ++ _)
        }

    val tracks =
      if (!options.music) Report.empty
      else {
        val client = new DefaultMusicClient(config.mediaNetwork, config.musicPoll)
        if (client.isAvailable) {
          println("Mood tracks:")
          music(library, client)
        } else {
          println("No music baked: REPLICATE_API_KEY is not set")
          Report.empty
        }
      }

    val report = pictures ++ tracks
    println(s"\nBaked: ${report.summary}")
    sys.exit(if (report.failed.isEmpty) 0 else 1)
  }

  /** Designs an adventure for the options' theme with the configured model, and keeps it. */
  private def design(config: SzorkConfig, library: AdventureLibrary, options: Options): Option[Adventure] = {
    val theme = options.newTheme.getOrElse("classic fantasy adventure")
    println(s"Designing an adventure: $theme ...")
    val kept = for {
      client <- SzorkConfig.getLLMClient(config)
      outline <- AdventureGenerator
        .generateAdventureOutline(theme, None, config.reasoningEffort)(using client)
        .left
        .map(_.message)
      adventure <- library
        .keep(
          outline,
          Some(GameTheme("custom", options.themeName.getOrElse(theme.take(40)), theme)),
          options.style.map(s => ArtStyle(s, s.capitalize)))
        .left
        .map(_.message)
    } yield adventure
    kept match {
      case Right(adventure) =>
        println(s"Kept '${adventure.outline.title}' as ${adventure.id}")
        Some(adventure)
      case Left(problem) =>
        println(s"Could not design the adventure: $problem")
        None
    }
  }
}
