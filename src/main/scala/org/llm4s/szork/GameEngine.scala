package org.llm4s.szork

import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Message, UserMessage, AssistantMessage, SystemMessage, ToolMessage, ToolCall}
import org.llm4s.error.LLMError
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory
import org.llm4s.szork.core.{CoreEngine, CoreState}
import org.llm4s.szork.spi.{Clock, SystemClock, TTSClient, ImageClient, MusicClient}

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
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  
  private val themeDescription = PromptBuilder.themeDescription(theme)
  private val artStyleDescription = PromptBuilder.artStyleDescription(artStyle)
  private val adventureOutlinePrompt = PromptBuilder.outlinePrompt(adventureOutline)
  
  

  private val completeSystemPrompt: String = PromptBuilder.fullSystemPrompt(theme, artStyle, adventureOutline)
  private val client: LLMClient = llmClient
  private val toolRegistry = new ToolRegistry(GameTools.allTools)
  private val agent = new Agent(client)
  
  private var currentState: AgentState = _
  private var core: CoreState = CoreState()
  private var lastValidationIssues: Option[List[String]] = None
  private val createdAt: Long = clock.now()
  private var sessionStartTime: Long = clock.now()
  private var totalPlayTime: Long = 0L  // Accumulated play time from previous sessions
  private var adventureTitle: Option[String] = adventureOutline.map(_.title)
  
  // Enhanced state tracking
  private var mediaCache: Map[String, MediaCacheEntry] = Map.empty
  
  def initialize(): Either[String, String] = {
    logger.info(s"[$sessionId] Initializing game with theme: $themeDescription")
    
    // Clear inventory for new game
    GameTools.clearInventory()
    
    // Use "Start adventure" as the trigger message (not shown to user)
    val initPrompt = "Start adventure"
    
    // Initialize the agent with the complete system prompt including adventure outline
    currentState = agent.initialize(
      initPrompt,
      toolRegistry,
      systemPromptAddition = Some(completeSystemPrompt)
    )
    
    // Track the initialization message (but don't show to user)
    // This ensures the agent knows to generate the opening scene
    
    // Automatically run the initial scene generation
    agent.run(currentState) match {
      case Right(newState) =>
        currentState = newState
        // Extract the last textual response from the agent
        val responseContent = ResponseInterpreter.extractLastAssistantResponse(newState.conversation.messages)
        
        // Try to parse as structured data
        val (dataOpt, issuesOpt) = ResponseInterpreter.parseToOption(responseContent)
        lastValidationIssues = issuesOpt
        dataOpt match {
          case Some(scene: GameScene) =>
            core = CoreEngine.applyScene(core, scene)
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
        logger.error(s"[$sessionId] Failed to initialize game: $error")
        Left(s"Failed to initialize game: ${error.message}")
    }
  }
  
  case class GameResponse(
    text: String, 
    audioBase64: Option[String] = None, 
    imageBase64: Option[String] = None,
    backgroundMusicBase64: Option[String] = None,
    musicMood: Option[String] = None,
    scene: Option[GameScene] = None
  )
  
  def processCommand(command: String, generateAudio: Boolean = true): Either[LLMError, GameResponse] = {
    logger.debug(s"[$sessionId] Processing command: $command")
    
    // Track user command in conversation history (pure core state)
    core = CoreEngine.trackUser(core, command)
    
    // Track message count before adding user message
    val previousMessageCount = currentState.conversation.messages.length
    
    // Add user message to conversation
    currentState = currentState
      .addMessage(UserMessage(content = command))
      .withStatus(AgentStatus.InProgress)
    
    // Run the agent
    val textStartTime = System.currentTimeMillis()
    logger.info(s"[$sessionId] Starting text generation for command: $command")
    
    agent.run(currentState) match {
      case Right(newState) =>
        // Get only the new messages added by the agent
        val newMessages = newState.conversation.messages.drop(previousMessageCount + 1) // +1 to skip the user message we just added
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
        logger.info(s"[$sessionId] Text generation completed in ${textGenerationTime}ms (${response.length} chars)")
        
        // Try to parse the response as structured JSON
        val (responseText, sceneOpt) = {
          val (dataOpt, issuesOpt) = ResponseInterpreter.parseToOption(response)
          lastValidationIssues = issuesOpt
          dataOpt match {
            case Some(scene: GameScene) =>
              core = CoreEngine.applyScene(core, scene)
              logger.info(s"[$sessionId] Full scene response: ${scene.locationId} - ${scene.locationName}")
              (scene.narrationText, Some(scene))

            case Some(simple: SimpleResponse) =>
              logger.info(s"[$sessionId] Simple response for action: ${simple.actionTaken}")
              (simple.narrationText, core.currentScene) // Keep the current scene

            case None =>
              logger.warn(s"[$sessionId] Could not parse structured response, attempting to extract narrationText")
              val narrationText = ResponseInterpreter.extractNarrationTextFromJson(response).getOrElse {
                if (response.trim.startsWith("{")) {
                  logger.error(s"[$sessionId] Failed to parse JSON response, showing error to user")
                  "I apologize, but there was an error processing the game response. Please try your command again."
                } else response
              }
              (narrationText, core.currentScene)
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
          logger.info(s"[$sessionId] Skipping audio (generateAudio=$generateAudio, empty=${responseText.isEmpty}, ttsClient=${ttsClient.isDefined})")
          None
        }
        
        // Track assistant response in conversation history (pure core state)
        core = CoreEngine.applySimpleResponse(core, responseText)
        
        // Image generation is now handled asynchronously in the server
        // Background music generation is also handled asynchronously
        
        Right(GameResponse(responseText, audioBase64, None, None, None, sceneOpt))
        
      case Left(error) =>
        logger.error(s"[$sessionId] Error processing command: $error")
        Left(error)
    }
  }
  
  def processCommandStreaming(
    command: String,
    onTextChunk: String => Unit,
    generateAudio: Boolean = true
  ): Either[LLMError, GameResponse] = {
    logger.debug(s"[$sessionId] Processing command with streaming: $command")
    
    // Track user command in conversation history
    core = CoreEngine.trackUser(core, command)
    
    // Create streaming agent and text parser
    val streamingAgent = new StreamingAgent(client)
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
    streamingAgent.runStreaming(currentState, chunk => {
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
    }) match {
      case Right(newState) =>
        currentState = newState
        val responseText = accumulatedText.toString
        
        // TEMPORARY: Log complete LLM response to console for debugging
        logger.debug(s"Streaming LLM response for '$command' (truncated): ${responseText.take(200)}")
        
        val textGenerationTime = System.currentTimeMillis() - textStartTime
        logger.info(s"[$sessionId] Streaming completed: $chunkCount chunks, $narrativeChunkCount narrative chunks, ${responseText.length} chars in ${textGenerationTime}ms")
        
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
              core = CoreEngine.applyScene(core, scene)
              logger.info(s"[$sessionId] Full scene response: ${scene.locationId} - ${scene.locationName}")
              (scene.narrationText, Some(scene))
            case Some(simple: SimpleResponse) =>
              logger.info(s"[$sessionId] Simple response for action: ${simple.actionTaken}")
              (simple.narrationText, core.currentScene)
            case None =>
              logger.warn(s"[$sessionId] Could not parse structured response in streaming, attempting to extract narrationText")
              val narrationText = ResponseInterpreter.extractNarrationTextFromJson(responseText).getOrElse {
                if (responseText.trim.startsWith("{")) {
                  logger.error(s"[$sessionId] Failed to parse JSON response in streaming, showing error to user")
                  "I apologize, but there was an error processing the game response. Please try your command again."
                } else responseText
              }
              (narrationText, core.currentScene)
          }
        }
        
        // Track assistant response in conversation history
        core = CoreEngine.applySimpleResponse(core, finalText)
        
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
        logger.error(s"[$sessionId] Error in streaming command: $error")
        Left(error)
    }
  }
  
  def getMessageCount: Int = currentState.conversation.messages.length
  
  def getState: AgentState = currentState
  
  def popValidationIssues(): Option[List[String]] = {
    val v = lastValidationIssues
    lastValidationIssues = None
    v
  }
  
  // Helper to extract just narrationText from JSON when full parsing fails
  private def extractNarrationTextFromJson(response: String): Option[String] = {
    try {
      if (response.trim.startsWith("{") && response.contains("narrationText")) {
        // Try to parse with ujson
        val json = ujson.read(response)
        json.obj.get("narrationText").map(_.str)
      } else {
        None
      }
    } catch {
      case _: Exception =>
        // If ujson parsing fails, try a simple regex extraction
        val pattern = """"narrationText"\s*:\s*"([^"]+(?:\\.[^"]+)*)"""".r
        pattern.findFirstMatchIn(response).map(_.group(1).replace("\\\"", "\"").replace("\\n", "\n"))
    }
  }
  
  def shouldGenerateSceneImage(responseText: String): Boolean = CoreEngine.shouldGenerateSceneImage(core, responseText)
  
  def generateSceneImage(responseText: String, gameId: Option[String] = None): Option[String] = {
    // Use detailed description from current scene if available
    val (imagePrompt, locationId) = core.currentScene match {
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
  
  def shouldGenerateBackgroundMusic(responseText: String): Boolean = CoreEngine.shouldGenerateBackgroundMusic(core, responseText)
  
  def generateBackgroundMusic(responseText: String, gameId: Option[String] = None): Option[(String, String)] = {
    if (shouldGenerateBackgroundMusic(responseText)) {
      logger.info(s"[$sessionId] Checking if background music should be generated")
      try {
        // Check if music generation is available
        if (musicClient.isEmpty || !musicClient.get.isAvailable) {
          logger.info(s"[$sessionId] Music generation disabled or unavailable")
          return None
        }
        
        // Use structured mood and description if available
        val (mood, contextText, locationId) = core.currentScene match {
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
            logger.info(s"[$sessionId] Background music generated/retrieved for mood: $mood, base64: ${musicBase64.length}")
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
  }

  // mood detection moved to MediaPlanner
  
  def getCurrentScene: Option[GameScene] = core.currentScene
  
  // Track media generation in cache
  def addMediaCacheEntry(locationId: String, entry: MediaCacheEntry): Unit = {
    mediaCache = mediaCache + (locationId -> entry)
    logger.debug(s"Added media cache entry for location: $locationId")
  }
  
  def updateMediaCacheImage(locationId: String, imagePrompt: String, imageCacheId: String): Unit = {
    val existing = mediaCache.get(locationId).getOrElse(
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
    val existing = mediaCache.get(locationId).getOrElse(
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
  private def messageToJson(message: Message): ujson.Value = {
    message match {
      case UserMessage(content) => ujson.Obj(
        "type" -> "user",
        "content" -> content
      )
      case AssistantMessage(contentOpt, toolCalls) => ujson.Obj(
        "type" -> "assistant",
        "content" -> contentOpt.map(ujson.Str(_)).getOrElse(ujson.Null),
        "toolCalls" -> toolCalls.map(tc => ujson.Obj(
          "id" -> tc.id,
          "name" -> tc.name,
          "arguments" -> tc.arguments
        ))
      )
      case SystemMessage(content) => ujson.Obj(
        "type" -> "system",
        "content" -> content
      )
      case ToolMessage(toolCallId, content) => ujson.Obj(
        "type" -> "tool",
        "toolCallId" -> toolCallId,
        "content" -> content
      )
    }
  }
  
  // State extraction for persistence
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
      adventureOutline = adventureOutline,  // Include the full adventure outline
      currentScene = core.currentScene,
      visitedLocations = core.visitedLocations,
      conversationHistory = core.conversationHistory,
      inventory = GameTools.getInventory,
      createdAt = createdAt,
      lastSaved = clock.now(),
      lastPlayed = clock.now(),
      totalPlayTime = totalPlayTime + currentSessionTime,
      adventureTitle = adventureTitle,
      agentMessages = agentMessagesJson,  // Complete agent state
      mediaCache = mediaCache,  // Media cache entries
      systemPrompt = Some(completeSystemPrompt)  // Complete system prompt
    )
  }
  
  // Helper to convert JSON back to Message for restoration
  private def jsonToMessage(json: ujson.Value): Message = {
    json("type").str match {
      case "user" => UserMessage(content = json("content").str)
      case "assistant" => 
        val content = json("content") match {
          case ujson.Null => None
          case s => Some(s.str)
        }
        val toolCalls = json("toolCalls").arr.map(tc => ToolCall(
          id = tc("id").str,
          name = tc("name").str,
          arguments = tc("arguments")  // Already a ujson.Value, not a string
        )).toSeq
        AssistantMessage(contentOpt = content, toolCalls = toolCalls)
      case "system" => SystemMessage(content = json("content").str)
      case "tool" => ToolMessage(
        toolCallId = json("toolCallId").str,
        content = json("content").str
      )
    }
  }
  
  // Restore game from saved state
  def restoreGameState(state: GameState): Unit = {
    // Restore simple state
    core = core.copy(
      currentScene = state.currentScene,
      visitedLocations = state.visitedLocations,
      conversationHistory = state.conversationHistory
    )
    GameTools.setInventory(state.inventory)
    totalPlayTime = state.totalPlayTime
    sessionStartTime = clock.now()  // Reset session timer when restoring
    adventureTitle = state.adventureTitle
    mediaCache = state.mediaCache  // Restore media cache
    
    // Restore complete agent state if available
    if (state.agentMessages.nonEmpty) {
      // Convert JSON messages back to Message objects
      val messages = state.agentMessages.map(jsonToMessage)
      
      // Use the stored system prompt or fall back to current one
      val systemPrompt = state.systemPrompt.getOrElse(completeSystemPrompt)
      
      // Initialize the agent with the first message (should be "Start adventure")
      val firstMessage = messages.headOption match {
        case Some(UserMessage(content)) => content
        case _ => "Start adventure"  // Fallback
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
      
      logger.info(s"[$sessionId] Game state restored (legacy) with ${core.conversationHistory.size} conversation entries")
    } else {
      // No history to restore, initialize normally
      initialize()
    }
  }

  
  def getAdventureTitle: Option[String] = adventureTitle
  
  def setAdventureTitle(title: String): Unit = {
    adventureTitle = Some(title)
  }
  
}

object GameEngine {
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
