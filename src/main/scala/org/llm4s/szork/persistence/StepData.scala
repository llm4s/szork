package org.llm4s.szork.persistence

import org.llm4s.llmconnect.model.{AssistantMessage, Message, ToolMessage}
import org.llm4s.szork.game.{GameScene, AdventureOutline}
import ujson.Value

// Note: GameMetadata is defined in GameState.scala and includes:
// - gameId, theme, artStyle, adventureTitle
// - createdAt, lastSaved, lastPlayed, totalPlayTime
// - currentStep, totalSteps (for step-based persistence)

/** Metadata for a single step (stored in step-N/metadata.json): `stepNumber` is 1-indexed, `userCommand` None for the
  * initial step.
  */
case class StepMetadata(
  gameId: String,
  stepNumber: Int,
  timestamp: Long,
  userCommand: Option[String],
  responseLength: Int,
  toolCallCount: Int,
  messageCount: Int,
  success: Boolean,
  error: Option[String] = None,
  executionTimeMs: Long = 0
)

/** Complete data for a single step.
  *
  * This is the primary data structure for step-based persistence. Each step directory contains a complete snapshot of
  * game state.
  */
case class StepData(
  metadata: StepMetadata,
  gameState: GameState,
  userCommand: Option[String],
  narrationText: String,
  response: Option[SceneResponse],
  toolCalls: List[ToolCallInfo] = Nil,
  outline: Option[AdventureOutline] = None
)

/** Information about a tool call executed during a step.
  */
case class ToolCallInfo(
  id: String,
  name: String,
  arguments: Value,
  result: Option[String],
  timestamp: Long
)

object ToolCallInfo {

  /** The tool calls the assistant made in `messages`, each with its result if a tool message answers it. */
  def fromMessages(messages: Seq[Message]): List[ToolCallInfo] = {
    val results = messages.collect { case tool: ToolMessage => tool.toolCallId -> tool.content }.toMap
    messages.toList.flatMap {
      case assistant: AssistantMessage =>
        assistant.toolCalls.map { tc =>
          ToolCallInfo(tc.id.asString, tc.name, tc.arguments, results.get(tc.id), System.currentTimeMillis())
        }
      case _ => Nil
    }
  }
}

/** Structured response recorded with a step: the scene the step described. */
case class SceneResponse(
  scene: GameScene
)

object StepData {

  /** Create StepData for initial game creation (step 1). */
  def initialStep(
    gameId: String,
    gameState: GameState,
    narrationText: String,
    scene: Option[GameScene],
    outline: Option[AdventureOutline],
    executionTimeMs: Long
  ): StepData = {
    val metadata = StepMetadata(
      gameId = gameId,
      stepNumber = 1,
      timestamp = System.currentTimeMillis(),
      userCommand = None,
      responseLength = narrationText.length,
      toolCallCount = 0,
      messageCount = gameState.agentMessages.length,
      success = true,
      executionTimeMs = executionTimeMs
    )

    val response = scene.map(SceneResponse.apply)

    StepData(
      metadata = metadata,
      gameState = gameState,
      userCommand = None,
      narrationText = narrationText,
      response = response,
      toolCalls = Nil,
      outline = outline
    )
  }

  /** Create StepData for a command execution step. */
  def commandStep(
    gameId: String,
    stepNumber: Int,
    gameState: GameState,
    userCommand: String,
    narrationText: String,
    response: Option[SceneResponse],
    toolCalls: List[ToolCallInfo],
    executionTimeMs: Long,
    success: Boolean = true,
    error: Option[String] = None
  ): StepData = {
    val metadata = StepMetadata(
      gameId = gameId,
      stepNumber = stepNumber,
      timestamp = System.currentTimeMillis(),
      userCommand = Some(userCommand),
      responseLength = narrationText.length,
      toolCallCount = toolCalls.length,
      messageCount = gameState.agentMessages.length,
      success = success,
      error = error,
      executionTimeMs = executionTimeMs
    )

    StepData(
      metadata = metadata,
      gameState = gameState,
      userCommand = Some(userCommand),
      narrationText = narrationText,
      response = response,
      toolCalls = toolCalls,
      outline = None
    )
  }
}

object GameMetadataHelper {

  /** Create initial metadata for a new game. */
  def initial(
    gameId: String,
    adventureTitle: String,
    theme: String,
    artStyle: String,
    createdAt: Long
  ): GameMetadata = GameMetadata(
    gameId = gameId,
    theme = theme,
    artStyle = artStyle,
    adventureTitle = adventureTitle,
    createdAt = createdAt,
    lastSaved = createdAt,
    lastPlayed = createdAt,
    totalPlayTime = 0,
    currentStep = 1,
    totalSteps = 1
  )

  /** Update metadata after executing a step. */
  def afterStep(
    metadata: GameMetadata,
    stepNumber: Int,
    playTimeDelta: Long
  ): GameMetadata = metadata.copy(
    lastSaved = System.currentTimeMillis(),
    lastPlayed = System.currentTimeMillis(),
    currentStep = stepNumber,
    totalSteps = math.max(metadata.totalSteps, stepNumber),
    totalPlayTime = metadata.totalPlayTime + playTimeDelta
  )
}
