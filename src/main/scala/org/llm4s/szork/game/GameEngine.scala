package org.llm4s.szork.game

import org.llm4s.agent.{Agent, AgentResult, AgentStatus}
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.{GraphRuntime, RunConfig, StreamEvent, ThreadId}
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Message, UserMessage, AssistantMessage, SystemMessage, ToolMessage}
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import org.llm4s.szork.core.{CoreEngine, CoreState}
import org.llm4s.szork.spi.{Clock, SystemClock, TTSClient, ImageClient, MusicClient}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.debug.DebugHelpers
import org.llm4s.szork.api.{GameTheme, ArtStyle}
import org.llm4s.szork.media.MediaPlanner
import org.llm4s.szork.persistence.{StepData, GameState, MediaCacheEntry, MessageCodec, StepMetadata}
import org.llm4s.szork.streaming.StreamingTextParser

/** Core game engine managing AI-driven text adventure gameplay.
  *
  * Orchestrates LLM agent interactions, game state management, media generation, and persistence. Handles both
  * streaming and non-streaming gameplay modes.
  *
  * @param sessionId
  *   Unique identifier for this game session
  * @param theme
  *   Optional theme name for the adventure (e.g., "fantasy", "sci-fi")
  * @param artStyle
  *   Optional visual art style for generated images
  * @param adventureOutline
  *   Optional pre-generated adventure structure with locations and characters
  * @param clock
  *   Clock implementation for time tracking (defaults to system time)
  * @param ttsClient
  *   Optional text-to-speech client for narration
  * @param imageClient
  *   Optional client for scene image generation
  * @param musicClient
  *   Optional client for background music generation
  * @param llmClient
  *   LLM client for AI narrative generation (implicit)
  */
