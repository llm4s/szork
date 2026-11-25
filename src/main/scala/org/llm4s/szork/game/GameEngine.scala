package org.llm4s.szork.game

import org.llm4s.agent.{Agent, AgentResult, AgentStatus, ContextWindowConfig}
import org.llm4s.agent.events.{AgentEvents, SubagentEvents}
import org.llm4s.agent.graph.{GraphRuntime, RunConfig, StreamEvent, ThreadId}
import org.llm4s.agent.graph.middleware.{ContextWindowMiddleware, GuardrailMiddleware}
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{CompletionOptions, Message, ReasoningEffort, UsageSummary}
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import org.llm4s.szork.core.{CoreEngine, CoreState}
import org.llm4s.szork.spi.{Clock, SystemClock, TTSClient, ImageClient, MusicClient}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.api.{GameTheme, ArtStyle, SpeechConfig}
import org.llm4s.szork.adventures.AdventureLibrary
import org.llm4s.szork.media.MediaRef
import org.llm4s.szork.persistence.{GameState, SceneResponse, StepData, StepMetadata, ToolCallInfo}
import org.llm4s.szork.streaming.StreamingJsonParser

/** Core game engine managing AI-driven text adventure gameplay.
  *
  * Plays the Dungeon Master's turns (streaming or not) and keeps the game's state - its scene, inventory and world -
  * which a turn changes only when it completes; makes the scenes' media through [[SceneMedia]]; and gives and takes the
  * state a save holds. It does not save: the server stores what [[getGameState]] and [[createStepData]] return.
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
  * @param narrationVoice
  *   The text-to-speech voice the narration is spoken in
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
  * @param reasoning
  *   How hard the Dungeon Master and its subagents reason before they answer, on models that reason (None: the model's
  *   default); see [[GameEngine.completionOptions]]
  * @param library
  *   The adventure library, which keeps the pictures of a library adventure's locations and one music track per mood
  *   (None: every picture and track is made for this game alone); see [[SceneMedia]]
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
  narrationVoice: String = SpeechConfig.DefaultVoice,
  imageClient: Option[ImageClient] = None,
  musicClient: Option[MusicClient] = None,
  tracing: Option[Tracing] = None,
  contextWindow: Option[ContextWindowConfig] = Some(GameEngine.DefaultContextWindow),
  inputGuardrails: Seq[InputGuardrail] = PlayerInputGuardrail.default,
  subagents: SubagentSettings = SubagentSettings.default,
  reasoning: Option[ReasoningEffort] = None,
  library: Option[AdventureLibrary] = None
)(implicit llmClient: LLMClient) {
  private implicit val logger: org.slf4j.Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  private val themeDescription = PromptBuilder.themeDescription(theme)
  // The adventure outline and the system prompt built from it. A restored game takes its outline from the save.
  // (volatile: a subagent's brief reads it on the runtime's thread)
  @volatile private var outline: Option[AdventureOutline] = adventureOutline

  // The library adventure this game plays, if it plays one: its locations' pictures are the library's
  @volatile private var adventureId: Option[String] = None

  private val media = new SceneMedia(
    sessionId,
    artStyle,
    ttsClient,
    narrationVoice,
    imageClient,
    musicClient,
    library,
    () => adventureId,
    id => outline.flatMap(_.keyLocations.find(_.id == id))
  )

  // This game's inventory and world state, read and changed by the agent's tools. The world state outlives the
  // context window: what happened in turns the model is no longer sent is still there.
  private val inventory = new PlayerInventory()
  private val world = WorldState.seededFrom(outline)
  private val toolRegistry: Result[ToolRegistry] = for {
    inventoryTools <- GameTools.forInventory(inventory)
    worldTools <- GameTools.forWorld(world)
    registry <- ToolRegistry.of(inventoryTools ++ worldTools)
  } yield registry
  // What every model call of this game asks for besides its messages
  private val modelOptions = GameEngine.completionOptions(reasoning)
  // The subagents the Dungeon Master delegates to: their briefs read this game's outline, world and inventory
  private val subagentDefinitions =
    DmSubagents.forGame(subagents, () => outline, world, inventory, reasoning.map(_ => llmClient -> modelOptions))

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
  // The conversation with the Dungeon Master: its llm4s thread, history and usage
  private val conversation =
    new DmConversation(
      sessionId,
      buildAgents(PromptBuilder.fullSystemPrompt(theme, artStyle, outline, subagents)),
      clock)

  private var coreState: CoreState = CoreState()
  // What the last turn did, for the server to act on: reset when a turn starts and when a game is restored
  private var lastValidationIssues: Option[List[String]] = None
  // Whether the last turn moved the player to a new location (or opened the first scene);
  // scene images and location music are generated only then
  @volatile private var lastTurnChangedLocation: Boolean = false
  // How the player's location looks now, when the last turn's answer said its picture is out of date
  @volatile private var lastTurnImageUpdate: Option[String] = None
  private var createdAt: Long = clock.now()
  private var sessionStartTime: Long = clock.now()
  private var totalPlayTime: Long = 0L // Accumulated play time from previous sessions
  private var adventureTitle: Option[String] = outline.map(_.title)

  // The number of the step the next save is stored as
  private var currentStepNumber: Int = 1

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
    conversation.startOver(Vector.empty)

    playTurn(GameConstants.Prompts.INIT_ADVENTURE, listener = None).flatMap(DmConversation.answered) match {
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

    playTurn(command, onTextChunk.map(narrationListener)) match {
      case Right(result) =>
        result.status match {
          case AgentStatus.Blocked(_, _) =>
            // An input guardrail refused the command before the model saw it: the game is as it was, and the Dungeon
            // Master answers in character
            val audio = if (generateAudio) media.narrate(PlayerInputGuardrail.Refusal) else None
            Right(GameResponse(PlayerInputGuardrail.Refusal, audio, coreState.currentScene, refused = true))
          case _ =>
            val outcome = completeTurn(result)
            val audio = if (generateAudio) media.narrate(outcome.narration) else None
            Right(GameResponse(outcome.narration, audio, outcome.scene.orElse(coreState.currentScene), refused = false))
        }

      case Left(error) =>
        val szorkError = AIError(s"Error processing command: ${error.message}", llmError = Some(error))
        Logging.logError(s"[$sessionId] Process command")(Left(szorkError))
        Left(szorkError)
    }
  }

  /** Runs one turn of the Dungeon Master, as a whole or not at all.
    *
    * The turn's tools change the inventory and world as they run (a later call of the turn reads the change). A turn
    * that does not complete - it fails, ends at a step or output limit, or its answer would move the player through an
    * exit that is not open - is an error and leaves the game as it was: [[DmConversation]] keeps the history as it was,
    * and the inventory and world are put back as they were before the turn. A turn a guardrail blocked ran no tool. The
    * last turn's results ([[locationChangedLastTurn]] and the like) are reset as the turn starts.
    *
    * [[ExitGuardMiddleware]] has held every answer of the turn to the exit rule, with the same reading of the answer,
    * against the world as it stood; an answer that still breaks it here means the two disagree, and the turn is
    * discarded rather than leave the thread telling of a move the game did not make.
    */
  private def playTurn(command: String, listener: Option[StreamEvent => Unit]): Result[AgentResult] = {
    resetLastTurn()
    val (worldBefore, inventoryBefore) = (world.snapshot, inventory.list)
    val outcome = conversation.runTurn(command, listener).flatMap { result =>
      val move = result.status match {
        case AgentStatus.Completed(_) =>
          ResponseInterpreter
            .interpret(result.answer.getOrElse(""))
            .data
            .collect { case scene: GameScene => scene }
            .flatMap(scene => ExitGuard.blockedMove(world.snapshot, scene.locationId))
        case _ => None
      }
      move match {
        case None => Right(result)
        case Some(refused) =>
          logger.error(
            s"[$sessionId] The turn's answer moves the player from ${refused.from} to ${refused.to}, through an exit " +
              "that is not open, past the exit guard: the turn is discarded")
          conversation.discardLastTurn()
          Left(GameEngine.TurnNotCompleted(s"its answer moves the player to ${refused.to}, and no way there is open"))
      }
    }
    if (outcome.isLeft) {
      world.replaceWith(worldBefore)
      inventory.replaceWith(inventoryBefore)
    }
    outcome
  }

  /** Forgets what the last turn did. */
  private def resetLastTurn(): Unit = {
    lastTurnChangedLocation = false
    lastTurnImageUpdate = None
    lastValidationIssues = None
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
    * The answer is parsed and validated, and a scene moves the player ([[playTurn]] has refused a turn whose scene goes
    * through an exit that is not open). If the answer cannot be used as structured data, its narration is still shown.
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
      world.enter(s.locationId, s.locationName, s.exits.map(e => (e.direction, e.locationId)))
    }
    // An action that is not a scene may still change what is here (an item taken, a character appearing): the
    // current scene, which every response carries to the player, is kept up to date with it
    interpreted.data.collect { case simple: SimpleResponse => simple }.foreach { simple =>
      coreState.currentScene.filter(_.locationId == simple.locationId).foreach { current =>
        val updated =
          current.copy(items = simple.items.getOrElse(current.items), npcs = simple.npcs.getOrElse(current.npcs))
        if (updated != current) coreState = coreState.copy(currentScene = Some(updated))
      }
    }
    // What the player carries is not lying in the scene: a model often lists an item there on the turn it is taken
    coreState.currentScene.foreach { current =>
      val carried = inventory.list.map(_.trim.toLowerCase).toSet
      val lying = current.items.filterNot(item => carried(item.trim.toLowerCase))
      if (lying != current.items) coreState = coreState.copy(currentScene = Some(current.copy(items = lying)))
    }
    lastTurnChangedLocation = scene.exists(s => !previousLocation.contains(s.locationId))
    lastTurnImageUpdate = interpreted.imageUpdate

    TurnOutcome(interpreted.narration, scene.flatMap(_ => coreState.currentScene))
  }

  /** Narrates `text` with the configured text-to-speech client, if there is one. */
  def narrate(text: String): Option[String] = media.narrate(text)

  /** The picture of `scene`'s location: the one it was first shown with, or with `update` (how the place looks now,
    * from [[imageUpdateLastTurn]]) a new one. None when there is no picture and none can be made. It can run while the
    * next turn is in progress. See [[SceneMedia.image]].
    */
  def generateSceneImage(scene: GameScene, gameId: Option[String], update: Option[String] = None): Option[MediaRef] =
    media.image(scene, gameId, update)

  /** The picture a location already has, for showing a reloaded game's transcript. See [[SceneMedia.pictureOf]]. */
  def pictureOf(locationId: String, gameId: Option[String]): Option[MediaRef] = media.pictureOf(locationId, gameId)

  /** Whether [[generateSceneImage]] can be expected to produce a picture. */
  def canPicture(scene: GameScene, update: Option[String] = None): Boolean = media.canPicture(scene, update)

  /** Background music for `scene` (the track for its mood) and its mood. It can run while the next turn is in progress.
    */
  def generateBackgroundMusic(scene: GameScene, gameId: Option[String]): Option[(MediaRef, String)] =
    media.music(scene)

  /** Whether [[generateBackgroundMusic]] can be expected to produce a track. */
  def canPlayMusic(scene: GameScene): Boolean = media.canPlay(scene)

  /** Says this game plays the library adventure `id`: its locations' pictures are kept in, and come from, the library.
    */
  def playAdventure(id: String): Unit = adventureId = Some(id)

  /** The library adventure this game plays, if it plays one. */
  def adventure: Option[String] = adventureId

  /** Returns the current count of messages in the conversation history. */
  def getMessageCount: Int = conversation.history.length

  /** The items the player is carrying. */
  def getInventory: List[String] = inventory.list

  /** How the adventure ended, if it has: "completed" or "failed", the main quest's status once the Dungeon Master
    * records either with `update_quest_progress`. The player may play on after it.
    */
  def ending: Option[String] =
    world.snapshot.quests.find(_.id == WorldState.MainQuest).map(_.status).collect {
      case QuestStatus.Completed => QuestStatus.Completed.wire
      case QuestStatus.Failed => QuestStatus.Failed.wire
    }

  /** The state of the game world: exits, location facts, characters met and quest progress. */
  def getWorldState: WorldSnapshot = world.snapshot

  /** Returns the full conversation history (without the system prompt) as of the last completed turn.
    *
    * This is the whole thread, not the pruned part a model call is sent; it is what a save stores.
    */
  def getMessages: Vector[Message] = conversation.history

  /** The transcript shown to the player, derived from the conversation (see [[Transcript]]). */
  def getTranscript: List[Transcript.Entry] = Transcript.of(conversation.history)

  /** Builds the Dungeon Master agents for `systemPrompt`: one streaming, one not, sharing this engine's runtime.
    *
    * Each player command must pass the input guardrails (llm4s's `GuardrailMiddleware`); a refused command ends the
    * turn `AgentStatus.Blocked` without a model call, storing nothing of it.
    *
    * Each model call's system prompt ends with the current game state ([[WorldStateMiddleware]]): rendered for that
    * call, after any tool calls earlier in the turn, and never stored in the thread.
    *
    * Each answer is held to the exit states ([[ExitGuardMiddleware]]): a scene that would move the player through an
    * exit that is not open is sent back to the model once, with what is in the way, and never reaches the thread.
    *
    * A tool call repeated with nothing changed in between is not run again ([[RepeatedToolCallMiddleware]]), and after
    * a few rounds of tool calls the model is offered none ([[ToolRoundsMiddleware]]).
    *
    * With a context window, each model call is sent the system prompt (which holds the adventure outline and the game
    * state, and is never pruned) and as much of the recent conversation as fits; the current turn is always sent whole,
    * and a tool call is never sent without its result.
    *
    * With subagents, the Dungeon Master is offered a `task_<id>` tool for each (llm4s delegation, bounded by
    * [[DmSubagents.Delegation]]); a task runs on a child thread of the turn's thread, in the same runtime, traced with
    * the Dungeon Master.
    */
  private def buildAgents(systemPrompt: String): Result[DmConversation.Agents] =
    toolRegistry.flatMap(registry => buildAgents(systemPrompt, registry))

  private def buildAgents(systemPrompt: String, registry: ToolRegistry): Result[DmConversation.Agents] = {
    val agent = Agent
      .builder(GameEngine.AgentId, llmClient)
      .withTools(registry)
      .withSystemPrompt(systemPrompt)
      .withMaxSteps(GameEngine.MaxModelCallsPerTurn)
      .withCompletionOptions(modelOptions)
      .withOutput(DungeonMasterOutput.schema)
      .withRuntime(runtime)
    val base =
      if (subagentDefinitions.isEmpty) agent
      else agent.withSubagents(subagentDefinitions*).withDelegation(DmSubagents.Delegation)
    // The input guardrails check each turn's command before the agent starts (beforeAgent); the context window trims
    // what each model call is sent (wrapModelCall), so the two are independent
    val guarded =
      if (inputGuardrails.isEmpty) base else base.withMiddleware(new GuardrailMiddleware(inputGuardrails, Nil))
    // llm4s nests wrapModelCall and wrapToolCall in the order middleware are added, the first outermost
    val stated =
      guarded.withMiddleware(
        // The current game state joins the system prompt of every model call (a prompt section, never stored), so
        // the model reads the location, inventory and quests there instead of calling the read tools
        new WorldStateMiddleware(world, inventory),
        // The tool calls of a turn that keeps looking things up instead of answering are not run. It wraps the exit
        // guard, so the guard's corrective request is part of the round it corrects, not another one
        new ToolRoundsMiddleware,
        // An answer that would move the player through an exit that is not open goes back to the model once, with
        // what is in the way; it wraps the context window, so its corrective request is trimmed like any other
        new ExitGuardMiddleware(world),
        // A tool call the model repeats with nothing changed in between is not run again, and it is told to answer
        new RepeatedToolCallMiddleware
      )
    val windowed = modelWindow.fold(stated)(window => stated.withMiddleware(new ContextWindowMiddleware(window)))
    val builder = tracing.fold(windowed)(windowed.withTracing)
    for {
      blocking <- builder.build()
      streaming <- builder.withStreaming().build()
    } yield {
      if (subagentDefinitions.nonEmpty) logger.debug(s"[$sessionId] ${blocking.delegation}")
      DmConversation.Agents(blocking, streaming)
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

  /** The model calls, tokens and time the last completed turn took, from the usage llm4s counts on the turn's thread.
    */
  def lastTurnUsage: Option[GameEngine.TurnUsage] = conversation.lastTurnUsage

  /** Whether the last turn moved the player to a new location (or opened the first scene). */
  def locationChangedLastTurn: Boolean = lastTurnChangedLocation

  /** How the player's location looks now, when the Dungeon Master's last answer said its picture is out of date. */
  def imageUpdateLastTurn: Option[String] = lastTurnImageUpdate

  def getCurrentScene: Option[GameScene] = coreState.currentScene

  /** Extracts complete game state for persistence.
    *
    * Captures the inventory, world, scene and the Dungeon Master's conversation (from which the transcript is derived)
    * for save/load.
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
      inventory = inventory.list,
      worldState = world.snapshot,
      createdAt = createdAt,
      lastSaved = clock.now(),
      lastPlayed = clock.now(),
      totalPlayTime = totalPlayTime + currentSessionTime,
      adventureTitle = adventureTitle,
      adventureId = adventureId,
      // The full thread: pruning applies only to what a model call is sent, so a restored game
      // prunes again from the whole conversation
      agentMessages = conversation.history.toList
    )
  }

  /** Restores game from a saved state snapshot.
    *
    * Reconstructs conversation history, inventory, world and agent state from a previously saved game state. The step
    * number is not in it: the caller sets it with [[setStepNumber]].
    *
    * @param state
    *   Saved game state to restore
    */
  def restoreGameState(state: GameState): Unit = {
    coreState = coreState.copy(currentScene = state.currentScene, visitedLocations = state.visitedLocations)
    inventory.replaceWith(state.inventory)
    world.replaceWith(state.worldState)
    createdAt = state.createdAt
    totalPlayTime = state.totalPlayTime
    sessionStartTime = clock.now() // Reset session timer when restoring
    adventureTitle = state.adventureTitle
    adventureId = state.adventureId
    // There is no last turn yet (the step number is the caller's to set, from the save's metadata: setStepNumber)
    resetLastTurn()

    // The Dungeon Master's prompt is rebuilt from the saved outline, and the saved conversation
    // seeds a new llm4s thread when the next turn runs.
    outline = state.adventureOutline
    conversation.rebuild(buildAgents(PromptBuilder.fullSystemPrompt(theme, artStyle, outline, subagents)))
    conversation.restore(state.agentMessages.toVector)
    logger.info(s"[$sessionId] Game state restored with ${conversation.history.size} agent messages")
  }

  // Step tracking methods for persistence

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
    val toolCalls = ToolCallInfo.fromMessages(conversation.lastTurnMessages)

    val metadata = StepMetadata(
      gameId = gameId,
      stepNumber = currentStepNumber,
      timestamp = clock.now(),
      userCommand = userCommand,
      responseLength = narrationText.length,
      toolCallCount = toolCalls.length,
      messageCount = conversation.history.length,
      success = true,
      executionTimeMs = executionTimeMs
    )

    val gameState = getGameState(gameId, gameTheme, gameArtStyle)

    StepData(
      metadata = metadata,
      gameState = gameState,
      userCommand = userCommand,
      narrationText = narrationText,
      response = response.flatMap(_.scene).map(SceneResponse.apply),
      toolCalls = toolCalls,
      outline = if (currentStepNumber == 1) outline else None
    )
  }

}

