package org.llm4s.szork.playtest

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{CompletionOptions, Conversation, ReasoningEffort, SystemMessage, UserMessage}
import org.llm4s.szork.api.{AgentTracing, EngineFactory, SessionFeatures, SzorkConfig}
import org.llm4s.szork.adventures.{Adventure, AdventureLibrary}
import org.llm4s.szork.error.ErrorHandling.SzorkResult
import org.llm4s.szork.error.{ConfigurationError, NotFoundError}
import org.llm4s.szork.game.{AdventureGenerator, AdventureOutline, ExitState, GameEngine, GameScene, IdGenerator}
import org.llm4s.trace.Tracing

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/** Chooses the player's next command in a playtest. */
trait Player {

  /** The player's next command, or why the player could not choose one (the playtest then looks around, and reports
    * it).
    */
  def next(
    narration: String,
    scene: Option[GameScene],
    inventory: List[String],
    played: Seq[PlayedTurn]): Either[String, String]
}

object Player {

  /** The most tokens the model player may answer with: one short command, and room for a model that reasons first
    * (llm4s's Anthropic default leaves a Claude 5 model too little).
    */
  val MaxOutputTokens: Int = 4000

  /** A player that types `commands` in order, then looks around. */
  def scripted(commands: String*): Player = new Player {
    private val remaining = commands.iterator
    def next(
      narration: String,
      scene: Option[GameScene],
      inventory: List[String],
      played: Seq[PlayedTurn]): Either[String, String] =
      Right(if (remaining.hasNext) remaining.next() else "look")
  }

  /** A player played by a model: it reads the latest narration, the exits and what it carries, and types one terse
    * command, reasoning with effort `reasoning` on a model that reasons. A call that fails, or answers with no command,
    * is reported as the player's failure.
    */
  def model(llm: LLMClient, reasoning: Option[ReasoningEffort] = None): Player = new Player {
    private val options = CompletionOptions().withMaxTokens(MaxOutputTokens).withReasoning(reasoning)

    def next(
      narration: String,
      scene: Option[GameScene],
      inventory: List[String],
      played: Seq[PlayedTurn]): Either[String, String] = {
      val recent = played.takeRight(8).map(t => s"> ${t.command}\n${t.narration}").mkString("\n\n")
      val exits = scene.toList.flatMap(_.exits).map(_.direction).mkString(", ")
      val prompt =
        s"""You are playing a text adventure and want to finish it: explore, talk to the characters you meet, pick up
          |what looks useful, and work out the puzzles. Do not repeat a command that has just failed; try something
          |else. Type ONE command, short and in lower case ("go north", "examine desk", "take key", "ask mara about
          |the lamp", "use key on gate").
          |
          |Recent turns:
          |$recent
          |
          |Now:
          |$narration
          |
          |Exits: ${if (exits.isEmpty) "none listed" else exits}
          |You carry: ${if (inventory.isEmpty) "nothing" else inventory.mkString(", ")}
          |
          |Your command:""".stripMargin
      val conversation =
        Conversation(
          Seq(SystemMessage("You answer with the player's next command and nothing else."), UserMessage(prompt)))
      llm.complete(conversation, options) match {
        case Left(error) => Left(s"the player model failed: ${error.message}")
        case Right(completion) =>
          completion.content.linesIterator
            .map(_.trim.stripPrefix(">").trim)
            .find(_.nonEmpty)
            .map(_.take(80).toLowerCase)
            .toRight("the player model answered with no command")
      }
    }
  }
}

/** What a playtest is asked to do.
  *
  * `turns` is the most the player takes (the playtest ends sooner if the adventure does); `probeExits` walks at the
  * start location's locked and sealed exits in games of their own, to see that they hold; `artStyle` is the style the
  * Dungeon Master is told pictures are drawn in (None: the default); `adventureId` is the library adventure played, if
  * it is one (see [[Playtest.playAdventure]]).
  */
final case class PlaytestConfig(
  theme: String,
  turns: Int = 25,
  probeExits: Boolean = true,
  artStyle: Option[String] = None,
  adventureId: Option[String] = None)

