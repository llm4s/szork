package org.llm4s.szork.game

import org.llm4s.agent.{Agent, AgentResult, AgentStatus, ContextWindowConfig}
import org.llm4s.agent.events.{AgentEvents, SubagentEvents}
import org.llm4s.agent.graph.{GraphRuntime, RunConfig, StreamEvent, ThreadId}
import org.llm4s.agent.graph.middleware.{ContextWindowMiddleware, GuardrailMiddleware}
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Message, UserMessage, AssistantMessage, SystemMessage, ToolMessage}
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import org.llm4s.szork.core.{CoreEngine, CoreState}
import org.llm4s.szork.spi.{Clock, SystemClock, TTSClient, ImageClient, MusicClient}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.debug.DebugHelpers
import org.llm4s.szork.api.{GameTheme, ArtStyle}
import org.llm4s.szork.media.MediaPlanner
import org.llm4s.szork.persistence.{StepData, GameState, StepMetadata}
import org.llm4s.szork.streaming.StreamingJsonParser

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
  * @param tracing
  *   Optional tracing for the agent's runs
  * @param contextWindow
  *   How much of the conversation each model call is sent (None sends all of it); see [[GameEngine.contextWindowFor]]
  * @param inputGuardrails
  *   Guardrails each player command must pass before the Dungeon Master sees it (empty: none); a refused command is
  *   answered with [[PlayerInputGuardrail.Refusal]]
  * @param subagents
  *   Which subagents the Dungeon Master may delegate to (NPC dialogue, hints) and their client; see [[DmSubagents]]
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
  musicClient: Option[MusicClient] = None,
  tracing: Option[Tracing] = None,
  contextWindow: Option[ContextWindowConfig] = Some(GameEngine.DefaultContextWindow),
  inputGuardrails: Seq[InputGuardrail] = PlayerInputGuardrail.default,
  subagents: SubagentSettings = SubagentSettings.default
)(implicit llmClient: LLMClient) {
  private implicit val logger: org.slf4j.Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  private val themeDescription = PromptBuilder.themeDescription(theme)
  private val artStyleDescription = PromptBuilder.artStyleDescription(artStyle)
  // The adventure outline and the system prompt built from it. A restored game takes its outline from the save.
  // (volatile: a subagent's brief reads it on the runtime's thread)
  @volatile private var outline: Option[AdventureOutline] = adventureOutline
  private var systemPrompt: String = PromptBuilder.fullSystemPrompt(theme, artStyle, outline, subagents)

  // This game's inventory and world state, read and changed by the agent's tools. The world state outlives the
  // context window: what happened in turns the model is no longer sent is still there.
  private val inventory = new PlayerInventory()
  private val world = WorldState.seededFrom(outline)
  private val toolRegistry = new ToolRegistry(GameTools.forInventory(inventory) ++ GameTools.forWorld(world))
  // The subagents the Dungeon Master delegates to: their briefs read this game's outline, world and inventory
  private val subagentDefinitions = DmSubagents.forGame(subagents, () => outline, world, inventory)

  // The Dungeon Master is an llm4s Agent; the game's conversation is one of its threads.
  // Two agents are compiled from the same settings - one streaming, one not - over a shared
  // runtime: streaming is not part of the agent's graph version, so either can run the next
  // turn of the same thread.
  // The thread keeps the whole conversation; llm4s's ContextWindowMiddleware trims only what each
  // model call is sent, to this window fitted to the model.
  private val modelWindow: Option[ContextWindowConfig] = GameEngine.contextWindowFor(contextWindow, llmClient)
  modelWindow.foreach(w =>
    logger.debug(s"[$sessionId] Context window: maxTokens=${w.maxTokens}, maxMessages=${w.maxMessages}"))
  private val runtime: GraphRuntime = GraphRuntime.inMemory()
  private var agents: Result[GameEngine.DungeonMaster] = buildAgents(systemPrompt)

  // The thread holding the conversation, once a turn has run on it. After a restore (or a
  // failed turn) it is None, and the next turn starts a new thread seeded with `history`.
  private var threadId: Option[ThreadId] = None
  // The conversation as of the last successful turn: the thread's full history, without the
  // system prompt (the agent sends that on every model call).
  @volatile private var history: Vector[Message] = Vector.empty
  private var coreState: CoreState = CoreState()
  private var lastValidationIssues: Option[List[String]] = None
  // Whether the last turn moved the player to a new location (or opened the first scene);
  // scene images and location music are generated only then
  @volatile private var lastTurnChangedLocation: Boolean = false
  // Index into `history` where the last turn's messages begin
  private var lastTurnStart: Int = 0
  private val createdAt: Long = clock.now()
  private var sessionStartTime: Long = clock.now()
  private var totalPlayTime: Long = 0L // Accumulated play time from previous sessions
  private var adventureTitle: Option[String] = outline.map(_.title)

  // Enhanced state tracking
  private var currentStepNumber: Int = 1 // Current step number for persistence

  /** Initializes a new game session with opening scene generation.
    *
    * @return
    *   The opening scene narration text, or an error
    */
  def initialize(): SzorkResult[String] = {
    logger.info(s"[$sessionId] Initializing game with theme: $themeDescription")

    inventory.replaceWith(Nil)
    world.replaceWith(WorldSnapshot.fromOutline(outline))
    coreState = CoreState()

    // A new game is a new thread, opened with the init adventure prompt as its first turn
    // (the trigger message is not shown to the user)
    startNewConversation(Vector.empty)

    runTurn(GameConstants.Prompts.INIT_ADVENTURE, listener = None).flatMap(answered) match {
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

  /** Plays a player's command as one turn of the Dungeon Master.
    *
    * With `onTextChunk`, the turn streams: the narration is passed to it piece by piece as the model writes it (the
    * returned response has the complete, final narration). Without, the turn runs without streaming.
    *
    * @param command
    *   Player's text command
    * @param generateAudio
    *   Whether to narrate the response with text-to-speech
    * @param onTextChunk
    *   Receives each piece of narration as it streams, if the turn should stream
    * @return
    *   The narration, its audio and the current scene, or an error
    */
  def processCommand(
    command: String,
    generateAudio: Boolean = true,
    onTextChunk: Option[String => Unit] = None
  ): SzorkResult[GameResponse] = {
    logger.debug(s"[$sessionId] Processing command (streaming=${onTextChunk.isDefined}): $command")

    // Track user command in conversation history (pure core state)
    val stateBefore = coreState
    coreState = CoreEngine.trackUser(coreState, command)

    val textStartTime = System.currentTimeMillis()
    runTurn(command, onTextChunk.map(narrationListener)) match {
      case Right(result) if result.status.isInstanceOf[AgentStatus.Blocked] =>
        // An input guardrail refused the command before the model saw it: the game is as it was, and the Dungeon
        // Master answers in character
        coreState = stateBefore
        lastTurnChangedLocation = false
        lastValidationIssues = None
        val audio = if (generateAudio) synthesize(PlayerInputGuardrail.Refusal) else None
        Right(GameResponse(PlayerInputGuardrail.Refusal, audio, coreState.currentScene, refused = true))

      case Right(result) =>
        logger.debug(s"[$sessionId] Turn completed in ${System.currentTimeMillis() - textStartTime}ms")
        val outcome = completeTurn(result)
        val audio = if (generateAudio) synthesize(outcome.narration) else None
        Right(GameResponse(outcome.narration, audio, outcome.scene.orElse(coreState.currentScene), refused = false))

      case Left(error) =>
        val szorkError = AIError(s"Error processing command: ${error.message}", llmError = Some(error))
        Logging.logError(s"[$sessionId] Process command")(Left(szorkError))
        Left(szorkError)
    }
  }

  /** A listener for a streaming turn's events that passes the narration to `onTextChunk` as it streams.
    *
    * The narration is extracted from the JSON answer as it streams. The extractor is reset when a model call starts: a
    * tool call produces no text, and if llm4s asks the model to correct an invalid answer the corrected narration
    * streams again (the final narration, sent with streamComplete, is what the client keeps). Events reach the listener
    * one at a time, and the run's await() returns only once the listener has handled the last one.
    *
    * Only the Dungeon Master's own text streams. A subagent's events are not part of this subscription (the turn is
    * streamed without `nested`, and the subagents do not stream), so its words reach the player only through the
    * Dungeon Master's narration; a model call of any other agent is ignored all the same, so it cannot reset the
    * extractor mid-narration. The subagent's TaskStarted/TaskEnded events are logged.
    */
  private def narrationListener(onTextChunk: String => Unit): StreamEvent => Unit = {
    var textParser = new StreamingJsonParser()
    var narrativeChunkCount = 0

    {
      case AgentEvents.ModelCallStarted(call) if call.agent == GameEngine.AgentId =>
        logger.debug(s"[$sessionId] Model call started (agent=${call.agent}, attempt=${call.attempt})")
        textParser = new StreamingJsonParser()

      case AgentEvents.TextDelta(delta) =>
        textParser.processChunk(delta.text).foreach { narrationText =>
          narrativeChunkCount += 1
          logger.debug(s"[$sessionId] Streaming narrative chunk #$narrativeChunkCount: ${narrationText.take(50)}...")
          onTextChunk(narrationText)
        }

      case AgentEvents.ToolCallStarted(call) =>
        logger.info(s"[$sessionId] Tool call: ${call.tool} (id=${call.toolCallId}) arguments=${call.arguments}")

      case SubagentEvents.TaskStarted(started) =>
        logger.info(s"[$sessionId] Subagent ${started.child.agent.value} started")

      case SubagentEvents.TaskEnded(ended) =>
        logger.info(
          s"[$sessionId] Subagent ${ended.child.agent.value} ended ${ended.status} in ${ended.duration.toMillis}ms, " +
            s"usage=${ended.usage}")

      case _ => ()
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
    scene.foreach { s =>
      coreState = CoreEngine.applyScene(coreState, s)
      world.enter(s.locationId, s.locationName)
    }
    lastTurnChangedLocation = scene.exists(s => !previousLocation.contains(s.locationId))
    coreState = CoreEngine.trackAssistant(coreState, interpreted.narration)

    TurnOutcome(interpreted.narration, scene)
  }

  /** Narrates `text` with the configured text-to-speech client, if there is one. */
  def narrate(text: String): Option[String] = synthesize(text)

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

  /** The state of the game world: exits, location facts, characters met and quest progress. */
  def getWorldState: WorldSnapshot = world.snapshot

  /** Returns the full conversation history (without the system prompt) as of the last completed turn.
    *
    * This is the whole thread, not the pruned part a model call is sent; it is what a save stores.
    */
  def getMessages: Vector[Message] = history

  /** Builds the Dungeon Master agents for `systemPrompt`: one streaming, one not, sharing this engine's runtime.
    *
    * Each player command must pass the input guardrails (llm4s's `GuardrailMiddleware`); a refused command ends the
    * turn `AgentStatus.Blocked` without a model call, storing nothing of it.
    *
    * With a context window, each model call is sent the system prompt (which holds the adventure outline, and is never
    * pruned) and as much of the recent conversation as fits; the current turn is always sent whole, and a tool call is
    * never sent without its result.
    *
    * With subagents, the Dungeon Master is offered a `task_<id>` tool for each (llm4s delegation, bounded by
    * [[DmSubagents.Delegation]]); a task runs on a child thread of the turn's thread, in the same runtime, traced with
    * the Dungeon Master.
    */
  private def buildAgents(systemPrompt: String): Result[GameEngine.DungeonMaster] = {
    val agent = Agent
      .builder(GameEngine.AgentId, llmClient)
      .withTools(toolRegistry)
      .withSystemPrompt(systemPrompt)
      .withMaxSteps(GameEngine.MaxModelCallsPerTurn)
      .withOutput(DungeonMasterOutput.schema)
      .withRuntime(runtime)
    val base =
      if (subagentDefinitions.isEmpty) agent
      else agent.withSubagents(subagentDefinitions*).withDelegation(DmSubagents.Delegation)
    // The input guardrails check each turn's command before the agent starts (beforeAgent); the context window trims
    // what each model call is sent (wrapModelCall), so the two are independent
    val guarded =
      if (inputGuardrails.isEmpty) base else base.withMiddleware(new GuardrailMiddleware(inputGuardrails, Nil))
    val windowed = modelWindow.fold(guarded)(window => guarded.withMiddleware(new ContextWindowMiddleware(window)))
    val builder = tracing.fold(windowed)(windowed.withTracing)
    for {
      blocking <- builder.build()
      streaming <- builder.withStreaming().build()
    } yield {
      if (subagentDefinitions.nonEmpty) logger.debug(s"[$sessionId] ${blocking.delegation}")
      GameEngine.DungeonMaster(blocking, streaming)
    }
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
    * recovery, which the game does not use), so the player can simply try the command again. A turn an input guardrail
    * blocked is returned with its `Blocked` status and leaves `history` as it was; its thread stored nothing of it.
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
            case AgentStatus.Blocked(guardrail, reason) =>
              // The reason names the patterns that matched, never the command
              logger.warn(s"[$sessionId] Command refused by guardrail $guardrail: $reason")
              // A thread this turn opened holds no conversation yet; the next turn opens another, seeded with history
              if (threadId.isEmpty) {
                threadId = Some(result.threadId)
                forgetThread()
              }
              Right(result)
            case status =>
              // Completed, or ended at a step or token limit: either way the thread can take the next turn
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

  /** `result` if the turn was answered; a turn a guardrail blocked is an error. */
  private def answered(result: AgentResult): Result[AgentResult] = result.status match {
    case AgentStatus.Blocked(guardrail, _) => Left(GameEngine.TurnNotCompleted(s"guardrail $guardrail refused it"))
    case _ => Right(result)
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

  /** Whether the last turn moved the player to a new location (or opened the first scene). */
  def locationChangedLastTurn: Boolean = lastTurnChangedLocation

  /** Generates (or fetches from cache) the image for `scene`.
    *
    * Reads no mutable engine state, so it can run while the next turn is in progress.
    *
    * @return
    *   Base64-encoded image, or None if there is no image client or generation fails
    */
  def generateSceneImage(scene: GameScene, gameId: Option[String]): Option[String] =
    imageClient.flatMap { client =>
      val styledPrompt = MediaPlanner.styledImagePrompt(artStyle, scene.imageDescription, artStyleDescription)
      logger.info(s"[$sessionId] Generating scene image for ${scene.locationId}: ${styledPrompt.take(100)}...")
      client.generateScene(styledPrompt, artStyle.getOrElse("fantasy"), gameId, Some(scene.locationId)) match {
        case Right(image) => Some(image)
        case Left(error) =>
          logger.error(s"[$sessionId] Failed to generate image: ${error.message}")
          None
      }
    }

  /** Generates (or fetches from cache) background music for `scene`; returns the music and its mood.
    *
    * Reads no mutable engine state, so it can run while the next turn is in progress.
    */
  def generateBackgroundMusic(scene: GameScene, gameId: Option[String]): Option[(String, String)] =
    musicClient.filter(_.isAvailable).flatMap { client =>
      logger.info(s"[$sessionId] Generating music for ${scene.locationId}: mood=${scene.musicMood}")
      client.generate(scene.musicMood, scene.musicDescription, gameId, Some(scene.locationId)) match {
        case Right(music) => Some((music, scene.musicMood))
        case Left(error) =>
          logger.warn(s"[$sessionId] Music generation failed: ${error.message}")
          None
      }
    }

  def getCurrentScene: Option[GameScene] = coreState.currentScene

  /** Extracts complete game state for persistence.
    *
    * Captures the transcript, inventory, scene and the Dungeon Master's conversation for save/load.
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

    GameState(
      gameId = gameId,
      theme = gameTheme,
      artStyle = gameArtStyle,
      adventureOutline = outline, // Include the full adventure outline
      currentScene = coreState.currentScene,
      visitedLocations = coreState.visitedLocations,
      conversationHistory = coreState.conversationHistory,
      inventory = inventory.list,
      worldState = world.snapshot,
      createdAt = createdAt,
      lastSaved = clock.now(),
      lastPlayed = clock.now(),
      totalPlayTime = totalPlayTime + currentSessionTime,
      adventureTitle = adventureTitle,
      // The full thread: pruning applies only to what a model call is sent, so a restored game
      // prunes again from the whole conversation
      agentMessages = history.toList
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
    // A state without a world (made before it was kept) starts from the world its outline plans
    world.replaceWith(
      if (state.worldState == WorldSnapshot.empty) WorldSnapshot.fromOutline(state.adventureOutline)
      else state.worldState)
    totalPlayTime = state.totalPlayTime
    sessionStartTime = clock.now() // Reset session timer when restoring
    adventureTitle = state.adventureTitle
    // Note: currentStepNumber should be restored from GameMetadata by the caller

    // The Dungeon Master's prompt is rebuilt from the saved outline, and the saved conversation
    // seeds a new llm4s thread when the next turn runs.
    outline = state.adventureOutline
    systemPrompt = PromptBuilder.fullSystemPrompt(theme, artStyle, outline, subagents)
    agents = buildAgents(systemPrompt)
    restoreConversation(state.agentMessages.toVector)
    logger.info(s"[$sessionId] Game state restored with ${history.size} agent messages")
  }

  /** Seeds the next turn with restored `messages`.
    *
    * llm4s refuses to seed a thread with an invalid conversation - for example one saved mid-turn, ending in a tool
    * call that has no result - so such a history is cut back to its longest valid prefix.
    */
  private def restoreConversation(messages: Vector[Message]): Unit = {
    val valid = Iterator
      .iterate(messages)(_.dropRight(1))
      .takeWhile(_.nonEmpty)
      .find(prefix => Message.validateConversation(prefix.toList).isRight)
      .getOrElse(Vector.empty)
    if (valid.length < messages.length)
      logger.warn(
        s"[$sessionId] Dropped ${messages.length - valid.length} trailing messages to restore a valid conversation")
    startNewConversation(valid)
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
      outline = if (currentStepNumber == 1) outline else None
    )
  }

}

/** Factory for creating GameEngine instances. */
object GameEngine {

  /** Model calls allowed in one turn: enough for several tool calls (checking an exit, unlocking it, using up the key,
    * recording quest progress, delegating to a subagent), the answer and a correction of it, but not a runaway loop. A
    * subagent's own model call is limited by its task budget ([[DmSubagents.MaxStepsPerTask]]), not counted here.
    */
  val MaxModelCallsPerTurn: Int = 12

  /** The text-to-speech voice used for narration (SZORK_TTS_VOICE, default nova). */
  val NarrationVoice: String =
    org.llm4s.config.EnvLoader.get("SZORK_TTS_VOICE").map(_.trim).filter(_.nonEmpty).getOrElse("nova")

  /** Conversation tokens sent with each model call by default, besides the system prompt and the current turn. */
  val DefaultContextTokens: Int = 24000

  /** The default context window: the most recent conversation that fits in [[DefaultContextTokens]]. */
  val DefaultContextWindow: ContextWindowConfig = ContextWindowConfig(maxTokens = Some(DefaultContextTokens))

  /** `window` fitted to `llmClient`'s model: a token limit is capped at half the model's context window less its
    * completion reserve, leaving the other half for the system prompt (with the adventure outline), the tool and output
    * schemas and the current turn. A model reporting no context window leaves the limit as configured.
    */
  def contextWindowFor(window: Option[ContextWindowConfig], llmClient: LLMClient): Option[ContextWindowConfig] =
    window.map { w =>
      val modelLimit = (llmClient.getContextWindow() - llmClient.getReserveCompletion()) / 2
      if (modelLimit > 0) w.copy(maxTokens = w.maxTokens.map(math.min(_, modelLimit))) else w
    }

  /** The id of the Dungeon Master agent. */
  val AgentId: String = "szork-dungeon-master"

  /** The Dungeon Master, compiled twice from the same settings: without and with streaming. */
  private final case class DungeonMaster(blocking: Agent, streaming: Agent)

  /** A turn that ran but did not complete with an answer (a step or token limit, a suspension, or a guardrail where a
    * refusal is not expected).
    */
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
    * @param tracing
    *   Optional tracing for the agent's runs
    * @param contextWindow
    *   How much of the conversation each model call is sent (None sends all of it)
    * @param inputGuardrails
    *   Guardrails each player command must pass (empty: none)
    * @param subagents
    *   The subagents the Dungeon Master may delegate to
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
    musicClient: Option[MusicClient] = None,
    tracing: Option[Tracing] = None,
    contextWindow: Option[ContextWindowConfig] = Some(DefaultContextWindow),
    inputGuardrails: Seq[InputGuardrail] = PlayerInputGuardrail.default,
    subagents: SubagentSettings = SubagentSettings.default
  ): GameEngine =
    new GameEngine(
      sessionId,
      theme,
      artStyle,
      adventureOutline,
      clock,
      ttsClient,
      imageClient,
      musicClient,
      tracing,
      contextWindow,
      inputGuardrails,
      subagents)(using llmClient)
}