class GameEngine(
  sessionId: String = "",
  theme: Option[String] = None,
  artStyle: Option[String] = None,
  adventureOutline: Option[AdventureOutline] = None,
  clock: Clock = SystemClock,
  ttsClient: Option[TTSClient] = None,
  imageClient: Option[ImageClient] = None,
  musicClient: Option[MusicClient] = None
)(implicit llmClient: LLMClient) {
  private implicit val logger: org.slf4j.Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  private val themeDescription = PromptBuilder.themeDescription(theme)
  private val artStyleDescription = PromptBuilder.artStyleDescription(artStyle)
  // The adventure outline and the system prompt built from it. A restored game takes its outline from the save.
  private var outline: Option[AdventureOutline] = adventureOutline
  private var systemPrompt: String = PromptBuilder.fullSystemPrompt(theme, artStyle, outline)

  // This game's inventory, read and changed by the agent's tools
  private val inventory = new PlayerInventory()
  private val toolRegistry = new ToolRegistry(GameTools.forInventory(inventory))

  // The Dungeon Master is an llm4s Agent; the game's conversation is one of its threads.
  // Two agents are compiled from the same settings - one streaming, one not - over a shared
  // runtime: streaming is not part of the agent's graph version, so either can run the next
  // turn of the same thread.
  private val runtime: GraphRuntime = GraphRuntime.inMemory()
  private var agents: Result[GameEngine.DungeonMaster] = buildAgents(systemPrompt)

  // The thread holding the conversation, once a turn has run on it. After a restore (or a
  // failed turn) it is None, and the next turn starts a new thread seeded with `history`.
  private var threadId: Option[ThreadId] = None
  // The conversation as of the last successful turn: the thread's full history, without the
  // system prompt (the agent sends that on every model call).
  private var history: Vector[Message] = Vector.empty
  private var coreState: CoreState = CoreState()
  private var lastValidationIssues: Option[List[String]] = None
  // Whether the last turn moved the player to a new location (or opened the first scene);
  // scene images and location music are generated only then
  private var lastTurnChangedLocation: Boolean = false
  // Index into `history` where the last turn's messages begin
  private var lastTurnStart: Int = 0
  private val createdAt: Long = clock.now()
  private var sessionStartTime: Long = clock.now()
  private var totalPlayTime: Long = 0L // Accumulated play time from previous sessions
  private var adventureTitle: Option[String] = outline.map(_.title)

  // Enhanced state tracking
  private var mediaCache: Map[String, MediaCacheEntry] = Map.empty
  private var currentStepNumber: Int = 1 // Current step number for persistence

  /** Initializes a new game session with opening scene generation.
    *
    * @return
    *   The opening scene narration text, or an error
    */
  def initialize(): SzorkResult[String] = {
    logger.info(s"[$sessionId] Initializing game with theme: $themeDescription")

    inventory.replaceWith(Nil)
    coreState = CoreState()

    // A new game is a new thread, opened with the init adventure prompt as its first turn
    // (the trigger message is not shown to the user)
    startNewConversation(Vector.empty)

    runTurn(GameConstants.Prompts.INIT_ADVENTURE, listener = None) match {
      case Right(result) =>
        val outcome = completeTurn(result)
        logger.info(s"[$sessionId] Game initialized: ${outcome.scene.map(_.locationId).getOrElse("no scene")}")
        Right(outcome.narration)

      case Left(error) =>
        val szorkError = AIError(s"Failed to initialize game: ${error.message}", llmError = Some(error))
        Logging.logError(s"[$sessionId] Initialize game")(Left(szorkError))
        Left(szorkError)
    }
  }

  /** Response containing narrative text and optional generated media.
    *
    * @param text
    *   Narrative response text from the AI
    * @param audioBase64
    *   Base64-encoded audio narration
    * @param imageBase64
    *   Base64-encoded scene image
    * @param backgroundMusicBase64
    *   Base64-encoded background music
    * @param musicMood
    *   Mood descriptor for the music
    * @param scene
    *   Structured scene data if location changed
    */
  case class GameResponse(
    text: String,
    audioBase64: Option[String] = None,
    imageBase64: Option[String] = None,
    backgroundMusicBase64: Option[String] = None,
    musicMood: Option[String] = None,
    scene: Option[GameScene] = None
  )

  /** Processes a player command and generates AI response with optional media.
    *
    * @param command
    *   Player's text command
    * @param generateAudio
    *   Whether to generate TTS narration
    * @return
    *   Game response with text and media, or an error
    */
  def processCommand(command: String, generateAudio: Boolean = true): SzorkResult[GameResponse] = {
    logger.debug(s"[$sessionId] Processing command: $command")

    // Track user command in conversation history (pure core state)
    coreState = CoreEngine.trackUser(coreState, command)

    val textStartTime = System.currentTimeMillis()
    runTurn(command, listener = None) match {
      case Right(result) =>
        logger.debug(s"[$sessionId] Text generation completed in ${System.currentTimeMillis() - textStartTime}ms")
        val outcome = completeTurn(result)
        val audio = if (generateAudio) synthesize(outcome.narration) else None
        Right(GameResponse(outcome.narration, audio, None, None, None, outcome.scene.orElse(coreState.currentScene)))

      case Left(error) =>
        val szorkError = AIError(s"Error processing command: ${error.message}", llmError = Some(error))
        Logging.logError(s"[$sessionId] Process command")(Left(szorkError))
        Left(szorkError)
    }
  }

  /** Processes a command with real-time streaming of narrative text.
    *
    * Streams text chunks as they're generated for real-time display. Final response includes complete text and optional
    * media.
    *
    * @param command
    *   Player's text command
    * @param onTextChunk
    *   Callback for each narrative text chunk
    * @param generateAudio
    *   Whether to generate TTS narration after completion
    * @return
    *   Complete game response, or an error
    */
  def processCommandStreaming(
    command: String,
    onTextChunk: String => Unit,
    generateAudio: Boolean = true
  ): SzorkResult[GameResponse] = {
    logger.debug(s"[$sessionId] Processing command with streaming: $command")

    // Track user command in conversation history
    coreState = CoreEngine.trackUser(coreState, command)

    // Text of the current model call, and the parser extracting its narration. Both are reset
    // when a model call starts, so a retried attempt or the follow-up call after a tool
    // result starts clean. Tool calls produce no text deltas, so only answer text arrives here.
    // Events are delivered to the listener one at a time, and the run's await() returns only
    // once the listener has handled the last one.
    var textParser = new StreamingTextParser()
    var chunkCount = 0
    var narrativeChunkCount = 0

    val listener: StreamEvent => Unit = {
      case AgentEvents.ModelCallStarted(call) =>
        logger.debug(s"[$sessionId] Model call started (agent=${call.agent}, attempt=${call.attempt})")
        textParser = new StreamingTextParser()

      case AgentEvents.TextDelta(delta) =>
        chunkCount += 1
        logger.debug(s"[$sessionId] Received chunk #$chunkCount: ${delta.text.take(50)}...")

        // Try to extract narration text and stream it to the user
        textParser.processChunk(delta.text).foreach { narrationText =>
          narrativeChunkCount += 1
          logger.debug(s"[$sessionId] Streaming narrative chunk #$narrativeChunkCount: ${narrationText.take(50)}...")
          onTextChunk(narrationText)
        }

      case AgentEvents.ToolCallStarted(call) =>
        logger.info(s"[$sessionId] Tool call: ${call.tool} (id=${call.toolCallId}) arguments=${call.arguments}")

      case _ => ()
    }

    // Run the turn with streaming
    val textStartTime = System.currentTimeMillis()
    logger.info(s"[$sessionId] Starting streaming text generation for command: '$command'")

    runTurn(command, Some(listener)) match {
      case Right(result) =>
        logger.debug(
          s"[$sessionId] Streaming completed: $chunkCount chunks, $narrativeChunkCount narrative chunks in ${System
              .currentTimeMillis() - textStartTime}ms")
        val outcome = completeTurn(result)
        val audio = if (generateAudio) synthesize(outcome.narration) else None
        Right(GameResponse(outcome.narration, audio, None, None, None, outcome.scene.orElse(coreState.currentScene)))

      case Left(error) =>
        val szorkError = AIError(s"Error in streaming command: ${error.message}", llmError = Some(error))
        Logging.logError(s"[$sessionId] Streaming command")(Left(szorkError))
        Left(szorkError)
    }
  }

  /** What a completed turn produced: the narration to show, and the scene if the turn described one. */
  private final case class TurnOutcome(narration: String, scene: Option[GameScene])

  /** Interprets a completed turn's answer and applies it to the game state.
    *
    * The answer is parsed and validated; a scene moves the player, and the narration is recorded once in the
    * transcript. If the answer cannot be used as structured data, its narration is still shown.
    */
  private def completeTurn(result: AgentResult): TurnOutcome = {
    val answer = result.answer.getOrElse("")
    logger.debug(s"[$sessionId] Turn answer (truncated): ${answer.take(200)}")

    val interpreted = ResponseInterpreter.interpret(answer)
    lastValidationIssues = interpreted.issues
    interpreted.issues.foreach(issues => logger.warn(s"[$sessionId] Response issues: ${issues.mkString("; ")}"))

    val previousLocation = coreState.currentScene.map(_.locationId)
    val scene = interpreted.data.collect { case scene: GameScene => scene }
    scene.foreach(s => coreState = CoreEngine.applyScene(coreState, s))
    lastTurnChangedLocation = scene.exists(s => !previousLocation.contains(s.locationId))
    coreState = CoreEngine.trackAssistant(coreState, interpreted.narration)

    TurnOutcome(interpreted.narration, scene)
  }

  /** Narrates `text` with the configured text-to-speech client, if there is one. */
  private def synthesize(text: String): Option[String] =
    ttsClient.filter(_ => text.nonEmpty).flatMap { client =>
      val start = System.currentTimeMillis()
      client.synthesizeToBase64(text, GameEngine.NarrationVoice) match {
        case Right(audio) =>
          logger.info(s"[$sessionId] Audio generated in ${System.currentTimeMillis() - start}ms (${text.length} chars)")
          Some(audio)
        case Left(error) =>
          logger.error(s"[$sessionId] Failed to generate audio: ${error.message}")
          None
      }
    }

  /** Returns the current count of messages in the conversation history. */
  def getMessageCount: Int = history.length

  /** The items the player is carrying. */
  def getInventory: List[String] = inventory.list

  /** Returns the full conversation history (without the system prompt) as of the last completed turn. */
  def getMessages: Vector[Message] = history

  /** Builds the Dungeon Master agents for `systemPrompt`: one streaming, one not, sharing this engine's runtime. */
  private def buildAgents(systemPrompt: String): Result[GameEngine.DungeonMaster] = {
    val builder = Agent
      .builder(GameEngine.AgentId, llmClient)
      .withTools(toolRegistry)
      .withSystemPrompt(systemPrompt)
      .withRuntime(runtime)
    for {
      blocking <- builder.build()
      streaming <- builder.withStreaming().build()
    } yield GameEngine.DungeonMaster(blocking, streaming)
  }

  /** Replaces the conversation: forgets the current thread, and seeds the next turn's new thread with `seed`. */
  private def startNewConversation(seed: Vector[Message]): Unit = {
    forgetThread()
    history = seed
  }

  /** Removes the current thread from the runtime; the next turn starts a new one seeded with `history`. */
  private def forgetThread(): Unit = {
    for {
      id <- threadId
      dm <- agents.toOption
    } dm.blocking.forget(id).left.foreach { error =>
      logger.warn(s"[$sessionId] Could not forget agent thread ${id.value}: ${error.message}")
    }
    threadId = None
  }

  /** Runs one turn of the conversation, streaming events to `listener` when given.
    *
    * Continues the current thread, or starts a new one seeded with `history`. A completed turn updates `history` from
    * the thread. A failed turn leaves `history` as it was and drops the thread (llm4s keeps a failed turn's thread for
    * recovery, which the game does not use), so the player can simply try the command again.
    */
  private def runTurn(query: String, listener: Option[StreamEvent => Unit]): Result[AgentResult] =
    agents.flatMap { dm =>
      val (turnThread, seed) = threadId match {
        case Some(id) => (id, Nil)
        case None => (ThreadId(s"szork-${sessionId}-${java.util.UUID.randomUUID()}"), history)
      }
      val outcome = listener match {
        case None => dm.blocking.run(turnThread, query, RunConfig(), seed)
        case Some(l) => dm.streaming.stream(turnThread, query, RunConfig(), seed)(l).flatMap(_.await())
      }
      outcome match {
        case Right(result) =>
          val turnStart = history.length
          result.status match {
            case AgentStatus.Suspended(_, _, _, _, _) =>
              // The game configures no approvals or breakpoints, so a parked turn is unexpected.
              // Its history is mid-turn, so keep the last good history and drop the thread.
              threadId = Some(turnThread)
              forgetThread()
              Left(GameEngine.TurnNotCompleted("the turn was suspended"))
            case status =>
              // Completed, or ended at a limit or guardrail: either way the thread can take the next turn
              threadId = Some(result.threadId)
              history = result.messages
              lastTurnStart = turnStart
              status match {
                case AgentStatus.Completed(_) =>
                  logger.debug(
                    s"[$sessionId] Turn completed: ${result.messages.length} messages, usage=${result.usage}")
                  Right(result)
                case other =>
                  Left(GameEngine.TurnNotCompleted(s"the turn ended ${other.productPrefix}"))
              }
          }
        case Left(error) =>
          logger.warn(s"[$sessionId] Turn failed on thread ${turnThread.value}: ${error.message}")
          threadId = Some(turnThread)
          forgetThread()
          Left(error)
      }
    }

  /** Retrieves and clears any validation issues from the last response parsing.
    *
    * @return
    *   Validation issues if any occurred, None otherwise
    */
  def popValidationIssues(): Option[List[String]] = {
    val v = lastValidationIssues
    lastValidationIssues = None
    v
  }

  /** Determines if a scene image should be generated based on response text.
    *
    * @param responseText
    *   The narrative response text
    * @return
    *   true if image generation is appropriate
    */
  def shouldGenerateSceneImage(responseText: String): Boolean = {
    val _ = responseText
    lastTurnChangedLocation
  }

  /** Generates a scene image based on current location or response text.
    *
    * @param responseText
    *   Narrative text describing the scene
    * @param gameId
    *   Optional game ID for caching
    * @return
    *   Base64-encoded image, or None if generation fails
    */
  def generateSceneImage(responseText: String, gameId: Option[String] = None): Option[String] = {
    // Use detailed description from current scene if available
    val _ = responseText
    val (imagePrompt, locationId) = coreState.currentScene match {
      case Some(scene) => (scene.imageDescription, Some(scene.locationId))
      case None => return None
    }

    // Include art style prominently in the image prompt with detailed direction
    val styledPrompt = MediaPlanner.styledImagePrompt(artStyle, imagePrompt, artStyleDescription)
    logger.info(s"[$sessionId] Generating scene image with prompt: ${styledPrompt.take(100)}...")
    imageClient match {
      case Some(client) =>
        val artStyleForCache = artStyle.getOrElse("fantasy")
        client.generateScene(styledPrompt, artStyleForCache, gameId, locationId) match {
          case Right(image) =>
            logger.info(s"[$sessionId] Scene image generated/retrieved, base64: ${image.length}")
            Some(image)
          case Left(error) =>
            logger.error(s"[$sessionId] Failed to generate image: $error")
            None
        }
      case None =>
        logger.info(s"[$sessionId] Image client not configured; skipping image generation")
        None
    }
  }

  // isNewScene moved to CoreEngine

  // scene description extraction moved to MediaPlanner

  def shouldGenerateBackgroundMusic(responseText: String): Boolean = {
    val _ = responseText
    lastTurnChangedLocation
  }

  def generateBackgroundMusic(responseText: String, gameId: Option[String] = None): Option[(String, String)] =
    if (shouldGenerateBackgroundMusic(responseText)) {
      logger.info(s"[$sessionId] Checking if background music should be generated")
      try {
        // Check if music generation is available
        if (musicClient.isEmpty || !musicClient.get.isAvailable) {
          logger.info(s"[$sessionId] Music generation disabled or unavailable")
          return None
        }

        // Use structured mood and description if available
        val (mood, contextText, locationId) = coreState.currentScene match {
          case Some(scene) => (scene.musicMood, scene.musicDescription, Some(scene.locationId))
          case None => return None
        }

        logger.info(s"[$sessionId] Generating background music with mood: $mood")
        musicClient.get.generate(mood, contextText, gameId, locationId) match {
          case Right(musicBase64) =>
            logger.info(
              s"[$sessionId] Background music generated/retrieved for mood: $mood, base64: ${musicBase64.length}")
            Some((musicBase64, mood))
          case Left(error) =>
            logger.warn(s"[$sessionId] Music generation not available: $error")
            None
        }
      } catch {
        case e: Exception =>
          logger.warn(s"[$sessionId] Music generation disabled due to error: ${e.getMessage}")
          None
      }
    } else {
      None
    }

  // mood detection moved to MediaPlanner

  def getCurrentScene: Option[GameScene] = coreState.currentScene

  // Track media generation in cache
  def addMediaCacheEntry(locationId: String, entry: MediaCacheEntry): Unit = {
    mediaCache = mediaCache + (locationId -> entry)
    logger.debug(s"Added media cache entry for location: $locationId")
  }

  def updateMediaCacheImage(locationId: String, imagePrompt: String, imageCacheId: String): Unit = {
    val existing = mediaCache
      .get(locationId)
      .getOrElse(
        MediaCacheEntry(locationId, None, None, None, None, System.currentTimeMillis())
      )
    val updated = existing.copy(
      imagePrompt = Some(imagePrompt),
      imageCacheId = Some(imageCacheId),
      generatedAt = System.currentTimeMillis()
    )
    mediaCache = mediaCache + (locationId -> updated)
    logger.debug(s"Updated image cache for location: $locationId")
  }

  def updateMediaCacheMusic(locationId: String, musicPrompt: String, musicCacheId: String): Unit = {
    val existing = mediaCache
      .get(locationId)
      .getOrElse(
        MediaCacheEntry(locationId, None, None, None, None, System.currentTimeMillis())
      )
    val updated = existing.copy(
      musicPrompt = Some(musicPrompt),
      musicCacheId = Some(musicCacheId),
      generatedAt = System.currentTimeMillis()
    )
    mediaCache = mediaCache + (locationId -> updated)
    logger.debug(s"Updated music cache for location: $locationId")
  }

  /** Extracts complete game state for persistence.
    *
    * Captures conversation history, inventory, media cache, and agent state for save/load functionality.
    *
    * @param gameId
    *   Unique game identifier
    * @param gameTheme
    *   Theme information for the game
    * @param gameArtStyle
    *   Art style information for the game
    * @return
    *   Complete game state snapshot
    */
  def getGameState(gameId: String, gameTheme: Option[GameTheme], gameArtStyle: Option[ArtStyle]): GameState = {
    val currentSessionTime = clock.now() - sessionStartTime

    // Convert all agent messages to JSON for complete state persistence
    val agentMessagesJson = history.map(MessageCodec.toJson).toList

    GameState(
      gameId = gameId,
      theme = gameTheme,
      artStyle = gameArtStyle,
      adventureOutline = outline, // Include the full adventure outline
      currentScene = coreState.currentScene,
      visitedLocations = coreState.visitedLocations,
      conversationHistory = coreState.conversationHistory,
      inventory = inventory.list,
      createdAt = createdAt,
      lastSaved = clock.now(),
      lastPlayed = clock.now(),
      totalPlayTime = totalPlayTime + currentSessionTime,
      adventureTitle = adventureTitle,
      agentMessages = agentMessagesJson, // Complete agent state
      mediaCache = mediaCache, // Media cache entries
      systemPrompt = Some(systemPrompt)
    )
  }

  /** Restores game from a saved state snapshot.
    *
    * Reconstructs conversation history, inventory, and agent state from a previously saved game state.
    *
    * @param state
    *   Saved game state to restore
    */
  def restoreGameState(state: GameState): Unit = {
    // Restore simple state
    coreState = coreState.copy(
      currentScene = state.currentScene,
      visitedLocations = state.visitedLocations,
      conversationHistory = state.conversationHistory
    )
    inventory.replaceWith(state.inventory)
    totalPlayTime = state.totalPlayTime
    sessionStartTime = clock.now() // Reset session timer when restoring
    adventureTitle = state.adventureTitle
    mediaCache = state.mediaCache // Restore media cache
    // Note: currentStepNumber should be restored from GameMetadata by the caller

    // Restore the conversation. The agent holds the system prompt, so a save made with a
    // different prompt gets agents built for it; the saved messages then seed a new thread
    // when the next turn runs.
    if (state.agentMessages.nonEmpty) {
      // Convert JSON messages back to Message objects. The system prompt is the agent's,
      // so any system message in an older save is dropped.
      val messages = state.agentMessages
        .map(MessageCodec.fromJson)
        .filterNot(_.isInstanceOf[SystemMessage])
        .toVector

      // The Dungeon Master's prompt is rebuilt from the saved outline
      outline = state.adventureOutline
      systemPrompt = PromptBuilder.fullSystemPrompt(theme, artStyle, outline)
      agents = buildAgents(systemPrompt)
      restoreConversation(messages)
      logger.info(s"[$sessionId] Game state restored with ${messages.size} agent messages")
    } else if (state.conversationHistory.nonEmpty) {
      // Fallback to old restoration method for backwards compatibility
      val messages = state.conversationHistory.flatMap { entry =>
        entry.role match {
          case "user" => Some(UserMessage(content = entry.content))
          case "assistant" => Some(AssistantMessage(contentOpt = Some(entry.content)))
          case _ => None
        }
      }.toVector

      restoreConversation(messages)
      logger.info(
        s"[$sessionId] Game state restored (legacy) with ${coreState.conversationHistory.size} conversation entries")
    } else {
      // No history to restore, initialize normally
      initialize()
    }
  }

  /** Seeds the next turn with restored `messages`, checking they form a conversation llm4s will accept. */
  private def restoreConversation(messages: Vector[Message]): Unit = {
    Message.validateConversation(messages.toList).left.foreach { error =>
      logger.warn(s"[$sessionId] Restored conversation may be refused by the agent: ${error.message}")
    }
    startNewConversation(messages)
  }

  /** Returns the adventure title if set. */
  def getAdventureTitle: Option[String] = adventureTitle

  /** Sets the adventure title for this game. */
  def setAdventureTitle(title: String): Unit =
    adventureTitle = Some(title)

  // Step tracking methods for persistence

  /** Get current step number. */
  def getCurrentStepNumber: Int = currentStepNumber

  /** Increment step number and return the new value. */
  def incrementStepNumber(): Int = {
    currentStepNumber += 1
    currentStepNumber
  }

  /** Set step number (for restoration from saved games). */
  def setStepNumber(stepNumber: Int): Unit =
    currentStepNumber = stepNumber

  /** Create StepData from current engine state.
    *
    * This method extracts all necessary data for step-based persistence. The caller is responsible for saving via
    * StepPersistence.
    *
    * @param gameId
    *   Game identifier
    * @param gameTheme
    *   Optional game theme
    * @param gameArtStyle
    *   Optional art style
    * @param userCommand
    *   Optional user command that triggered this step
    * @param narrationText
    *   Narration text from the response
    * @param response
    *   Optional game response (scene or action)
    * @param executionTimeMs
    *   Time taken to execute the step
    * @return
    *   Complete step data ready for persistence
    */
  def createStepData(
    gameId: String,
    gameTheme: Option[GameTheme],
    gameArtStyle: Option[ArtStyle],
    userCommand: Option[String],
    narrationText: String,
    response: Option[GameResponse],
    executionTimeMs: Long
  ): StepData = {
    // Extract tool calls from agent messages
    val toolCalls = DebugHelpers.extractToolCalls(history.drop(lastTurnStart))

    // Convert response to GameResponse sealed trait
    val gameResponseOpt: Option[org.llm4s.szork.persistence.GameResponse] = response.flatMap { resp =>
      resp.scene.map(org.llm4s.szork.persistence.SceneResponse.apply)
    }

    val metadata = StepMetadata(
      gameId = gameId,
      stepNumber = currentStepNumber,
      timestamp = clock.now(),
      userCommand = userCommand,
      responseLength = narrationText.length,
      toolCallCount = toolCalls.length,
      messageCount = history.length,
      success = true,
      executionTimeMs = executionTimeMs
    )

    val gameState = getGameState(gameId, gameTheme, gameArtStyle)

    StepData(
      metadata = metadata,
      gameState = gameState,
      userCommand = userCommand,
      narrationText = narrationText,
      response = gameResponseOpt,
      toolCalls = toolCalls,
      agentMessages = history.toList,
      outline = if (currentStepNumber == 1) outline else None
    )
  }

}

