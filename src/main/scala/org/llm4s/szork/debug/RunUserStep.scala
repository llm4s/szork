package org.llm4s.szork.debug

import org.llm4s.szork.game._
import org.llm4s.szork.persistence.{StepData, StepPersistence}
import org.llm4s.szork.api.SzorkConfig
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}

/** Debug script to execute a user command on an existing adventure.
  *
  * Usage: sbt "runMain org.llm4s.szork.debug.RunUserStep <game-id> <previous-step> <command>"
  *
  * Example: sbt "runMain org.llm4s.szork.debug.RunUserStep test-game-1 1 'look around'" sbt "runMain
  * org.llm4s.szork.debug.RunUserStep test-game-1 2 'go north'" sbt "runMain org.llm4s.szork.debug.RunUserStep
  * test-game-1 3 'take lantern'"
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

      // No media clients: the debug tools only play the text of a turn
      val engine = GameEngine.create(
        llmClient = llmClient,
        sessionId = sessionId,
        theme = gameState.theme.map(_.prompt),
        artStyle = gameState.artStyle.map(_.id),
        adventureOutline = gameState.adventureOutline
      )

      // Restore the saved state and step number
      engine.restoreGameState(gameState)
      engine.setStepNumber(nextStep)
      println(s"✓ Game engine restored (${engine.getMessageCount} messages)")

      // Process the user command
      println(s"\nProcessing command: '${config.command}'...")
      val startTime = System.currentTimeMillis()
      val response: GameResponse = engine.processCommand(
        config.command,
        generateAudio = false,
        onTextChunk = Some(text => print(text)) // Print streaming text chunks
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

      // The step, as the server saves it: with this turn's tool calls only
      val stepData = engine.createStepData(
        gameId = config.gameId,
        gameTheme = gameState.theme,
        gameArtStyle = gameState.artStyle,
        userCommand = Some(config.command),
        narrationText = response.text,
        response = Some(response),
        executionTimeMs = executionTime
      )
      if (stepData.toolCalls.nonEmpty) {
        println(s"✓ Detected ${stepData.toolCalls.length} tool call(s)")
      }

      // Save step data
      println(s"\nSaving step $nextStep...")
      // As the server saves a turn
      StepPersistence.saveNextStep(stepData, executionTime) match {
        case Right(_) => println(s"✓ Step $nextStep saved and game metadata updated")
        case Left(error) =>
          println(s"ERROR: Failed to save step: ${error.message}")
          System.exit(1)
          return
      }

      // Print summary
      DebugHelpers.printStepSummary(stepData)

      println("SUCCESS: Step completed!")
      println(
        s"""Next step: sbt "runMain org.llm4s.szork.debug.RunUserStep ${config.gameId} $nextStep '<next-command>'" """)

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
    require(IdGenerator.isValidGameId(gameId), s"'$gameId' is not a game id (expected e.g. game-1a2b3c4d)")

    Config(gameId, step, command)
  }

  private def printUsage(): Unit =
    println("""
      |Usage: sbt "runMain org.llm4s.szork.debug.RunUserStep <game-id> <previous-step> <command>"
      |
      |Arguments:
      |  <game-id>           Game id, as printed by CreateAdventure (e.g. game-1a2b3c4d)
      |  <previous-step>     Previous step number to load from
      |  <command>           User command to execute (quoted)
      |
      |Examples:
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep game-1a2b3c4d 1 'look around'"
      |  sbt "runMain org.llm4s.szork.debug.RunUserStep game-1a2b3c4d 2 'take lantern'"
      |
      |Output:
      |  Creates szork-saves/<game-id>/step-<N+1>/ with:
      |    - metadata.json          : Step metadata
      |    - state.json             : Updated game state, including the agent conversation
      |    - command.txt            : User command
      |    - response.txt           : Narration text
      |    - response.json          : Structured response (if the step described a scene)
      |    - tool-calls.json        : Tool calls (if any)
      |""".stripMargin)
}
