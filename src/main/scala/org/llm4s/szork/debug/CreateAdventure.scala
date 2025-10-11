package org.llm4s.szork.debug

import org.llm4s.szork._
import org.llm4s.szork.adapters.DefaultClients
import org.llm4s.config.EnvLoader
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}

/** Debug script to create a new adventure and save the initial state.
  *
  * Usage: sbt "runMain org.llm4s.szork.debug.CreateAdventure <session-name> [--theme <theme>] [--art-style <style>]"
  *
  * Example: sbt "runMain org.llm4s.szork.debug.CreateAdventure test-session-1" sbt "runMain
  * org.llm4s.szork.debug.CreateAdventure fantasy-quest --theme 'medieval fantasy' --art-style illustration"
  */
object CreateAdventure {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  case class Config(
    sessionName: String,
    theme: String = "classic fantasy adventure",
    artStyle: String = "fantasy"
  )

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      printUsage()
      System.exit(1)
    }

    val config = parseArgs(args) match {
      case Success(cfg) => cfg
      case Failure(ex) =>
        println(s"Error: ${ex.getMessage}")
        printUsage()
        System.exit(1)
        return
    }

    println(s"\n=== Creating Adventure: ${config.sessionName} ===")
    println(s"Theme: ${config.theme}")
    println(s"Art Style: ${config.artStyle}")
    println()

    try {
      // Initialize LLM client
      println("Initializing LLM client...")
      implicit val llmClient = org.llm4s.llmconnect.LLMConnect.getClient(EnvLoader) match {
        case Right(client) => client
        case Left(error) =>
          println(s"ERROR: Failed to initialize LLM client: ${error.message}")
          System.exit(1)
          return
      }
      println("✓ LLM client initialized")

      // Generate adventure outline
      println(s"\nGenerating adventure outline for theme: '${config.theme}'...")
      val outline = AdventureGenerator.generateAdventureOutline(config.theme, config.artStyle) match {
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
      val gameId = IdGenerator.gameId()

      // Create SPI clients (optional, not used in debug mode)
      val ttsClient = DefaultClients.createTTSClient(EnvLoader)
      val imageClient = DefaultClients.createImageClient(EnvLoader)
      val musicClient = DefaultClients.createMusicClient(EnvLoader)

      val engine = GameEngine.create(
        llmClient = llmClient,
        sessionId = sessionId,
        theme = Some(config.theme),
        artStyle = Some(config.artStyle),
        adventureOutline = Some(outline),
        ttsClient = ttsClient,
        imageClient = imageClient,
        musicClient = musicClient
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

      // Get the initial scene and game state
      val initialScene: GameScene = engine.getCurrentScene match {
        case Some(scene) => scene
        case None =>
          println("ERROR: No initial scene after initialization")
          System.exit(1)
          return
      }

      val gameTheme = Some(GameTheme(id = config.artStyle, name = config.theme, prompt = config.theme))
      val gameArtStyle = Some(ArtStyle(id = config.artStyle, name = config.artStyle))
      val gameState = engine.getGameState(gameId, gameTheme, gameArtStyle)

      // Create step-1 directory and save all data
      println("\nSaving adventure data...")
      val stepDir = DebugHelpers.createStepDir(config.sessionName, 1)

      val metadata = DebugHelpers.StepMetadata(
        sessionName = config.sessionName,
        stepNumber = 1,
        timestamp = System.currentTimeMillis(),
        userCommand = None,
        responseLength = initialText.length,
        toolCallCount = 0,
        messageCount = engine.getMessageCount,
        success = true
      )

      DebugHelpers.saveStepData(
        stepDir = stepDir,
        metadata = metadata,
        gameState = gameState,
        outline = Some(outline),
        agentMessages = Some(engine.getState.conversation.messages)
      )

      println(s"✓ Adventure data saved to: ${stepDir.toAbsolutePath}")

      // Print summary
      DebugHelpers.printAdventureSummary(config.sessionName, outline, initialScene)

      println("SUCCESS: Adventure created and ready for play!")
      println(s"Next step: Run user commands with RunUserStep")
      println(s"""Example: sbt "runMain org.llm4s.szork.debug.RunUserStep ${config.sessionName} 1 'look around'" """)

    } catch {
      case ex: Exception =>
        println(s"\nERROR: ${ex.getMessage}")
        logger.error("Failed to create adventure", ex)
        System.exit(1)
    }
  }

  private def parseArgs(args: Array[String]): Try[Config] = Try {
    if (args.isEmpty) {
      throw new IllegalArgumentException("Session name is required")
    }

    val sessionName = args(0)
    var theme = "classic fantasy adventure"
    var artStyle = "fantasy"

    var i = 1
    while (i < args.length) {
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
    }

    Config(sessionName, theme, artStyle)
  }

  private def printUsage(): Unit = {
    println("""
      |Usage: sbt "runMain org.llm4s.szork.debug.CreateAdventure <session-name> [OPTIONS]"
      |
      |Arguments:
      |  <session-name>      Name for this debug session (required)
      |
      |Options:
      |  --theme <theme>     Adventure theme (default: "classic fantasy adventure")
      |  --art-style <style> Art style: fantasy|pixel|illustration|painting|comic (default: "fantasy")
      |
      |Examples:
      |  sbt "runMain org.llm4s.szork.debug.CreateAdventure test-session-1"
      |  sbt "runMain org.llm4s.szork.debug.CreateAdventure space-quest --theme 'sci-fi space adventure' --art-style comic"
      |
      |Output:
      |  Creates runs/<session-name>/step-1/ with:
      |    - adventure-outline.json  : Generated adventure design
      |    - game-state.json        : Complete game state
      |    - agent-messages.json    : Full LLM conversation
      |    - conversation.txt       : Human-readable conversation log
      |    - metadata.json          : Session metadata
      |""".stripMargin)
  }
}
