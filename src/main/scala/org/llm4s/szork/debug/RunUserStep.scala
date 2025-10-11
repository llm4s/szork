package org.llm4s.szork.debug

import org.llm4s.szork._
import org.llm4s.szork.adapters.DefaultClients
import org.llm4s.config.EnvLoader
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}

/** Debug script to execute a user command on an existing adventure session.
  *
  * Usage: sbt "runMain org.llm4s.szork.debug.RunUserStep <session-name> <step> <command>"
  *
  * Example: sbt "runMain org.llm4s.szork.debug.RunUserStep test-session-1 1 'look around'" sbt "runMain
  * org.llm4s.szork.debug.RunUserStep test-session-1 2 'go north'" sbt "runMain org.llm4s.szork.debug.RunUserStep
  * test-session-1 3 'take lantern'"
  */
object RunUserStep {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  case class Config(
    sessionName: String,
    previousStep: Int,
    command: String
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

    val nextStep = config.previousStep + 1

    println(s"\n=== Running Step $nextStep: ${config.sessionName} ===")
    println(s"Command: ${config.command}")
    println()

    try {
      // Load previous game state
      println(s"Loading game state from step ${config.previousStep}...")
      val previousStepDir = DebugHelpers.getStepDir(config.sessionName, config.previousStep)
      val gameStateJson = DebugHelpers.loadJson(previousStepDir.resolve("game-state.json"))
      val gameState = GameStateCodec.fromJson(gameStateJson)
      println(s"✓ Game state loaded (${gameState.agentMessages.length} messages)")

      // Initialize LLM client
      println("\nInitializing LLM client...")
      implicit val llmClient = org.llm4s.llmconnect.LLMConnect.getClient(EnvLoader) match {
        case Right(client) => client
        case Left(error) =>
          println(s"ERROR: Failed to initialize LLM client: ${error.message}")
          System.exit(1)
          return
      }
      println("✓ LLM client initialized")

      // Create game engine and restore state
      println("\nRestoring game engine...")
      val sessionId = IdGenerator.sessionId()

      // Create SPI clients (optional, not used in debug mode)
      val ttsClient = DefaultClients.createTTSClient(EnvLoader)
      val imageClient = DefaultClients.createImageClient(EnvLoader)
      val musicClient = DefaultClients.createMusicClient(EnvLoader)

      val engine = GameEngine.create(
        llmClient = llmClient,
        sessionId = sessionId,
        theme = gameState.theme.map(_.prompt),
        artStyle = gameState.artStyle.map(_.id),
        adventureOutline = gameState.adventureOutline,
        ttsClient = ttsClient,
        imageClient = imageClient,
        musicClient = musicClient
      )

      // Restore the saved state
      engine.restoreGameState(gameState)
      println(s"✓ Game engine restored (${engine.getMessageCount} messages)")

      // Process the user command
      println(s"\nProcessing command: '${config.command}'...")
      val startTime = System.currentTimeMillis()
      val response: engine.GameResponse = engine.processCommand(config.command, generateAudio = false) match {
        case Right(resp) =>
          val elapsed = System.currentTimeMillis() - startTime
          println(s"✓ Command processed in ${elapsed}ms")
          resp
        case Left(error: org.llm4s.szork.error.SzorkError) =>
          println(s"ERROR: Failed to process command: ${error.message}")
          error.cause.foreach(ex => logger.error("Command processing error", ex))

          // Save error state anyway
          val errorStepDir = DebugHelpers.createStepDir(config.sessionName, nextStep)
          val errorMetadata = DebugHelpers.StepMetadata(
            sessionName = config.sessionName,
            stepNumber = nextStep,
            timestamp = System.currentTimeMillis(),
            userCommand = Some(config.command),
            responseLength = 0,
            toolCallCount = 0,
            messageCount = engine.getMessageCount,
            success = false,
            error = Some(error.message) // error is already typed as SzorkError from the match
          )

          val updatedGameState = engine.getGameState(gameState.gameId, gameState.theme, gameState.artStyle)
          DebugHelpers.saveStepData(
            stepDir = errorStepDir,
            metadata = errorMetadata,
            gameState = updatedGameState,
            userCommand = Some(config.command),
            agentMessages = Some(engine.getState.conversation.messages)
          )

          System.exit(1)
          return
      }

      // Extract tool calls from the agent conversation
      val allMessages = engine.getState.conversation.messages
      val toolCalls = DebugHelpers.extractToolCalls(allMessages)
      if (toolCalls.nonEmpty) {
        println(s"✓ Detected ${toolCalls.length} tool call(s)")
      }

      // Check if media generation prompts would be created (but don't actually generate)
      val musicPrompt = if (engine.shouldGenerateBackgroundMusic(response.text)) {
        val scene = engine.getCurrentScene
        val mood = scene.map(_.musicMood).getOrElse(MediaPlanner.detectMoodFromText(response.text))
        val description = scene.map(_.musicDescription).getOrElse(response.text)
        Some(s"Mood: $mood\nDescription: $description")
      } else None

      val imagePrompt = if (engine.shouldGenerateSceneImage(response.text)) {
        val scene = engine.getCurrentScene
        scene.map(_.imageDescription)
      } else None

      // Get updated game state
      val updatedGameState = engine.getGameState(gameState.gameId, gameState.theme, gameState.artStyle)

      // Create next step directory and save all data
      println(s"\nSaving step data...")
      val stepDir = DebugHelpers.createStepDir(config.sessionName, nextStep)

      val metadata = DebugHelpers.StepMetadata(
        sessionName = config.sessionName,
        stepNumber = nextStep,
        timestamp = System.currentTimeMillis(),
        userCommand = Some(config.command),
        responseLength = response.text.length,
        toolCallCount = toolCalls.length,
        messageCount = engine.getMessageCount,
        success = true
      )

      DebugHelpers.saveStepData(
        stepDir = stepDir,
        metadata = metadata,
        gameState = updatedGameState,
        response = Some(response),
        userCommand = Some(config.command),
        toolCalls = toolCalls,
        agentMessages = Some(allMessages)
      )

      // Save media generation prompts if any
      musicPrompt.foreach { prompt =>
        DebugHelpers.saveText(stepDir.resolve("music-prompt.txt"), prompt)
        println("  ✓ Music generation prompt saved")
      }

      imagePrompt.foreach { prompt =>
        DebugHelpers.saveText(stepDir.resolve("image-prompt.txt"), prompt)
        println("  ✓ Image generation prompt saved")
      }

      println(s"✓ Step data saved to: ${stepDir.toAbsolutePath}")

      // Print summary
      DebugHelpers.printStepSummary(nextStep, Some(config.command), response, toolCalls, metadata)

      println("SUCCESS: Step completed!")
      println(s"""Next step: sbt "runMain org.llm4s.szork.debug.RunUserStep ${config.sessionName} $nextStep '<next-command>'" """)

    } catch {
      case ex: Exception =>
        println(s"\nERROR: ${ex.getMessage}")
        logger.error("Failed to run step", ex)
        System.exit(1)
    }
  }