/** What a playtest did and found. */
final case class PlaytestReport(
  model: String,
  theme: String,
  outline: AdventureOutline,
  turns: Vector[PlayedTurn],
  findings: List[Finding],
  ending: Option[String]
) {

  /** Whether the game kept its rules: no hard finding. */
  def passed: Boolean = !findings.exists(_.hard)

  def toJson: ujson.Value = ujson.Obj(
    "model" -> model,
    "theme" -> theme,
    "title" -> outline.title,
    "passed" -> passed,
    "ending" -> ending.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
    "locationsVisited" -> turns.flatMap(_.after.currentLocation).distinct,
    "locationsPlanned" -> outline.keyLocations.size,
    "findings" -> findings.map { f =>
      ujson.Obj(
        "check" -> f.check,
        "hard" -> f.hard,
        "turn" -> f.turn.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
        "detail" -> f.detail)
    },
    "turns" -> turns.map { t =>
      ujson.Obj(
        "number" -> t.number,
        "command" -> t.command,
        "narration" -> t.narration,
        "location" -> t.after.currentLocation.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
        "inventory" -> t.inventory,
        "modelCalls" -> t.usage.fold[ujson.Value](ujson.Null)(u => ujson.Num(u.modelCalls.toDouble)),
        "outputTokens" -> t.usage.fold[ujson.Value](ujson.Null)(u => ujson.Num(u.outputTokens.toDouble)),
        "durationMs" -> t.usage.fold[ujson.Value](ujson.Null)(u => ujson.Num(u.durationMs.toDouble)),
        "error" -> t.error.fold[ujson.Value](ujson.Null)(ujson.Str(_))
      )
    }
  )

  /** The report for a person: the verdict, the numbers and each finding. */
  def summary: String = {
    val visited = turns.flatMap(_.after.currentLocation).distinct.size
    val usages = turns.flatMap(_.usage)
    def perTurn(f: GameEngine.TurnUsage => Long) =
      if (usages.isEmpty) "-" else f"${usages.map(f).sum.toDouble / usages.size}%.1f"
    val lines = List(
      s"${if (passed) "PASSED" else "FAILED"}: \"${outline.title}\" on $model",
      s"  ${turns.size} turns, $visited of ${outline.keyLocations.size} planned locations visited, " +
        s"ending: ${ending.getOrElse("not reached")}",
      s"  per turn: ${perTurn(_.modelCalls)} model calls, ${perTurn(_.outputTokens)} output tokens, " +
        s"${perTurn(_.durationMs)}ms"
    ) ++ findings.map { f =>
      s"  ${if (f.hard) "HARD" else "soft"} ${f.check}${f.turn.fold("")(n => s" (turn $n)")}: ${f.detail}"
    }
    lines.mkString("\n")
  }
}

/** Designs an adventure, plays it with a [[Player]] and holds every turn to [[PlaytestChecks]].
  *
  * It is what `sbt test` cannot be: the designer, the Dungeon Master, the tools, the world state and the exit guard
  * together, against whichever model is configured. With scripted clients it runs in the test suite too. Its games are
  * made as the server makes them ([[EngineFactory.default]]), from the same settings: the Dungeon Master's context
  * window, input guardrail, subagents and reasoning effort - text only.
  *
  * {{{
  * sbt "runMain org.llm4s.szork.playtest.Playtest --theme 'a haunted lighthouse' --turns 30 --report playtest.json"
  * }}}
  *
  * The exit status is 0 when the game kept its rules, 1 when a hard finding was made, and 2 when the playtest could not
  * run (no model configured, or the adventure could not be designed or started) or its report could not be written.
  */
object Playtest {

  /** The most locked exits probed: each is a game of its own (an opening turn and one command). */
  val MaxProbes: Int = 2

  /** What a playtest's games have: text only, and nothing saved. */
  private val TextOnly = SessionFeatures(tts = false, stt = false, image = false, music = false, autoSave = false)

  /** Designs the adventure for `config.theme` and plays it, with the designer and the games set up by `settings`. */
  def run(
    config: PlaytestConfig,
    llm: LLMClient,
    player: Player,
    model: String = "scripted",
    tracing: Option[Tracing] = None,
    log: String => Unit = _ => (),
    settings: SzorkConfig = SzorkConfig()
  ): SzorkResult[PlaytestReport] =
    AdventureGenerator
      .generateAdventureOutline(config.theme, tracing, settings.reasoningEffort)(using llm)
      .flatMap(outline => play(config, outline, llm, player, model, tracing, log, settings))

