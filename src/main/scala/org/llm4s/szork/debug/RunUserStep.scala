package org.llm4s.szork.debug

import org.llm4s.szork.game._
import org.llm4s.szork.persistence.{StepData, StepPersistence, GameMetadataHelper, SceneResponse, GameResponse}
import org.llm4s.szork.adapters.DefaultClients
import org.llm4s.szork.api.SzorkConfig
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}

/** Debug script to execute a user command on an existing adventure.
  *
  * Usage: sbt "runMain org.llm4s.szork.debug.RunUserStep <game-id> <previous-step> <command>"
  *
  * Example:
  *   sbt "runMain org.llm4s.szork.debug.RunUserStep test-game-1 1 'look around'"
  *   sbt "runMain org.llm4s.szork.debug.RunUserStep test-game-1 2 'go north'"
  *   sbt "runMain org.llm4s.szork.debug.RunUserStep test-game-1 3 'take lantern'"
  */
object RunUserStep {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  case class Config(
    gameId: String,
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

    println(s"\n=== Running Step $nextStep: ${config.gameId} ===")
    println(s"Command: ${config.command}")
    println()

    try {
      // Load previous game state
      println(s"Loading game state from step ${config.previousStep}...")
      val previousStepData = StepPersistence.loadStep(config.gameId, config.previousStep) match {
        case Right(data) =>
          println(s"✓ Step ${config.previousStep} loaded")
          data
        case Left(error) =>
          println(s"ERROR: Failed to load step ${config.previousStep}: ${error.message}")
          System.exit(1)
          return
      }

      val gameState = previousStepData.gameState
      println(s"✓ Game state loaded (${gameState.agentMessages.length} messages)")

      // Initialize LLM client
      println("\nInitializing LLM client...")
      implicit val llmClient: org.llm4s.llmconnect.LLMClient = SzorkConfig.getLLMClient() match {
        case Right(client) => client
        case Left(error) =>
          println(s"ERROR: Failed to initialize LLM client: $error")
          System.exit(1)
          return
      }
      println("✓ LLM client initialized")

      // Create game engine and restore state
      println("\nRestoring game engine...")
      val sessionId = IdGenerator.sessionId()

      // Create SPI clients (optional, not used in debug mode)
      val ttsClient = DefaultClients.createTTSClient()
      val imageClient = DefaultClients.createImageClient()
      val musicClient = DefaultClients.createMusicClient()

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

      // Restore the saved state and step number
      engine.restoreGameState(gameState)
      engine.setStepNumber(nextStep)
      println(s"✓ Game engine restored (${engine.getMessageCount} messages)")

      // Process the user command
      println(s"\nProcessing command: '${config.command}'...")
      val startTime = System.currentTimeMillis()
      val response: engine.GameResponse = engine.processCommandStreaming(
        config.command,
        onTextChunk = text => print(text), // Print streaming text chunks
        generateAudio = false
      ) match {
        case Right(resp) =>
          val elapsed = System.currentTimeMillis() - startTime
          println(s"✓ Command processed in ${elapsed}ms")
          resp
        case Left(error: org.llm4s.szork.error.SzorkError) =>
          println(s"ERROR: Failed to process command: ${error.message}")
          error.cause.foreach(ex => logger.error("Command processing error", ex))

          // Save error state
          val executionTime = System.currentTimeMillis() - startTime
          val errorStepData = StepData.commandStep(
            gameId = config.gameId,
            stepNumber = nextStep,
            gameState = engine.getGameState(config.gameId, gameState.theme, gameState.artStyle),
            userCommand = config.command,
            narrationText = s"ERROR: ${error.message}",
            response = None,
            toolCalls = Nil,
            agentMessages = engine.getState.conversation.messages.toList,
            executionTimeMs = executionTime,
            success = false,
            error = Some(error.message)
          )

          StepPersistence.saveStep(errorStepData) match {
            case Right(_) => println(s"✓ Error state saved to step $nextStep")
            case Left(saveError) => println(s"ERROR: Failed to save error state: ${saveError.message}")
          }

          System.exit(1)
          return
      }

      val executionTime = System.currentTimeMillis() - startTime

      // Extract tool calls from the agent conversation
      val allMessages = engine.getState.conversation.messages
      val toolCalls = DebugHelpers.extractToolCalls(allMessages)
      if (toolCalls.nonEmpty) {
        println(s"✓ Detected ${toolCalls.length} tool call(s)")
      }

      // Convert engine GameResponse to StepData GameResponse
      val gameResponse: Option[GameResponse] = response.scene.map(SceneResponse)

      // Create step data
      val stepData = StepData.commandStep(
        gameId = config.gameId,
        stepNumber = nextStep,
        gameState = engine.getGameState(config.gameId, gameState.theme, gameState.artStyle),
        userCommand = config.command,
        narrationText = response.text,
        response = gameResponse,
        toolCalls = toolCalls,
        agentMessages = allMessages.toList,
        executionTimeMs = executionTime
      )

      // Save step data
      println(s"\nSaving step $nextStep...")
      StepPersistence.saveStep(stepData) match {
        case Right(_) => println(s"✓ Step $nextStep saved")
        case Left(error) =>
          println(s"ERROR: Failed to save step: ${error.message}")
          System.exit(1)
          return
      }

      // Update game metadata
      StepPersistence.loadGameMetadata(config.gameId) match {
        case Right(metadata) =>
          val updated = GameMetadataHelper.afterStep(metadata, nextStep, executionTime)
          StepPersistence.saveGameMetadata(updated) match {
            case Right(_) => println("✓ Game metadata updated")
            case Left(error) => println(s"WARNING: Failed to update metadata: ${error.message}")
          }
        case Left(error) =>
          println(s"WARNING: Failed to load metadata for update: ${error.message}")
      }

      // Print summary
      DebugHelpers.printStepSummary(stepData)

      println("SUCCESS: Step completed!")
      println(s"""Next step: sbt "runMain org.llm4s.szork.debug.RunUserStep ${config.gameId} $nextStep '<next-command>'" """)

    } catch {
      case ex: Exception =>
        println(s"\nERROR: ${ex.getMessage}")
        logger.error("Failed to run step", ex)
        System.exit(1)
    }
  }

  private def parseArgs(args: Array[String]): Try[Config] = Try {
    if (args.length < 3) {
      throw new IllegalArgumentException("Game ID, step number, and command are required")
    }

    val gameId = args(0)
    val step = Try(args(1).toInt).getOrElse(
      throw new IllegalArgumentException(s"Invalid step number: ${args(1)}")
    )
    val command = args(2)

    Config(gameId, step, command)
  }

  private def printUsage(): Unit = {
    println("""
      |Usage: sbt "runMain org.llm4s.szork.debug.RunUserStep <game-id> <previous-step> <command>"
      |
      |Arguments:
      |  <game-id>           Game identifier (required)
      |  <previous-step>     Previous step number to load from (required)
      |  <command>           User command to execute (required, must be quoted)
      |
      |Examples:
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep test-game-1 1 'look around'"
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep test-game-1 2 'go north'"
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep test-game-1 3 'take lantern'"
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep test-game-1 4 'inventory'"
      |
      |Output:
      |  Creates szork-saves/<game-id>/step-<N+1>/ with:
      |    - metadata.json          : Step metadata
      |    - state.json             : Updated game state
      |    - command.txt            : User command
      |    - response.txt           : Narration text
      |    - response.json          : Structured response
      |    - messages.json          : Agent messages
      |    - tool-calls.json        : Tool calls (if any)
      |""".stripMargin)
  }
}