  private def parseArgs(args: Array[String]): Try[Config] = Try {
    if (args.length < 3) {
      throw new IllegalArgumentException("Session name, step number, and command are required")
    }

    val sessionName = args(0)
    val step = Try(args(1).toInt).getOrElse(
      throw new IllegalArgumentException(s"Invalid step number: ${args(1)}")
    )
    val command = args(2)

    Config(sessionName, step, command)
  }

  private def printUsage(): Unit = {
    println("""
      |Usage: sbt "runMain org.llm4s.szork.debug.RunUserStep <session-name> <previous-step> <command>"
      |
      |Arguments:
      |  <session-name>      Name of the debug session (required)
      |  <previous-step>     Previous step number to load from (required)
      |  <command>           User command to execute (required, must be quoted)
      |
      |Examples:
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep test-session-1 1 'look around'"
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep test-session-1 2 'go north'"
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep test-session-1 3 'take lantern'"
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep test-session-1 4 'inventory'"
      |
      |Output:
      |  Creates runs/<session-name>/step-<N+1>/ with:
      |    - user-command.txt       : The command that was executed
      |    - response.json          : Game response
      |    - game-state.json        : Updated game state
      |    - agent-messages.json    : Updated LLM conversation
      |    - conversation.txt       : Human-readable conversation log
      |    - tool-calls.json        : Tool execution details (if any)
      |    - music-prompt.txt       : Music generation prompt (if applicable)
      |    - image-prompt.txt       : Image generation prompt (if applicable)
      |    - metadata.json          : Step metadata
      |""".stripMargin)
  }
}