  /** Plays the library's `adventure` as a game of it is played: under its own theme and art style, as that adventure
    * (`config`'s theme and art style are replaced by the adventure's; an adventure with no theme is played under its
    * title).
    */
  def playAdventure(
    adventure: Adventure,
    config: PlaytestConfig,
    llm: LLMClient,
    player: Player,
    model: String = "scripted",
    tracing: Option[Tracing] = None,
    log: String => Unit = _ => (),
    settings: SzorkConfig = SzorkConfig()
  ): SzorkResult[PlaytestReport] = {
    val own = config.copy(
      theme = adventure.theme.fold(adventure.outline.title)(_.prompt),
      artStyle = adventure.artStyle.map(_.id),
      adventureId = Some(adventure.id))
    play(own, adventure.outline, llm, player, model, tracing, log, settings)
  }

  /** Plays `outline` and reports what was found. */
  def play(
    config: PlaytestConfig,
    outline: AdventureOutline,
    llm: LLMClient,
    player: Player,
    model: String = "scripted",
    tracing: Option[Tracing] = None,
    log: String => Unit = _ => (),
    settings: SzorkConfig = SzorkConfig()
  ): SzorkResult[PlaytestReport] = {
    log(s"Adventure: ${outline.title} (${outline.keyLocations.size} locations)")
    val engines = EngineFactory.default(llm, tracing, settings, imageClient = None)
    for {
      engine <- newGame(outline, config, engines)
      opening <- engine.initialize()
    } yield {
      log(s"\n$opening\n")

      @annotation.tailrec
      def loop(
        narration: String,
        played: Vector[PlayedTurn],
        findings: List[Finding]): (Vector[PlayedTurn], List[Finding]) =
        if (played.size >= config.turns || engine.ending.isDefined) (played, findings)
        else {
          // A player that cannot choose looks around, and the failure is a finding of the turn
          val chosen = player.next(narration, engine.getCurrentScene, engine.getInventory, played)
          chosen.left.foreach(why => log(s"[the player could not choose: $why; looking around]"))
          val command = chosen.getOrElse("look")
          val before = engine.getWorldState
          val result = engine.processCommand(command, generateAudio = false)
          val turn = PlayedTurn(
            number = played.size + 1,
            command = command,
            narration = result.fold(_ => "", _.text),
            before = before,
            after = engine.getWorldState,
            scene = engine.getCurrentScene,
            inventory = engine.getInventory,
            refused = result.exists(_.refused),
            usage = result.toOption.flatMap(_ => engine.lastTurnUsage),
            error = result.left.toOption.map(_.message),
            playerError = chosen.left.toOption
          )
          val found = PlaytestChecks.turn(turn, played)
          log(s"> $command\n${turn.error.fold(turn.narration)(e => s"[failed: $e]")}")
          found.foreach(f => log(s"  [${f.check}] ${f.detail}"))
          log("")
          // A failed turn leaves the game as it was, so the player reads the last narration again
          loop(if (turn.error.isEmpty) turn.narration else narration, played :+ turn, findings ++ found)
        }

      val (turns, found) = loop(opening, Vector.empty, Nil)
      val probed = if (config.probeExits) probeExits(outline, config, engines, log) else Nil
      PlaytestReport(
        model,
        config.theme,
        outline,
        turns,
        PlaytestChecks.outline(outline) ++ found ++ PlaytestChecks.game(turns) ++ probed,
        engine.ending)
    }
  }

  /** Walks at each locked or sealed exit of the start location, in a new game each, and reports any the player got
    * through. (A closed exit may simply be opened, and a hidden one cannot be named, so neither is probed.)
    */
  private def probeExits(
    outline: AdventureOutline,
    config: PlaytestConfig,
    engines: EngineFactory,
    log: String => Unit): List[Finding] = {
    val shut = Set(ExitState.Locked.wire, ExitState.Sealed.wire)
    val exits = outline.keyLocations.headOption.toList.flatMap(_.exits).filter(exit => shut(exit.state)).take(MaxProbes)
    exits.flatMap { exit =>
      val command = s"go ${exit.direction}"
      val outcome = for {
        engine <- newGame(outline, config, engines)
        _ <- engine.initialize()
        start = engine.getWorldState.currentLocation
        _ <- engine.processCommand(command, generateAudio = false)
      } yield (start, engine.getWorldState.currentLocation)
      outcome match {
        case Right((start, end)) if end.contains(exit.targetId) =>
          log(s"Probe '$command': the player walked through a ${exit.state} exit")
          Some(
            Finding(
              "locked-exit-walked",
              hard = true,
              s"'$command' from ${start.mkString} took the player through the ${exit.state} exit to ${exit.targetId}"))
        case Right(_) =>
          log(s"Probe '$command': the ${exit.state} exit held")
          None
        case Left(error) =>
          Some(Finding("probe-failed", hard = false, s"'$command' could not be played: ${error.message}"))
      }
    }
  }

