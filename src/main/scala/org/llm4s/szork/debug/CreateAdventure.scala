package org.llm4s.szork.debug

import org.llm4s.szork.game._
import org.llm4s.szork.api.{GameTheme, ArtStyle, SzorkConfig}
import org.llm4s.szork.persistence.{GameMetadataHelper, StepData, StepPersistence}
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}

/** Debug script to create a new adventure and save the initial state.
  *
  * Usage: sbt "runMain org.llm4s.szork.debug.CreateAdventure [--theme <theme>] [--art-style <style>]"
  *
  * Example: sbt "runMain org.llm4s.szork.debug.CreateAdventure test-game-1" sbt "runMain
  * org.llm4s.szork.debug.CreateAdventure fantasy-quest --theme 'medieval fantasy' --art-style illustration"
  */
object CreateAdventure {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  case class Config(
    gameId: String,
    theme: String = "classic fantasy adventure",
    artStyle: String = "fantasy"
  )

  def main(args: Array[String]): Unit = {
    val config = parseArgs(args) match {
      case Success(cfg) => cfg
      case Failure(ex) =>
        println(s"Error: ${ex.getMessage}")
        printUsage()
        System.exit(1)
        return
    }

    println(s"\n=== Creating Adventure: ${config.gameId} ===")
    println(s"Theme: ${config.theme}")
    println(s"Art Style: ${config.artStyle}")
    println()

    try {
      // Initialize LLM client
      println("Initializing LLM client...")
      implicit val llmClient: org.llm4s.llmconnect.LLMClient = SzorkConfig.getLLMClient() match {
        case Right(client) => client
        case Left(error) =>
          println(s"ERROR: Failed to initialize LLM client: $error")
          System.exit(1)
          return
      }
      println("✓ LLM client initialized")

      // Generate adventure outline
      println(s"\nGenerating adventure outline for theme: '${config.theme}'...")
      val startTime = System.currentTimeMillis()
      val outline: AdventureOutline = AdventureGenerator.generateAdventureOutline(config.theme, config.artStyle) match {
        case Right(o) =>
          println(s"✓ Adventure outline generated: ${o.title}")
          o
        case Left(error) =>
          println(s"ERROR: Failed to generate adventure outline: ${error.message}")
          System.exit(1)
          return
      }

      // Create game engine with the outline
      println("\nInitializing game engine...")
      val sessionId = IdGenerator.sessionId()

      // No media clients: the debug tools only play the text of a turn
      val engine = GameEngine.create(
        llmClient = llmClient,
        sessionId = sessionId,
        theme = Some(config.theme),
        artStyle = Some(config.artStyle),
        adventureOutline = Some(outline)
      )
      println("✓ Game engine created")

      // Initialize the game (generates opening scene)
      println("\nGenerating opening scene...")
      val initialText: String = engine.initialize() match {
        case Right(text) =>
          println(s"✓ Opening scene generated (${text.length} chars)")
          text
        case Left(error: org.llm4s.szork.error.SzorkError) =>
          println(s"ERROR: Failed to initialize game: ${error.message}")
          System.exit(1)
          return
      }

      val executionTime = System.currentTimeMillis() - startTime

      // Get the initial scene
      val initialScene: GameScene = engine.getCurrentScene match {
        case Some(scene) => scene
        case None =>
          println("ERROR: No initial scene after initialization")
          System.exit(1)
          return
      }

      // As the server records a theme given by its description
      val gameTheme = Some(GameTheme(id = config.theme, name = config.theme, prompt = config.theme))
      val gameArtStyle = Some(ArtStyle(id = config.artStyle, name = config.artStyle))
      val gameState = engine.getGameState(config.gameId, gameTheme, gameArtStyle)

      // Create game metadata
      println("\nSaving adventure data...")
      val createdAt = System.currentTimeMillis()
      val metadata = GameMetadataHelper.initial(
        gameId = config.gameId,
        adventureTitle = outline.title,
        theme = config.theme,
        artStyle = config.artStyle,
        createdAt = createdAt
      )

      // Create step data for step 1
      val stepData = StepData.initialStep(
        gameId = config.gameId,
        gameState = gameState,
        narrationText = initialText,
        scene = Some(initialScene),
        outline = Some(outline),
        executionTimeMs = executionTime
      )

      StepPersistence.saveStep(stepData) match {
        case Right(_) => println("✓ Step 1 saved")
        case Left(error) =>
          println(s"ERROR: Failed to save step: ${error.message}")
          System.exit(1)
          return
      }

      // game.json last, so it never names a step that is not saved
      StepPersistence.saveGameMetadata(metadata) match {
        case Right(_) => println("✓ Game metadata saved")
        case Left(error) =>
          println(s"ERROR: Failed to save game metadata: ${error.message}")
          System.exit(1)
          return
      }

      // Print summary
      DebugHelpers.printAdventureSummary(config.gameId, outline, initialScene)

      println("SUCCESS: Adventure created and ready for play!")
      println(s"Next step: Run user commands with RunUserStep")
      println(s"""Example: sbt "runMain org.llm4s.szork.debug.RunUserStep ${config.gameId} 1 'look around'" """)

    } catch {
      case ex: Exception =>
        println(s"\nERROR: ${ex.getMessage}")
        logger.error("Failed to create adventure", ex)
        System.exit(1)
    }
  }

  private def parseArgs(args: Array[String]): Try[Config] = Try {
    // Save directories only accept generated ids, so the tool makes one (and prints it)
    val gameId = IdGenerator.gameId()
    var theme = "classic fantasy adventure"
    var artStyle = "fantasy"

    var i = 0
    while (i < args.length)
      args(i) match {
        case "--theme" =>
          if (i + 1 >= args.length) throw new IllegalArgumentException("--theme requires a value")
          theme = args(i + 1)
          i += 2
        case "--art-style" =>
          if (i + 1 >= args.length) throw new IllegalArgumentException("--art-style requires a value")
          artStyle = args(i + 1)
          i += 2
        case other =>
          throw new IllegalArgumentException(s"Unknown argument: $other")
      }

    Config(gameId, theme, artStyle)
  }

  private def printUsage(): Unit =
    println("""
      |Usage: sbt "runMain org.llm4s.szork.debug.CreateAdventure [OPTIONS]"
      |
      |Creates a new game (with a generated id, printed at the end) and plays its opening turn.
      |
      |Options:
      |  --theme <theme>     Adventure theme (default: "classic fantasy adventure")
      |  --art-style <style> Art style: fantasy|pixel|illustration|painting|comic (default: "fantasy")
      |
      |Examples:
      |  sbt "runMain org.llm4s.szork.debug.CreateAdventure"
      |  sbt "runMain org.llm4s.szork.debug.CreateAdventure --theme 'sci-fi space adventure' --art-style comic"
      |
      |Output:
      |  Creates szork-saves/<game-id>/step-0001/ with:
      |    - metadata.json          : Step metadata
      |    - state.json             : Complete game state, including the agent conversation
      |    - response.txt           : Narration text
      |    - response.json          : Structured response (scene)
      |    - outline.json           : Adventure outline
      |  and szork-saves/<game-id>/game.json with the game's metadata.
      |""".stripMargin)
}
