package org.llm4s.szork.persistence

import org.llm4s.llmconnect.model.Message
import org.llm4s.szork.game.{GameScene, AdventureOutline}
import ujson.Value

/** Unified data models for step-based game persistence.
  *
  * These models are used by both regular gameplay (UI) and debug sessions, providing a single source of truth for game
  * state storage.
  */

// Note: GameMetadata is defined in GamePersistence.scala and includes:
// - gameId, theme, artStyle, adventureTitle
// - createdAt, lastSaved, lastPlayed, totalPlayTime
// - currentStep, totalSteps (for step-based persistence)

/** Metadata for a single step (stored in step-N/metadata.json).
  *
  * @param gameId
  *   Game identifier this step belongs to
  * @param stepNumber
  *   Step number (1-indexed)
  * @param timestamp
  *   Timestamp when step was executed
  * @param userCommand
  *   User command that triggered this step (None for initial step)
  * @param responseLength
  *   Length of narration text response
  * @param toolCallCount
  *   Number of tool calls executed
  * @param messageCount
  *   Total messages in conversation after this step
  * @param success
  *   Whether step executed successfully
  * @param error
  *   Error message if step failed
  * @param executionTimeMs
  *   Time taken to execute step in milliseconds
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
  *
  * @param metadata
  *   Step-level metadata
  * @param gameState
  *   Complete game state at this step
  * @param userCommand
  *   User command text (for convenience, also in metadata)
  * @param narrationText
  *   Narration text response
  * @param response
  *   Structured response (GameScene or SimpleResponse)
  * @param toolCalls
  *   Tool calls executed during this step
  * @param agentMessages
  *   Complete agent conversation at this step
  * @param outline
  *   Adventure outline (included in step 1 only)
  */
case class StepData(
  metadata: StepMetadata,
  gameState: GameState,
  userCommand: Option[String],
  narrationText: String,
  response: Option[GameResponse],
  toolCalls: List[ToolCallInfo] = Nil,
  agentMessages: List[Message] = Nil,
  outline: Option[AdventureOutline] = None
)

/** Information about a tool call executed during a step.
  *
  * @param id
  *   Tool call identifier
  * @param name
  *   Tool name (e.g., "list_inventory")
  * @param arguments
  *   Tool arguments as JSON
  * @param result
  *   Tool result (if available)
  * @param timestamp
  *   Timestamp when tool was called
  */
case class ToolCallInfo(
  id: String,
  name: String,
  arguments: Value,
  result: Option[String],
  timestamp: Long
)

/** Structured game response (scene or simple action result).
  *
  * This can represent either:
  *   - A full scene with location, exits, items, NPCs
  *   - A simple response to an action (examine, inventory, etc.)
  */
sealed trait GameResponse {
  def narrationText: String
}

/** Full scene response with location details. */
case class SceneResponse(
  scene: GameScene
) extends GameResponse {
  override def narrationText: String = scene.narrationText
}

/** Simple action response without scene change. */
case class ActionResponse(
  narrationText: String,
  locationId: String,
  action: String
) extends GameResponse

object StepData {

  /** Create StepData for initial game creation (step 1). */
  def initialStep(
    gameId: String,
    gameState: GameState,
    narrationText: String,
    scene: Option[GameScene],
    outline: Option[AdventureOutline],
    agentMessages: List[Message],
    executionTimeMs: Long
  ): StepData = {
    val metadata = StepMetadata(
      gameId = gameId,
      stepNumber = 1,
      timestamp = System.currentTimeMillis(),
      userCommand = None,
      responseLength = narrationText.length,
      toolCallCount = 0,
      messageCount = agentMessages.length,
      success = true,
      executionTimeMs = executionTimeMs
    )

    val response = scene.map(SceneResponse)

    StepData(
      metadata = metadata,
      gameState = gameState,
      userCommand = None,
      narrationText = narrationText,
      response = response,
      toolCalls = Nil,
      agentMessages = agentMessages,
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
    response: Option[GameResponse],
    toolCalls: List[ToolCallInfo],
    agentMessages: List[Message],
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
      messageCount = agentMessages.length,
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
      agentMessages = agentMessages,
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
