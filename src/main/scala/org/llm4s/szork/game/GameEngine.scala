package org.llm4s.szork.game

import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Message, UserMessage, AssistantMessage, SystemMessage, ToolMessage, ToolCall}
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory
import org.llm4s.szork.core.{CoreEngine, CoreState}
import org.llm4s.szork.spi.{Clock, SystemClock, TTSClient, ImageClient, MusicClient}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.debug.DebugHelpers
import org.llm4s.szork.api.{GameTheme, ArtStyle}
import org.llm4s.szork.media.{MediaPlanner, TextToSpeech}
import org.llm4s.szork.persistence.{StepPersistence, StepData, GameState, MediaCacheEntry, SceneResponse, StepMetadata, GameResponse}
import org.llm4s.szork.streaming.{StreamingAgent, StreamingTextParser}
import org.llm4s.trace.{EnhancedTracing, EnhancedNoOpTracing, EnhancedLangfuseTracing, Tracing, TracingMode}
import org.llm4s.llmconnect.config.{TracingSettings, LangfuseConfig}
import io.github.cdimascio.dotenv.Dotenv

/** Core game engine managing AI-driven text adventure gameplay.
  *
  * Orchestrates LLM agent interactions, game state management, media generation,
  * and persistence. Handles both streaming and non-streaming gameplay modes.
  *
  * @param sessionId Unique identifier for this game session
  * @param theme Optional theme name for the adventure (e.g., "fantasy", "sci-fi")
  * @param artStyle Optional visual art style for generated images
  * @param adventureOutline Optional pre-generated adventure structure with locations and characters
  * @param clock Clock implementation for time tracking (defaults to system time)
  * @param ttsClient Optional text-to-speech client for narration
  * @param imageClient Optional client for scene image generation
  * @param musicClient Optional client for background music generation
  * @param llmClient LLM client for AI narrative generation (implicit)
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
  private implicit val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  // Initialize Langfuse tracing from environment variables
  // Fixed in llm4s 0.1.16+088f6b923-SNAPSHOT with correct URL endpoint
  private val tracer: Tracing = {
    val langfuseUrl = sys.env.getOrElse("LANGFUSE_HOST", "https://cloud.langfuse.com")
    val publicKey = sys.env.getOrElse("LANGFUSE_PUBLIC_KEY", "")
    val secretKey = sys.env.getOrElse("LANGFUSE_SECRET_KEY", "")

    if (publicKey.nonEmpty && secretKey.nonEmpty) {
      logger.info(s"[$sessionId] Langfuse tracing enabled: $langfuseUrl")
      val enhancedTracing = new EnhancedLangfuseTracing(
        langfuseUrl = langfuseUrl,
        publicKey = publicKey,
        secretKey = secretKey,
        environment = "development",
        release = "1.0.0",
        version = "1.0.0"
      )
      Tracing.createFromEnhanced(enhancedTracing)
    } else {
      logger.info(s"[$sessionId] Langfuse keys not found, using no-op tracer")
      Tracing.createFromEnhanced(new EnhancedNoOpTracing())
    }
  }

  private val themeDescription = PromptBuilder.themeDescription(theme)
  private val artStyleDescription = PromptBuilder.artStyleDescription(artStyle)
  private val completeSystemPrompt: String = PromptBuilder.fullSystemPrompt(theme, artStyle, adventureOutline)
  private val toolRegistry = new ToolRegistry(GameTools.allTools)
  private val agent = new Agent(llmClient)

  private var currentState: AgentState = _
  private var coreState: CoreState = CoreState()
  private var lastValidationIssues: Option[List[String]] = None
  private val createdAt: Long = clock.now()
  private var sessionStartTime: Long = clock.now()
  private var totalPlayTime: Long = 0L // Accumulated play time from previous sessions
  private var adventureTitle: Option[String] = adventureOutline.map(_.title)

  // Enhanced state tracking
  private var mediaCache: Map[String, MediaCacheEntry] = Map.empty
  private var currentStepNumber: Int = 1 // Current step number for persistence

  /** Initializes a new game session with opening scene generation.
    *
    * @return The opening scene narration text, or an error
    */
  def initialize(): SzorkResult[String] = {
    logger.info(s"[$sessionId] Initializing game with theme: $themeDescription")

    // Clear inventory for new game
    GameTools.clearInventory()

    // Use init adventure prompt as the trigger message (not shown to user)
    val initPrompt = GameConstants.Prompts.INIT_ADVENTURE

    // Initialize the agent with the complete system prompt including adventure outline
    currentState = agent.initialize(
      initPrompt,
      toolRegistry,
      systemPromptAddition = Some(completeSystemPrompt)
    )

    // Track the initialization message (but don't show to user)
    // This ensures the agent knows to generate the opening scene

    // Automatically run the initial scene generation
    agent.run(currentState, maxSteps = None, traceLogPath = None, debug = true) match {
      case Right(newState) =>
        currentState = newState
        // Trace the agent state to Langfuse
        tracer.traceAgentState(newState)
        // Extract the last textual response from the agent
        val responseContent = ResponseInterpreter.extractLastAssistantResponse(newState.conversation.messages)

        // Try to parse as structured data
        val (dataOpt, issuesOpt) = ResponseInterpreter.parseToOption(responseContent)
        lastValidationIssues = issuesOpt
        dataOpt match {
          case Some(scene: GameScene) =>
            coreState = CoreEngine.applyScene(coreState, scene)
            logger.info(s"[$sessionId] Game initialized with scene: ${scene.locationId} - ${scene.locationName}")
            Right(scene.narrationText)
          case Some(simple: SimpleResponse) =>
            logger.info(s"[$sessionId] Simple response on initialization: ${simple.actionTaken}")
            Right(simple.narrationText)
          case None =>
            logger.warn(s"[$sessionId] Failed to parse structured response, using raw text")
            Right(responseContent.take(200))
        }

      case Left(error) =>
        val szorkError = AIError(s"Failed to initialize game: ${error.message}", llmError = Some(error))
        Logging.logError(s"[$sessionId] Initialize game")(Left(szorkError))
        Left(szorkError)
    }
  }

  /** Response containing narrative text and optional generated media.
    *
    * @param text Narrative response text from the AI
    * @param audioBase64 Base64-encoded audio narration
    * @param imageBase64 Base64-encoded scene image
    * @param backgroundMusicBase64 Base64-encoded background music
    * @param musicMood Mood descriptor for the music
    * @param scene Structured scene data if location changed
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
    * @param command Player's text command
    * @param generateAudio Whether to generate TTS narration
    * @return Game response with text and media, or an error
    */
  def processCommand(command: String, generateAudio: Boolean = true): SzorkResult[GameResponse] = {
    logger.debug(s"[$sessionId] Processing command: $command")

    // Track user command in conversation history (pure core state)
    coreState = CoreEngine.trackUser(coreState, command)

    // Track message count before adding user message
    val previousMessageCount = currentState.conversation.messages.length

    // Add user message to conversation
    currentState = currentState
      .addMessage(UserMessage(content = command))
      .withStatus(AgentStatus.InProgress)

    // Run the agent
    val textStartTime = System.currentTimeMillis()
    logger.debug(s"[$sessionId] Starting text generation for command: $command")

    agent.run(currentState, maxSteps = None, traceLogPath = None, debug = true) match {
      case Right(newState) =>
        // Trace the agent state to Langfuse
        tracer.traceAgentState(newState)
        // Get only the new messages added by the agent
        val newMessages =
          newState.conversation.messages.drop(previousMessageCount + 1) // +1 to skip the user message we just added
        val response = ResponseInterpreter.extractAssistantResponses(newMessages)

        // Debug: LLM response summary
        logger.debug(s"LLM response (truncated) for '$command': ${response.take(200)}")
        newMessages.foreach {
          case AssistantMessage(content, toolCalls) =>
            val preview = content.filter(_ != null).map(_.take(200))
            logger.debug(s"Assistant content: $preview")
            logger.debug(s"Assistant tool calls: ${toolCalls.map(_.name)}")
          case ToolMessage(toolCallId, content) =>
            logger.debug(s"Tool message id=$toolCallId content=${content.take(200)}")
          case msg =>
            logger.debug(s"Other message type: ${msg.getClass.getSimpleName}")
        }
        logger.debug(s"Extracted response text (truncated): ${response.take(200)}")

        val assistantMessageCount = newMessages.count(_.isInstanceOf[AssistantMessage])
        logger.debug(s"Agent added ${newMessages.length} messages, $assistantMessageCount are assistant messages")

        currentState = newState

        val textGenerationTime = System.currentTimeMillis() - textStartTime
        logger.debug(s"[$sessionId] Text generation completed in ${textGenerationTime}ms (${response.length} chars)")

        // Try to parse the response as structured JSON
        val (responseText, sceneOpt) = {
          val (dataOpt, issuesOpt) = ResponseInterpreter.parseToOption(response)
          lastValidationIssues = issuesOpt
          dataOpt match {
            case Some(scene: GameScene) =>
              coreState = CoreEngine.applyScene(coreState, scene)
              logger.info(s"[$sessionId] Full scene response: ${scene.locationId} - ${scene.locationName}")
              (scene.narrationText, Some(scene))

            case Some(simple: SimpleResponse) =>
              logger.info(s"[$sessionId] Simple response for action: ${simple.actionTaken}")
              (simple.narrationText, coreState.currentScene) // Keep the current scene

            case None =>
              logger.warn(s"[$sessionId] Could not parse structured response, attempting to extract narrationText")
              val narrationText = ResponseInterpreter.extractNarrationTextFromJson(response).getOrElse {
                if (response.trim.startsWith("{")) {
                  logger.error(s"[$sessionId] Failed to parse JSON response, showing error to user")
                  "I apologize, but there was an error processing the game response. Please try your command again."
                } else response
              }
              (narrationText, coreState.currentScene)
          }
        }

        // Generate audio if requested
        val audioBase64 = if (generateAudio && responseText.nonEmpty && ttsClient.isDefined) {
          val audioStartTime = System.currentTimeMillis()
          logger.info(s"[$sessionId] Starting audio generation (${responseText.length} chars)")
          ttsClient.get.synthesizeToBase64(responseText, "nova") match {
            case Right(audio) =>
              val audioTime = System.currentTimeMillis() - audioStartTime
              logger.info(s"[$sessionId] Audio generation completed in ${audioTime}ms (${audio.length} bytes base64)")
              Some(audio)
            case Left(error) =>
              logger.error(s"[$sessionId] Failed to generate audio: $error")
              None
          }
        } else {
          logger.debug(
            s"[$sessionId] Skipping audio (generateAudio=$generateAudio, empty=${responseText.isEmpty}, ttsClient=${ttsClient.isDefined})")
          None
        }

        // Track assistant response in conversation history (pure core state)
        coreState = CoreEngine.applySimpleResponse(coreState, responseText)

        // Image generation is now handled asynchronously in the server
        // Background music generation is also handled asynchronously

        Right(GameResponse(responseText, audioBase64, None, None, None, sceneOpt))

      case Left(error) =>
        val szorkError = AIError(s"Error processing command: ${error.message}", llmError = Some(error))
        Logging.logError(s"[$sessionId] Process command")(Left(szorkError))
        Left(szorkError)
    }
  }

  /** Processes a command with real-time streaming of narrative text.
    *
    * Streams text chunks as they're generated for real-time display.
    * Final response includes complete text and optional media.
    *
    * @param command Player's text command
    * @param onTextChunk Callback for each narrative text chunk
    * @param generateAudio Whether to generate TTS narration after completion
    * @return Complete game response, or an error
    */
  def processCommandStreaming(
    command: String,
    onTextChunk: String => Unit,
    generateAudio: Boolean = true
  ): SzorkResult[GameResponse] = {
    logger.debug(s"[$sessionId] Processing command with streaming: $command")

    // Track user command in conversation history
    coreState = CoreEngine.trackUser(coreState, command)

    // Create streaming agent and text parser
    val streamingAgent = new StreamingAgent(llmClient)
    val accumulatedText = new StringBuilder()
    val textParser = new StreamingTextParser()

    // Add user message to conversation
    currentState = currentState
      .addMessage(UserMessage(content = command))
      .withStatus(AgentStatus.InProgress)

    // Run the agent with streaming
    val textStartTime = System.currentTimeMillis()
    logger.info(s"[$sessionId] Starting streaming text generation for command: '$command'")

    var chunkCount = 0
    var narrativeChunkCount = 0
    streamingAgent.runStreaming(
      currentState,
      chunk => {
        // Process the JSON chunk to extract narration text
        chunkCount += 1
        accumulatedText.append(chunk)
        logger.debug(s"[$sessionId] Received chunk #$chunkCount: ${chunk.take(50)}...")

        // Try to extract narration text
        textParser.processChunk(chunk) match {
          case Some(narrationText) =>
            // We extracted some narration text - stream it to the user
            narrativeChunkCount += 1
            logger.debug(s"[$sessionId] Streaming narrative chunk #$narrativeChunkCount: ${narrationText.take(50)}...")
            onTextChunk(narrationText)
          case None =>
            // No narration text extracted yet
            logger.debug(s"[$sessionId] No narration text in chunk #$chunkCount")
        }
      }
    ) match {
      case Right(newState) =>
        currentState = newState
        val responseText = accumulatedText.toString

        // TEMPORARY: Log complete LLM response to console for debugging
        logger.debug(s"Streaming LLM response for '$command' (truncated): ${responseText.take(200)}")

        val textGenerationTime = System.currentTimeMillis() - textStartTime
        logger.debug(
          s"[$sessionId] Streaming completed: $chunkCount chunks, $narrativeChunkCount narrative chunks, ${responseText.length} chars in ${textGenerationTime}ms")

        // Extract the JSON portion from the response
        val jsonResponse = textParser.getJson().getOrElse {
          // Fallback: try to find JSON in the full response
          val jsonMarker = "<<<JSON>>>"
          val markerIndex = responseText.indexOf(jsonMarker)
          if (markerIndex >= 0) {
            responseText.substring(markerIndex + jsonMarker.length).trim
          } else {
            responseText // Use full response as fallback
          }
        }

        // Try to parse the JSON response
        val (finalText, sceneOpt) = {
          val (dataOpt, issuesOpt) = ResponseInterpreter.parseToOption(jsonResponse)
          lastValidationIssues = issuesOpt
          dataOpt match {
            case Some(scene: GameScene) =>
              coreState = CoreEngine.applyScene(coreState, scene)
              logger.info(s"[$sessionId] Full scene response: ${scene.locationId} - ${scene.locationName}")
              (scene.narrationText, Some(scene))
            case Some(simple: SimpleResponse) =>
              logger.info(s"[$sessionId] Simple response for action: ${simple.actionTaken}")
              (simple.narrationText, coreState.currentScene)
            case None =>
              logger.warn(
                s"[$sessionId] Could not parse structured response in streaming, attempting to extract narrationText")
              val narrationText = ResponseInterpreter.extractNarrationTextFromJson(responseText).getOrElse {
                if (responseText.trim.startsWith("{")) {
                  logger.error(s"[$sessionId] Failed to parse JSON response in streaming, showing error to user")
                  "I apologize, but there was an error processing the game response. Please try your command again."
                } else responseText
              }
              (narrationText, coreState.currentScene)
          }
        }

        // Track assistant response in conversation history
        coreState = CoreEngine.applySimpleResponse(coreState, finalText)

        // Generate audio if requested (not streamed, generated after text is complete)
        val audioBase64 = if (generateAudio && finalText.nonEmpty) {
          val audioStartTime = System.currentTimeMillis()
          logger.info(s"[$sessionId] Starting audio generation (${finalText.length} chars)")
          val tts = TextToSpeech()
          tts.synthesizeToBase64(finalText, TextToSpeech.VOICE_NOVA) match {
            case Right(audio) =>
              val audioTime = System.currentTimeMillis() - audioStartTime
              logger.info(s"[$sessionId] Audio generation completed in ${audioTime}ms")
              Some(audio)
            case Left(error) =>
              logger.error(s"[$sessionId] Failed to generate audio: $error")
              None
          }
        } else {
          None
        }

        // Return the complete response
        Right(GameResponse(finalText, audioBase64, None, None, None, sceneOpt))

      case Left(error) =>
        val szorkError = AIError(s"Error in streaming command: ${error.message}", llmError = Some(error))
        Logging.logError(s"[$sessionId] Streaming command")(Left(szorkError))
        Left(szorkError)
    }
  }

  /** Returns the current count of messages in the conversation history. */
  def getMessageCount: Int = currentState.conversation.messages.length

  /** Returns the current agent state with full conversation history. */
  def getState: AgentState = currentState

  /** Retrieves and clears any validation issues from the last response parsing.
    *
    * @return Validation issues if any occurred, None otherwise
    */
  def popValidationIssues(): Option[List[String]] = {
    val v = lastValidationIssues
    lastValidationIssues = None
    v
  }

  /** Determines if a scene image should be generated based on response text.
    *
    * @param responseText The narrative response text
    * @return true if image generation is appropriate
    */
  def shouldGenerateSceneImage(responseText: String): Boolean = CoreEngine.shouldGenerateSceneImage(coreState, responseText)

  /** Generates a scene image based on current location or response text.
    *
    * @param responseText Narrative text describing the scene
    * @param gameId Optional game ID for caching
    * @return Base64-encoded image, or None if generation fails
    */
  def generateSceneImage(responseText: String, gameId: Option[String] = None): Option[String] = {
    // Use detailed description from current scene if available
    val (imagePrompt, locationId) = coreState.currentScene match {
      case Some(scene) =>
        logger.info(s"[$sessionId] Using structured image description for ${scene.locationId}")
        (scene.imageDescription, Some(scene.locationId))
      case None if CoreEngine.isNewScene(responseText) =>
        logger.info(s"[$sessionId] No structured scene, extracting from text")
        (MediaPlanner.extractSceneDescription(responseText), None)
      case _ =>
        return None
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

  def shouldGenerateBackgroundMusic(responseText: String): Boolean =
    CoreEngine.shouldGenerateBackgroundMusic(coreState, responseText)

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
          case Some(scene) =>
            val moodStr = scene.musicMood
            logger.info(s"[$sessionId] Using structured music for ${scene.locationId}: mood=$moodStr")
            (moodStr, scene.musicDescription, Some(scene.locationId))
          case None =>
            val detectedMood = MediaPlanner.detectMoodFromText(responseText)
            logger.info(s"[$sessionId] Detected mood: $detectedMood from text")
            (detectedMood, responseText, None)
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

  // Helper to convert Message to JSON for persistence
  private def messageToJson(message: Message): ujson.Value =
    message match {
      case UserMessage(content) =>
        ujson.Obj(
          "type" -> "user",
          "content" -> content
        )
      case AssistantMessage(contentOpt, toolCalls) =>
        ujson.Obj(
          "type" -> "assistant",
          "content" -> contentOpt.map(ujson.Str(_)).getOrElse(ujson.Null),
          "toolCalls" -> toolCalls.map(tc =>
            ujson.Obj(
              "id" -> tc.id,
              "name" -> tc.name,
              "arguments" -> tc.arguments
            ))
        )
      case SystemMessage(content) =>
        ujson.Obj(
          "type" -> "system",
          "content" -> content
        )
      case ToolMessage(toolCallId, content) =>
        ujson.Obj(
          "type" -> "tool",
          "toolCallId" -> toolCallId,
          "content" -> content
        )
    }

  /** Extracts complete game state for persistence.
    *
    * Captures conversation history, inventory, media cache, and agent state
    * for save/load functionality.
    *
    * @param gameId Unique game identifier
    * @param gameTheme Theme information for the game
    * @param gameArtStyle Art style information for the game
    * @return Complete game state snapshot
    */
  def getGameState(gameId: String, gameTheme: Option[GameTheme], gameArtStyle: Option[ArtStyle]): GameState = {
    val currentSessionTime = clock.now() - sessionStartTime

    // Convert all agent messages to JSON for complete state persistence
    val agentMessagesJson = if (currentState != null) {
      currentState.conversation.messages.map(messageToJson).toList
    } else {
      List.empty
    }

    GameState(
      gameId = gameId,
      theme = gameTheme,
      artStyle = gameArtStyle,
      adventureOutline = adventureOutline, // Include the full adventure outline
      currentScene = coreState.currentScene,
      visitedLocations = coreState.visitedLocations,
      conversationHistory = coreState.conversationHistory,
      inventory = GameTools.getInventory,
      createdAt = createdAt,
      lastSaved = clock.now(),
      lastPlayed = clock.now(),
      totalPlayTime = totalPlayTime + currentSessionTime,
      adventureTitle = adventureTitle,
      agentMessages = agentMessagesJson, // Complete agent state
      mediaCache = mediaCache, // Media cache entries
      systemPrompt = Some(completeSystemPrompt) // Complete system prompt
    )
  }

  // Helper to convert JSON back to Message for restoration
  private def jsonToMessage(json: ujson.Value): Message =
    json("type").str match {
      case "user" => UserMessage(content = json("content").str)
      case "assistant" =>
        val content = json("content") match {
          case ujson.Null => None
          case s => Some(s.str)
        }
        val toolCalls = json("toolCalls").arr
          .map(tc =>
            ToolCall(
              id = tc("id").str,
              name = tc("name").str,
              arguments = tc("arguments") // Already a ujson.Value, not a string
            ))
          .toSeq
        AssistantMessage(contentOpt = content, toolCalls = toolCalls)
      case "system" => SystemMessage(content = json("content").str)
      case "tool" =>
        ToolMessage(
          toolCallId = json("toolCallId").str,
          content = json("content").str
        )
    }

  /** Restores game from a saved state snapshot.
    *
    * Reconstructs conversation history, inventory, and agent state from
    * a previously saved game state.
    *
    * @param state Saved game state to restore
    */
  def restoreGameState(state: GameState): Unit = {
    // Restore simple state
    coreState = coreState.copy(
      currentScene = state.currentScene,
      visitedLocations = state.visitedLocations,
      conversationHistory = state.conversationHistory
    )
    GameTools.setInventory(state.inventory)
    totalPlayTime = state.totalPlayTime
    sessionStartTime = clock.now() // Reset session timer when restoring
    adventureTitle = state.adventureTitle
    mediaCache = state.mediaCache // Restore media cache
    // Note: currentStepNumber should be restored from GameMetadata by the caller

    // Restore complete agent state if available
    if (state.agentMessages.nonEmpty) {
      // Convert JSON messages back to Message objects
      val messages = state.agentMessages.map(jsonToMessage)

      // Use the stored system prompt or fall back to current one
      val systemPrompt = state.systemPrompt.getOrElse(completeSystemPrompt)

      // Initialize the agent with the first message (should be "Start adventure")
      val firstMessage = messages.headOption match {
        case Some(UserMessage(content)) => content
        case _ => "Start adventure" // Fallback
      }

      currentState = agent.initialize(
        firstMessage,
        toolRegistry,
        systemPromptAddition = Some(systemPrompt)
      )

      // Add all the remaining messages to fully restore the conversation
      messages.tail.foreach { msg =>
        currentState = currentState.addMessage(msg)
      }

      logger.info(s"[$sessionId] Game state restored with ${messages.size} agent messages")
    } else if (state.conversationHistory.nonEmpty) {
      // Fallback to old restoration method for backwards compatibility
      val messages = state.conversationHistory.flatMap { entry =>
        entry.role match {
          case "user" => Some(UserMessage(content = entry.content))
          case "assistant" => Some(AssistantMessage(contentOpt = Some(entry.content)))
          case _ => None
        }
      }

      if (messages.nonEmpty) {
        currentState = agent.initialize(
          messages.head.content,
          toolRegistry,
          systemPromptAddition = Some(completeSystemPrompt)
        )

        messages.tail.foreach { msg =>
          currentState = currentState.addMessage(msg)
        }
      }

      logger.info(
        s"[$sessionId] Game state restored (legacy) with ${coreState.conversationHistory.size} conversation entries")
    } else {
      // No history to restore, initialize normally
      initialize()
    }
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
    * This method extracts all necessary data for step-based persistence.
    * The caller is responsible for saving via StepPersistence.
    *
    * @param gameId Game identifier
    * @param gameTheme Optional game theme
    * @param gameArtStyle Optional art style
    * @param userCommand Optional user command that triggered this step
    * @param narrationText Narration text from the response
    * @param response Optional game response (scene or action)
    * @param executionTimeMs Time taken to execute the step
    * @return Complete step data ready for persistence
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
    val toolCalls = DebugHelpers.extractToolCalls(currentState.conversation.messages)

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
      messageCount = currentState.conversation.messages.length,
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
      agentMessages = currentState.conversation.messages.toList,
      outline = if (currentStepNumber == 1) adventureOutline else None
    )
  }

}

/** Factory for creating GameEngine instances. */
object GameEngine {
  /** Creates a new GameEngine with the specified configuration.
    *
    * @param llmClient LLM client for AI narrative generation
    * @param sessionId Unique session identifier
    * @param theme Optional theme for the adventure
    * @param artStyle Optional art style for images
    * @param adventureOutline Optional pre-generated adventure structure
    * @param clock Clock implementation for time tracking
    * @param ttsClient Optional text-to-speech client
    * @param imageClient Optional image generation client
    * @param musicClient Optional music generation client
    * @return Configured GameEngine instance
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