  /** A new game of `outline`, made by `engines` as the server makes one. */
  private def newGame(
    outline: AdventureOutline,
    config: PlaytestConfig,
    engines: EngineFactory): SzorkResult[GameEngine] =
    engines
      .create(IdGenerator.sessionId(), Some(config.theme), config.artStyle, Some(outline), TextOnly)
      .left
      .map(error => ConfigurationError(s"The game could not be started: $error"))
      .map { engine =>
        config.adventureId.foreach(engine.playAdventure)
        engine
      }

  /** Writes `report`'s JSON to `path`, making the directories it needs, or says why it could not. */
  def writeReport(report: PlaytestReport, path: Path): Either[String, Path] =
    Try {
      Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
      Files.writeString(path, ujson.write(report.toJson, indent = 2))
    }.toEither.left.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}")

  private final case class Arguments(
    theme: String,
    turns: Int,
    report: Option[String],
    probe: Boolean,
    adventure: Option[String] = None)

  private def parse(args: List[String], parsed: Arguments): Either[String, Arguments] = args match {
    case Nil => Right(parsed)
    case "--theme" :: theme :: rest => parse(rest, parsed.copy(theme = theme))
    case "--turns" :: turns :: rest =>
      turns.toIntOption.filter(_ > 0).toRight(s"--turns takes a positive number, not '$turns'").flatMap { n =>
        parse(rest, parsed.copy(turns = n))
      }
    case "--report" :: path :: rest => parse(rest, parsed.copy(report = Some(path)))
    case "--no-probes" :: rest => parse(rest, parsed.copy(probe = false))
    case "--adventure" :: id :: rest => parse(rest, parsed.copy(adventure = Some(id)))
    case other :: _ => Left(s"unknown argument '$other'")
  }

  private val Usage =
    "Usage: Playtest [--theme <text> | --adventure <library-id>] [--turns <n>] [--report <file.json>] [--no-probes]"

  def main(args: Array[String]): Unit = {
    val status =
      parse(args.toList, Arguments("a haunted lighthouse on a storm-battered coast", 25, None, probe = true)) match {
        case Left(problem) =>
          println(s"$problem\n$Usage")
          2
        case Right(arguments) =>
          val settings = SzorkConfig.instance
          val model = settings.llmConfig.fold("no model")(c => s"${c.provider}/${c.model.getOrElse("default")}")
          SzorkConfig.getLLMClient() match {
            case Left(error) =>
              println(s"No LLM client: $error")
              2
            case Right(llm) =>
              val tracing = AgentTracing.fromEnvironment
              val config = PlaytestConfig(arguments.theme, arguments.turns, arguments.probe)
              val player = Player.model(llm, settings.reasoningEffort)
              // A library adventure is the same outline every run, so runs on different models compare like for like
              val adventure = arguments.adventure.map(id =>
                AdventureLibrary
                  .at(settings)
                  .load(id)
                  .toRight(NotFoundError(s"No adventure '$id' in the adventure library")))
              println(
                s"Playtest on $model, reasoning effort ${settings.reasoningEffort.fold("default")(_.name)}: " +
                  s"${adventure.flatMap(_.toOption).fold(config.theme)(a => s"${a.outline.title} (${a.id})")} " +
                  s"(up to ${config.turns} turns)")
              val result = adventure match {
                case None => run(config, llm, player, model, tracing, println, settings)
                case Some(found) =>
                  found.flatMap(a => playAdventure(a, config, llm, player, model, tracing, println, settings))
              }
              tracing.foreach(_.close())
              result match {
                case Left(error) =>
                  println(s"The playtest could not run: ${error.message}")
                  2
                case Right(report) =>
                  println(report.summary)
                  val written = arguments.report.forall { path =>
                    writeReport(report, Paths.get(path)) match {
                      case Right(_) =>
                        println(s"Report written to $path")
                        true
                      case Left(why) =>
                        println(s"The report could not be written to $path: $why")
                        false
                    }
                  }
                  if (!written) 2 else if (report.passed) 0 else 1
              }
          }
      }
    sys.exit(status)
  }
}
