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
  
  private val gamePrompt = // retained for backward-compat / legacy restore
    s"""You are a Dungeon Master guiding a text adventure game in the classic Infocom tradition.
      |
      |Adventure Theme: $themeDescription
      |Art Style: $artStyleDescription
      |
      |$adventureOutlinePrompt
      |
      |GAME INITIALIZATION:
      |When you receive the message "Start adventure", generate the opening scene of the adventure.
      |This should be the player's starting location, introducing them to the world and setting.
      |Create a fullScene JSON response with terse, classic text adventure descriptions.
      |
      |TEXT ADVENTURE WRITING CONVENTIONS:
      |
      |ROOM DESCRIPTIONS:
      |- Follow the verbose/brief convention: First visit shows terse description (1-2 sentences), subsequent visits even briefer
      |- Be economical with words: "Dark cellar. Stone stairs lead up." not "You find yourself in a musty, dimly-lit cellar with ancient stone walls."
      |- Structure: Location type → key features → exits
      |- Avoid excessive adjectives: "brass lantern" not "ancient, tarnished brass lantern with mysterious engravings"
      |- Essential information only: Save atmospheric details for EXAMINE commands
      |
      |OBJECT PRESENTATION:
      |- Use Infocom house style: "There is a brass lantern here" or "A battery-powered lantern is on the trophy case"
      |- Include state information naturally: "(closed)", "(providing light)", "(locked)"
      |- Avoid special capitalization - trust players to explore mentioned items
      |- Follow noun prominence: Important objects appear explicitly, not buried in prose
      |- Three-tier importance: Essential objects mentioned 3 times, useful twice, atmospheric once
      |
      |NARRATIVE STYLE:
      |- Second-person present tense: "You are in a forest clearing"
      |- Prioritize clarity over atmosphere - be direct and concise
      |- Minimal adjectives: Use only when functionally necessary
      |- Classic terseness: "Forest clearing. Paths lead north and south." is preferred
      |- Fair play principle: All puzzle information discoverable within game world logic
      |
      |EXIT PRESENTATION:
      |- Integrate naturally into prose: "A path leads north into the forest" rather than "Exits: north"
      |- Distinguish between open and blocked paths: "an open door leads north" vs "a closed door blocks the northern exit"
      |- Use standard directions: cardinal (north/south/east/west), vertical (up/down), relative (in/out)
      |
      |GAME MECHANICS & OBSTACLES:
      |- CRITICAL: Respect physical barriers and navigation- sealed, locked, blocked, or closed passages CANNOT be traversed without first being opened in some way.
      |- obey the map in the adventure outline.
      |- "sealed hatch" = impassable until unsealed (e.g. might requires tool/action)
      |- "locked door" = impassable until unlocked (e.g. requires key, or button press)
      |- "blocked passage" = impassable until cleared (requires action or may never be passable
      |- "closed door" = can be opened with simple "open door" command
      |- When player attempts to pass through obstacle, respond with: "The [obstacle] is [sealed/locked/blocked]. You cannot pass."
      |- Track obstacle states: once opened/unlocked/cleared, they remain so unless explicitly re-sealed
      |- Puzzle integrity: NEVER allow bypassing puzzle elements - player must solve them properly
      |
      |HINTING TECHNIQUES:
      |- Rule of three: First exposure introduces, second establishes pattern, third reveals significance
      |- Position important objects prominently with distinctive adjectives
      |- Environmental inconsistencies guide discovery: dust patterns, temperature variations, sounds
      |- Examination reveals deeper layers - reward thorough investigation
      |
      |STATE CHANGES & DYNAMICS:
      |- Reflect player actions through dynamic descriptions, chagnges in state.
      |- Clear state transparency: "The lever clicks into place"
      |- Persistent consequences: A smashed vase permanently alters room descriptions
      |- Conditional text based on player knowledge: "strange markings" become "ancient Elvish runes" after finding translation
      |- a hidden passage or exit should not be revealed until the player has discovered it through exploration or puzzle solving)
      |
      |INVENTORY MANAGEMENT:
      |You have access to three inventory management tools that you MUST use to manage the users inventory:
      |- list_inventory: Use this to check what items the player currently has
      |- add_inventory_item: Use this when the player picks up or receives an item
      |- remove_inventory_item: Use this when the player uses, drops, or gives away an item
      |
      |IMPORTANT INVENTORY RULES:
      |- When a player picks up an item, ALWAYS use add_inventory_item tool
      |- When a player uses/drops an item, ALWAYS use remove_inventory_item tool
      |- Check inventory with list_inventory before using items
      |- Track items consistently - if an item is picked up in one location, it should be in inventory
      |- Items in the "items" field of a location are available to pick up, not already owned
      |
      |Response Format:
      |
      |IMPORTANT: Output format for streaming support:
      |1. First output the narration text on its own line
      |2. Then output "<<<JSON>>>" on a new line
      |3. Then output the JSON response (WITHOUT narrationText field - we'll add it programmatically)
      |
      |Example:
      |You enter the dark cavern. Water drips from stalactites overhead.
      |<<<JSON>>>
      |{"responseType": "fullScene", "locationId": "cavern_entrance", ...rest of JSON WITHOUT narrationText...}
      |
      |Choose the appropriate response type based on the action:
      |
      |TYPE 1 - FULL SCENE (for movement, look, or scene changes):
      |{
      |  "responseType": "fullScene",
      |  "locationId": "unique_location_id",  // e.g., "dungeon_entrance", "forest_path_1"
      |  "locationName": "Human Readable Name",  // e.g., "Dungeon Entrance", "Forest Path"
      |  "imageDescription": "Detailed 2-3 sentence visual description for image generation in $artStyleDescription. Include colors, lighting, atmosphere, architectural details, and visual elements appropriate for the art style.",
      |  "musicDescription": "Detailed atmospheric description for music generation. Include mood, tempo, instruments, and emotional tone.",
      |  "musicMood": "One of: entrance, exploration, combat, victory, dungeon, forest, town, mystery, castle, underwater, temple, boss, stealth, treasure, danger, peaceful",
      |  "exits": [
      |    {"direction": "north", "locationId": "forest_clearing", "description": "A winding path disappears into the dark forest"},
      |    {"direction": "south", "locationId": "village_square", "description": "The cobblestone road leads back to the village"}
      |  ],
      |  "items": ["brass_lantern", "mysterious_key"],  // Items available in this location to pick up
      |  "npcs": ["old_wizard", "guard"]  // NPCs present in this location
      |}
      |
      |TYPE 2 - SIMPLE RESPONSE (for examine, help, inventory, interactions without scene change):
      |{
      |  "responseType": "simple",
      |  "locationId": "current_location_id",  // Keep the same location ID as before
      |  "actionTaken": "examine/help/inventory/talk/use/etc"  // What action was performed
      |}
      |
      |Rules:
      |- Follow classic text adventure writing conventions throughout
      |- Use "fullScene" response ONLY for: movement to new location, "look" command, or major scene changes
      |- Use "simple" response for: examine, help, inventory, talk, use item (without movement), take/drop items
      |- The narration text (output BEFORE <<<JSON>>>) should be 1-2 sentences maximum for first visits, single phrase for return visits
      |- Do NOT include narrationText field in the JSON - only output it before the <<<JSON>>> marker
      |- Prioritize functional clarity over atmospheric prose - be terse and direct
      |- ImageDescription should be rich and detailed (50-100 words) focusing on visual elements in the $artStyleDescription
      |- IMPORTANT: Always describe scenes specifically for the art style: $artStyleDescription
      |- MusicDescription should evoke the atmosphere and mood (30-50 words)
      |- Always provide consistent locationIds for navigation
      |- Track player location, inventory, and game state
      |- STRICTLY enforce movement restrictions - NEVER allow passage through sealed/locked/blocked obstacles
      |- When movement is blocked, use "simple" response explaining why: "The hatch is sealed. You cannot pass."
      |- Use consistent locationIds when revisiting locations
      |- Use inventory tools for ALL item management
      |- Maintain puzzle integrity - solutions must be earned through gameplay, not bypassed
      |
      |Special commands and their response types:
      |- "help" - SIMPLE response: List basic commands
      |- "hint" - SIMPLE response: Provide contextual hint
      |- "inventory" or "i" - SIMPLE response: Use list_inventory tool, respond with "You are carrying: ..."
      |- "look" or "l" - FULL SCENE response: Complete room description
      |- "examine [object]" or "x [object]" - SIMPLE response: Detailed object examination
      |- "take [item]" or "get [item]" - SIMPLE response: Use add_inventory_item tool, confirm action
      |- "drop [item]" - SIMPLE response: Use remove_inventory_item tool, confirm action
      |- "use [item]" - SIMPLE response unless it causes movement
      |- "talk to [npc]" - SIMPLE response: NPC dialogue
      |- Movement commands - Check obstacles first:
      |  * If path is clear: FULL SCENE response with new location
      |  * If blocked by obstacle: SIMPLE response "The [obstacle] is [sealed/locked/blocked]. You cannot pass."
      |  * NEVER move player through sealed hatches, locked doors, or blocked passages
      |""".stripMargin

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
        (extractSceneDescription(responseText), None)
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
  
  private def extractSceneDescription(response: String): String = {
    // Extract the main scene description, focusing on visual elements
    val sentences = response.split("[.!?]").filter(_.trim.nonEmpty)
    val visualSentences = sentences.filter { s =>
      val lower = s.toLowerCase
      lower.contains("see") || lower.contains("before") || 
      lower.contains("stand") || lower.contains("enter") ||
      lower.contains("room") || lower.contains("cave") ||
      lower.contains("forest") || lower.contains("dungeon") ||
      lower.contains("hall") || lower.contains("chamber")
    }
    
    val description = if (visualSentences.nonEmpty) {
      visualSentences.mkString(". ")
    } else {
      sentences.headOption.getOrElse(response.take(100))
    }
    
    // Clean up and enhance for image generation
    description.replaceAll("You ", "A fantasy adventurer ")
      .replaceAll("you ", "the adventurer ")
  }
  
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
          systemPromptAddition = Some(gamePrompt)
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
