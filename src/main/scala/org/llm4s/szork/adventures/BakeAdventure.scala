package org.llm4s.szork.adventures

import org.llm4s.szork.adapters.DefaultMusicClient
import org.llm4s.szork.api.{ArtStyle, GameTheme, SzorkConfig}
import org.llm4s.szork.error.ErrorHandling.*
import org.llm4s.szork.game.{AdventureGenerator, ArtDirector, DungeonMasterOutput, SceneMedia}
import org.llm4s.szork.media.{MediaPlanner, SceneImages}
import org.llm4s.szork.spi.{ImageClient, MusicClient}

import java.util.concurrent.Executors
import scala.annotation.tailrec
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
  * sbt "runMain org.llm4s.szork.adventures.BakeAdventure the-drowned-archive"     # its pictures, and the mood tracks
  * sbt "runMain org.llm4s.szork.adventures.BakeAdventure --all --style pixel"      # every adventure, in another style
  * sbt 'runMain org.llm4s.szork.adventures.BakeAdventure --new --theme "a haunted lighthouse" --style painting'
  * sbt "runMain org.llm4s.szork.adventures.BakeAdventure --music"                  # only the mood tracks
  * }}}
  *
  * Before an adventure's pictures are made, the [[org.llm4s.szork.game.ArtDirector]] writes a brief for each location
  * that has none (`--art` has it write them all again), and the briefs are saved in the adventure.
  *
  * What already exists is kept: delete a file to have it made again. With nothing chosen to bake it prints the usage;
  * the exit code is non-zero if anything asked for could not be made (a design that failed, an adventure that is not in
  * the library, a picture or track that failed).
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
        val prompt = SceneMedia.outlinePrompt(place, styleId)
        outcome(what, library.imageOr(adventure.id, place.id, style)(client.generateScene(prompt, style, None, None)))
      }
    }
  }

  /** Bakes the track for every music mood, `parallelism` at a time. */
  def music(library: AdventureLibrary, client: MusicClient, parallelism: Int = 2): Report =
    inParallel(DungeonMasterOutput.MusicMoods.toList, parallelism) { mood =>
      val what = s"music/$mood"
      if (library.music(mood).isDefined) Report(Nil, List(what), Nil)
      else outcome(what, library.musicOr(mood)(client.generate(mood)))
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

  /** How many polls' worth of time a bake waits for a track (SZORK_MUSIC_POLL_INTERVAL_MS apart). */
  private val BakePollAttempts = 180

  /** What the command line asks for. */
  private[szork] final case class Options(
    ids: List[String] = Nil,
    all: Boolean = false,
    list: Boolean = false,
    newTheme: Option[String] = None,
    themeName: Option[String] = None,
    designNew: Boolean = false,
    style: Option[String] = None,
    images: Boolean = true,
    art: Boolean = false,
    music: Boolean = true,
    musicOnly: Boolean = false) {

    /** Whether the command line chose something to do: an adventure to bake, a new one, the tracks, or the list. */
    def selectsSomething: Boolean = ids.nonEmpty || all || designNew || list || musicOnly
  }

  private[szork] val Usage =
    """Usage: BakeAdventure <adventure-id>... | --all | --new [--theme <theme>] [--theme-name <name>] | --music | --list
      |                     [--style <art-style-id>] [--art] [--no-images] [--no-music]
      |
      |  <adventure-id>...   bake these adventures' pictures (and the mood tracks)
      |  --all               bake every adventure in the library
      |  --new               design a new adventure with the configured model, keep it and bake it
      |  --theme <theme>     the new adventure's theme (with --new); --theme-name <name> names it
      |  --music             bake only the mood tracks
      |  --list              list the library
      |  --style <id>        the art style of the pictures: pixel, illustration, painting, comic or fantasy
      |  --art               have the art director write the picture briefs again
      |  --no-images         bake no pictures
      |  --no-music          bake no mood tracks""".stripMargin

  /** The options `args` ask for, or what is wrong with them (the usage follows). */
  private[szork] def parse(args: List[String]): Either[String, Options] = {
    @tailrec def go(args: List[String], options: Options): Either[String, Options] = args match {
      case Nil => Right(options)
      case "--all" :: rest => go(rest, options.copy(all = true))
      case "--list" :: rest => go(rest, options.copy(list = true))
      case "--new" :: rest => go(rest, options.copy(designNew = true))
      case "--music" :: rest => go(rest, options.copy(images = false, musicOnly = true))
      case "--no-images" :: rest => go(rest, options.copy(images = false))
      case "--art" :: rest => go(rest, options.copy(art = true))
      case "--no-music" :: rest => go(rest, options.copy(music = false))
      case "--theme" :: theme :: rest => go(rest, options.copy(newTheme = Some(theme)))
      case "--theme-name" :: name :: rest => go(rest, options.copy(themeName = Some(name)))
      case "--style" :: style :: rest =>
        MediaPlanner.normalizeStyle(style) match {
          case Some(known) => go(rest, options.copy(style = Some(known)))
          case None =>
            Left(s"Unknown art style '$style' (the styles are ${MediaPlanner.StyleIds.mkString(", ")}, fantasy)")
        }
      case flag :: _ if flag.startsWith("--") => Left(s"Unknown or incomplete option: $flag")
      case id :: rest => go(rest, options.copy(ids = options.ids :+ id))
    }
    go(args, Options()).flatMap { options =>
      if (!options.selectsSomething) Left("Nothing to bake: name an adventure, or give --all, --new, --music or --list")
      else if (!options.designNew && (options.newTheme.isDefined || options.themeName.isDefined))
        Left("--theme and --theme-name only go with --new")
      else Right(options)
    }
  }

  /** The adventures of `library` that `ids` name, and a failure for each that is not there. */
  private[szork] def lookup(library: AdventureLibrary, ids: List[String]): (List[Adventure], Report) = {
    val found = ids.map(id => id -> library.load(id))
    val missing = found.collect { case (id, None) => id -> s"no adventure '$id' in ${library.root}" }
    found.flatMap(_._2) -> Report(Nil, Nil, missing)
  }

  /** 0 when the bake made or found everything asked of it, otherwise 1. */
  private[szork] def exitCode(report: Report): Int = if (report.failed.isEmpty) 0 else 1

  def main(args: Array[String]): Unit = {
    val options = parse(args.toList) match {
      case Right(o) => o
      case Left(problem) =>
        println(s"$problem\n\n$Usage")
        sys.exit(2)
    }
    val config = SzorkConfig.instance
    val library = AdventureLibrary.at(config)

    if (options.list) {
      println(s"Adventures in ${library.root.toAbsolutePath}:")
      library.list.foreach { a =>
        println(f"  ${a.id}%-40s ${a.title} (${a.images}/${a.locations} pictured, ${a.artStyle.getOrElse("no style")})")
      }
      sys.exit(0)
    }

    val designed = Option.when(options.designNew)(design(config, library, options))
    val (named, missing) = lookup(library, if (options.all) library.list.map(_.id) else options.ids)
    val adventures = designed.flatMap(_.toOption).toList ++ named
    val problems = missing ++ Report(Nil, Nil, designed.flatMap(_.left.toOption).map("design" -> _).toList)

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
                val directed = artDirected(config, library, adventure, again = options.art)
                println(s"Pictures for '${directed.outline.title}':")
                images(library, directed, options.style, client)
              }
              .foldLeft(Report.empty)(_ ++ _)
        }

    val tracks =
      if (!options.music) Report.empty
      else {
        val client = // A bake can wait: a game gives up on a track after about half a minute, a bake after three
          new DefaultMusicClient(
            config.mediaNetwork,
            config.musicPoll.copy(maxAttempts = config.musicPoll.maxAttempts.max(BakePollAttempts)))
        if (client.isAvailable) {
          println("Mood tracks:")
          music(library, client)
        } else {
          println("No music baked: REPLICATE_API_KEY is not set")
          Report.empty
        }
      }

    val report = problems ++ pictures ++ tracks
    println(s"\nBaked: ${report.summary}")
    sys.exit(exitCode(report))
  }

  /** `adventure` with a picture brief for every location: as it is if it has them (and `again` is not asked for),
    * otherwise briefed by the [[ArtDirector]] on the configured model and saved. If that cannot be done the adventure
    * is baked as it is, its pictures drawn from the locations' descriptions.
    */
  private def artDirected(
    config: SzorkConfig,
    library: AdventureLibrary,
    adventure: Adventure,
    again: Boolean): Adventure =
    if (ArtDirector.isDirected(adventure.outline) && !again) adventure
    else {
      println(s"Briefing the pictures for '${adventure.outline.title}' ...")
      val directed = for {
        client <- SzorkConfig.getLLMClient(config)
        outline <- ArtDirector.direct(adventure.outline, None, config.reasoningEffort)(using client).left.map(_.message)
        saved <- library.update(adventure.id, outline).left.map(_.message)
      } yield saved
      directed match {
        case Right(saved) => saved
        case Left(problem) =>
          println(s"  no briefs ($problem): pictures are drawn from the locations' descriptions")
          adventure
      }
    }

  /** Designs an adventure for the options' theme with the configured model, and keeps it (or says why it could not). */
  private def design(config: SzorkConfig, library: AdventureLibrary, options: Options): Either[String, Adventure] = {
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
    kept.left.map(problem => s"could not design the adventure: $problem").map { adventure =>
      println(s"Kept '${adventure.outline.title}' as ${adventure.id}")
      adventure
    }
  }
}