/** Factory for creating GameEngine instances. */
object GameEngine {

  /** The text-to-speech voice used for narration. */
  val NarrationVoice: String = "nova"

  /** The id of the Dungeon Master agent. */
  val AgentId: String = "szork-dungeon-master"

  /** The Dungeon Master, compiled twice from the same settings: without and with streaming. */
  private final case class DungeonMaster(blocking: Agent, streaming: Agent)

  /** A turn that ran but did not complete with an answer (a step or token limit, a guardrail, a suspension). */
  final case class TurnNotCompleted(reason: String) extends LLMError {
    override def message: String = s"The Dungeon Master did not finish the turn: $reason"
  }

  /** Creates a new GameEngine with the specified configuration.
    *
    * @param llmClient
    *   LLM client for AI narrative generation
    * @param sessionId
    *   Unique session identifier
    * @param theme
    *   Optional theme for the adventure
    * @param artStyle
    *   Optional art style for images
    * @param adventureOutline
    *   Optional pre-generated adventure structure
    * @param clock
    *   Clock implementation for time tracking
    * @param ttsClient
    *   Optional text-to-speech client
    * @param imageClient
    *   Optional image generation client
    * @param musicClient
    *   Optional music generation client
    * @return
    *   Configured GameEngine instance
    */
  def create(
    llmClient: LLMClient,
    sessionId: String = "",
    theme: Option[String] = None,
    artStyle: Option[String] = None,
    adventureOutline: Option[AdventureOutline] = None,
    clock: Clock = SystemClock,
    ttsClient: Option[TTSClient] = None,
    imageClient: Option[ImageClient] = None,
    musicClient: Option[MusicClient] = None
  ): GameEngine =
    new GameEngine(sessionId, theme, artStyle, adventureOutline, clock, ttsClient, imageClient, musicClient)(llmClient)
}