/** The Dungeon Master's limits and settings, and what a turn reports. */
object GameEngine {

  /** Model calls allowed in one turn: enough for several tool calls (checking an exit, unlocking it, using up the key,
    * recording quest progress, delegating to a subagent), the answer and a correction of it, but not a runaway loop. A
    * subagent's own model call is limited by its task budget ([[DmSubagents.MaxStepsPerTask]]), not counted here.
    */
  val MaxModelCallsPerTurn: Int = 12

  /** The most tokens one Dungeon Master model call may answer with. A model that reasons spends them on its reasoning
    * too, so this is several times the longest scene; llm4s's default (2048 on Anthropic) is not enough for one.
    */
  val MaxOutputTokens: Int = 8000

  /** The options of a Dungeon Master model call: room for the answer, and `reasoning` when given. On a model that does
    * not reason the effort is not sent. Without one, a model that always reasons does so at its own default, which for
    * the Claude 5 models is high: slow, and long enough to use up the whole answer.
    */
  def completionOptions(reasoning: Option[ReasoningEffort]): CompletionOptions =
    CompletionOptions().withMaxTokens(MaxOutputTokens).withReasoning(reasoning)

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
      val modelLimit = (llmClient.contextWindow - llmClient.reserveCompletion) / 2
      if (modelLimit > 0) w.withMaxTokens(w.maxTokens.map(math.min(_, modelLimit))) else w
    }

  /** What one turn of the Dungeon Master cost: its model calls, their input and output tokens, and its time. */
  final case class TurnUsage(modelCalls: Long, inputTokens: Long, outputTokens: Long, durationMs: Long) {
    override def toString: String =
      s"$modelCalls model call(s), $inputTokens input + $outputTokens output tokens, ${durationMs}ms"
  }

  object TurnUsage {

    /** A turn's usage as llm4s reports it (`AgentResult.runUsage`), with the time it took. */
    def apply(usage: UsageSummary, durationMs: Long): TurnUsage =
      TurnUsage(usage.requestCount, usage.inputTokens, usage.outputTokens, durationMs)
  }

  /** A turn that ran but did not complete with an answer (a step or token limit, a suspension, or a guardrail where a
    * refusal is not expected).
    */
  final case class TurnNotCompleted(reason: String) extends LLMError {
    override def message: String = s"The Dungeon Master did not finish the turn: $reason"
  }

  /** The id of the Dungeon Master agent. */
  val AgentId: String = "szork-dungeon-master"

  /** A new engine: the constructor, with the LLM client passed as the first argument rather than implicitly. The
    * parameters are the constructor's ([[GameEngine]]).
    */
  def create(
    llmClient: LLMClient,
    sessionId: String = "",
    theme: Option[String] = None,
    artStyle: Option[String] = None,
    adventureOutline: Option[AdventureOutline] = None,
    clock: Clock = SystemClock,
    ttsClient: Option[TTSClient] = None,
    narrationVoice: String = SpeechConfig.DefaultVoice,
    imageClient: Option[ImageClient] = None,
    musicClient: Option[MusicClient] = None,
    tracing: Option[Tracing] = None,
    contextWindow: Option[ContextWindowConfig] = Some(DefaultContextWindow),
    inputGuardrails: Seq[InputGuardrail] = PlayerInputGuardrail.default,
    subagents: SubagentSettings = SubagentSettings.default,
    reasoning: Option[ReasoningEffort] = None,
    library: Option[AdventureLibrary] = None
  ): GameEngine =
    new GameEngine(
      sessionId,
      theme,
      artStyle,
      adventureOutline,
      clock,
      ttsClient,
      narrationVoice,
      imageClient,
      musicClient,
      tracing,
      contextWindow,
      inputGuardrails,
      subagents,
      reasoning,
      library
    )(using llmClient)
}
