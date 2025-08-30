# SZork Streaming Implementation Plan

## Executive Summary

This document outlines the implementation plan for adding streaming support to the SZork game server. The goal is to stream text responses from the LLM as they are generated, providing immediate feedback to users rather than waiting for the complete response.

## Current Architecture Analysis

### Existing Components

1. **Backend Stack**:
   - `SzorkServer.scala`: HTTP server handling game endpoints
   - `GameEngine.scala`: Core game logic using the Agent framework
   - `Agent.scala`: Orchestrates LLM interactions (currently uses `client.complete()`)
   - `LLMClient`: Supports both `complete()` and `streamComplete()` methods

2. **Frontend Stack**:
   - Vue 3 application
   - Axios for HTTP requests
   - Current flow: Send command → Wait for complete response → Display

3. **Key Observations**:
   - The LLM4S framework already has streaming support via `streamComplete()`
   - The Agent class currently only uses non-streaming `complete()`
   - Frontend currently expects complete responses via standard HTTP POST

## Design Decisions

### 1. Communication Protocol

**Decision: Server-Sent Events (SSE)**

Rationale:
- Native browser support
- Unidirectional (server → client) which matches our needs
- Simple to implement on both backend and frontend
- Better than WebSockets for this use case (no bidirectional needs)
- More efficient than long polling

### 2. Streaming Granularity

**Decision: Stream at the text chunk level**

The streaming will work as follows:
- Stream text chunks as they arrive from the LLM
- Tool calls and structured data (scenes, inventory) sent as complete units
- Audio/image generation notifications sent as separate events

### 3. Backward Compatibility

**Decision: Support both streaming and non-streaming modes**

- Add a `streaming` parameter to command endpoints
- Default to streaming for new clients
- Allow fallback to non-streaming for compatibility

## Critical Design Issue: Differentiating Response Types

### The Challenge

The Agent framework produces different types of responses that need distinct handling:

1. **User-facing text responses** - Should be streamed to the user
2. **Tool calls** - Internal operations (inventory management, scene parsing)  
3. **Tool responses** - Results from tool execution (not user-facing)

### Detection Strategy

The key insight: The **first meaningful chunk** tells us the response type:

```scala
// OpenAI format detection:
chunk.content.isDefined && chunk.toolCall.isEmpty => UserText
chunk.toolCall.isDefined => ToolCall

// Anthropic format detection:
content_block.type == "text" => UserText  
content_block.type == "tool_use" => ToolCall
```

### Solution: Early Detection with Minimal Buffering

```scala
class StreamingResponseDetector {
  sealed trait ResponseType
  case object UserText extends ResponseType
  case object ToolCall extends ResponseType
  case object Unknown extends ResponseType
  
  private var detectedType: ResponseType = Unknown
  
  def processChunk(chunk: StreamedChunk): (ResponseType, Option[String]) = {
    if (detectedType == Unknown) {
      // Detect based on first chunk with content
      if (chunk.content.isDefined && chunk.toolCall.isEmpty) {
        detectedType = UserText
        return (UserText, chunk.content)
      } else if (chunk.toolCall.isDefined) {
        detectedType = ToolCall
        return (ToolCall, None)
      }
    }
    
    detectedType match {
      case UserText => (UserText, chunk.content)
      case ToolCall => (ToolCall, None)  // Never stream tool calls
      case Unknown => (Unknown, None)    // Buffer until detected
    }
  }
}
```

This ensures:
- User never sees tool calls or internal reasoning
- Minimal buffering (usually just 1-3 chunks)
- Agent's multi-step reasoning is preserved
- Clean separation between user-facing and internal responses

## Implementation Architecture

### Backend Changes

#### 1. Enhanced Agent with Streaming Support

Create `StreamingAgent.scala`:
```scala
class StreamingAgent(client: LLMClient) extends Agent(client) {
  
  def runStepStreaming(
    state: AgentState,
    onUserText: String => Unit  // Only called for user-facing text
  ): Result[AgentState] = {
    val detector = new StreamingResponseDetector()
    
    state.status match {
      case AgentStatus.InProgress =>
        val options = CompletionOptions(tools = state.tools.tools)
        var isUserResponse = false
        
        client.streamComplete(state.conversation, options, chunk => {
          detector.processChunk(chunk) match {
            case (UserText, Some(text)) =>
              onUserText(text)  // Stream to frontend
              isUserResponse = true
            case (ToolCall, _) =>
              isUserResponse = false
              // Don't stream tool calls
            case _ =>
              // Still detecting
          }
        }) match {
          case Right(completion) =>
            val updatedState = state.addMessage(completion.message)
            
            if (isUserResponse) {
              Right(updatedState.withStatus(AgentStatus.Complete))
            } else {
              // Tool calls detected, mark for processing
              Right(updatedState.withStatus(AgentStatus.WaitingForTools))
            }
          case Left(error) =>
            Left(error)
        }
        
      case AgentStatus.WaitingForTools =>
        // Process tools without streaming
        super.runStep(state)
        
      case _ =>
        Right(state)
    }
  }
}
```

#### 2. Streaming Response Handler

Create `StreamingResponseHandler.scala`:
```scala
case class StreamEvent(
  eventType: String,  // "chunk", "scene", "toolCall", "complete", "error"
  data: String,
  metadata: Option[Map[String, Any]] = None
)

trait StreamingResponseHandler {
  def sendEvent(event: StreamEvent): Unit
  def close(): Unit
}
```

#### 3. Modified GameEngine

Add streaming support to `GameEngine.scala`:
```scala
def processCommandStreaming(
  command: String,
  onTextChunk: String => Unit,  // Stream text chunks
  generateAudio: Boolean = true
): Either[LLMError, GameResponse] = {
  
  trackConversation("user", command)
  
  val streamingAgent = new StreamingAgent(client)
  var accumulatedText = new StringBuilder()
  
  // Add user message to state
  currentState = currentState
    .addMessage(UserMessage(command))
    .withStatus(AgentStatus.InProgress)
  
  // Run agent with streaming
  @tailrec
  def runUntilComplete(state: AgentState): Result[AgentState] = {
    streamingAgent.runStepStreaming(state, chunk => {
      accumulatedText.append(chunk)
      onTextChunk(chunk)  // Stream to frontend
    }) match {
      case Right(newState) if newState.status == AgentStatus.WaitingForTools =>
        // Process tools and continue
        runUntilComplete(newState)
      case Right(finalState) =>
        Right(finalState)
      case Left(error) =>
        Left(error)
    }
  }
  
  runUntilComplete(currentState) match {
    case Right(finalState) =>
      currentState = finalState
      val responseText = accumulatedText.toString
      
      // Parse scene data and generate audio/images as before
      val (text, sceneOpt) = parseResponseData(responseText) match {
        case Some(scene: GameScene) =>
          currentScene = Some(scene)
          (scene.narrationText, Some(scene))
        case Some(simple: SimpleResponse) =>
          (simple.narrationText, currentScene)
        case None =>
          (responseText, currentScene)
      }
      
      trackConversation("assistant", text)
      Right(GameResponse(text, None, None, None, None, sceneOpt))
      
    case Left(error) =>
      Left(error)
  }
}

#### 4. SSE Endpoint in Server

Add new streaming endpoint to `SzorkServer.scala`:
```scala
@post("/api/game/stream")
def streamCommand(request: Request) = {
  val json = ujson.read(request.text())
  val sessionId = json("sessionId").str
  val command = json("command").str
  val imageGenerationEnabled = json.obj.get("imageGenerationEnabled").map(_.bool).getOrElse(true)
  
  sessions.get(sessionId) match {
    case Some(session) =>
      // Create SSE response
      val stream = new Iterator[String] {
        private val queue = new java.util.concurrent.LinkedBlockingQueue[String]()
        private var done = false
        
        // Start streaming in background
        Future {
          session.engine.processCommandStreaming(
            command,
            onTextChunk = chunk => {
              // Send SSE event for text chunk
              queue.offer(s"event: chunk\ndata: ${ujson.Obj("text" -> chunk).render()}\n\n")
            }
          ) match {
            case Right(response) =>
              // Send final response with scene data
              val finalData = ujson.Obj(
                "complete" -> true,
                "scene" -> (response.scene match {
                  case Some(s) => sceneToJson(s)
                  case None => ujson.Null
                }),
                "hasImage" -> session.engine.shouldGenerateSceneImage(response.text),
                "hasMusic" -> session.engine.shouldGenerateBackgroundMusic(response.text),
                "messageIndex" -> session.engine.getMessageCount
              )
              queue.offer(s"event: complete\ndata: ${finalData.render()}\n\n")
            case Left(error) =>
              queue.offer(s"event: error\ndata: ${ujson.Obj("error" -> error.message).render()}\n\n")
          }
          done = true
        }
        
        def hasNext: Boolean = !done || !queue.isEmpty
        def next(): String = {
          if (queue.isEmpty && !done) {
            Thread.sleep(10)  // Small delay to avoid busy waiting
          }
          Option(queue.poll()).getOrElse("")
        }
      }
      
      cask.Response(
        stream,
        headers = Seq(
          "Content-Type" -> "text/event-stream",
          "Cache-Control" -> "no-cache",
          "Connection" -> "keep-alive",
          "X-Accel-Buffering" -> "no"  // Disable nginx buffering
        )
      )
      
    case None =>
      cask.Response(
        s"event: error\ndata: ${ujson.Obj("error" -> "Session not found").render()}\n\n",
        statusCode = 404,
        headers = Seq("Content-Type" -> "text/event-stream")
      )
  }
}
```

### Frontend Changes

#### 1. SSE Client Service

Create `services/StreamingService.ts`:
```typescript
export class StreamingService {
  private eventSource: EventSource | null = null;
  
  streamCommand(
    sessionId: string,
    command: string,
    imageGenerationEnabled: boolean,
    callbacks: {
      onChunk: (text: string) => void;
      onComplete: (data: CompleteResponse) => void;
      onError: (error: string) => void;
    }
  ): void {
    // Close any existing connection
    this.close();
    
    // Create POST request body
    const body = JSON.stringify({
      sessionId,
      command,
      imageGenerationEnabled
    });
    
    // Use fetch with ReadableStream for POST + SSE
    fetch('/api/game/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body
    }).then(response => {
      const reader = response.body?.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      
      const processStream = async () => {
        while (true) {
          const { done, value } = await reader!.read();
          if (done) break;
          
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';
          
          for (const line of lines) {
            if (line.startsWith('event: ')) {
              const event = line.substring(7);
              const nextLine = lines[lines.indexOf(line) + 1];
              
              if (nextLine?.startsWith('data: ')) {
                const data = JSON.parse(nextLine.substring(6));
                
                switch (event) {
                  case 'chunk':
                    callbacks.onChunk(data.text);
                    break;
                  case 'complete':
                    callbacks.onComplete(data);
                    break;
                  case 'error':
                    callbacks.onError(data.error);
                    break;
                }
              }
            }
          }
        }
      };
      
      processStream().catch(callbacks.onError);
    }).catch(callbacks.onError);
  }
  
  close(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
}
```

#### 2. Progressive Message Display

Update `GameView.vue`:
```typescript
// Add streaming support to sendCommand
const sendCommandStreaming = async () => {
  const command = userInput.value.trim();
  if (!command || !sessionId.value) return;

  // Add user message
  messages.value.push({
    text: `> ${command}`,
    type: "user",
  });

  // Create streaming message placeholder
  const streamingMessage: GameMessage = {
    text: "",
    type: "game",
    streaming: true
  };
  messages.value.push(streamingMessage);
  const messageIdx = messages.value.length - 1;

  userInput.value = "";
  loading.value = true;

  const streamingService = new StreamingService();
  
  streamingService.streamCommand(
    sessionId.value,
    command,
    imageGenerationEnabled.value,
    {
      onChunk: (text: string) => {
        // Append text to streaming message
        streamingMessage.text += text;
        scrollToBottom();
      },
      onComplete: (data) => {
        // Mark streaming complete
        streamingMessage.streaming = false;
        streamingMessage.scene = data.scene;
        streamingMessage.messageIndex = data.messageIndex;
        
        // Handle image/music generation
        if (data.hasImage) {
          streamingMessage.imageLoading = true;
          pollForImage(sessionId.value!, data.messageIndex, messageIdx);
        }
        if (data.hasMusic) {
          pollForMusic(sessionId.value!, data.messageIndex);
        }
        
        loading.value = false;
      },
      onError: (error) => {
        messages.value.push({
          text: `Error: ${error}`,
          type: "system",
        });
        loading.value = false;
      }
    }
  );
};
```

#### 3. UI Enhancements

- Add streaming indicator (animated dots or cursor)
- Show partial text as it arrives
- Smooth text rendering without flicker
- Handle interruption/cancellation

## Implementation Checklist

### Phase 1: Backend Streaming Infrastructure (Week 1)
- [ ] Create `StreamingAgent` class extending `Agent`
- [ ] Implement `runStepStreaming` using `client.streamComplete()`
- [ ] Create `StreamingResponseHandler` trait
- [ ] Add SSE support to Cask server
- [ ] Create `/api/game/stream/command` endpoint
- [ ] Handle streaming for text responses
- [ ] Maintain tool call handling compatibility
- [ ] Add proper error handling and cleanup
- [ ] Test streaming with different LLM providers

### Phase 2: Frontend Streaming Support (Week 1)
- [ ] Create `StreamingService` class
- [ ] Implement EventSource connection management
- [ ] Add progressive text rendering in `GameView.vue`
- [ ] Handle connection errors and retries
- [ ] Add streaming visual indicators
- [ ] Implement stream cancellation
- [ ] Test browser compatibility

### Phase 3: Enhanced Features (Week 2)
- [ ] Stream scene data separately from text
- [ ] Progressive inventory updates
- [ ] Streaming audio generation (if text-to-speech supports it)
- [ ] Add streaming toggle in UI settings
- [ ] Performance optimization for long streams
- [ ] Add stream buffering for smoother display

### Phase 4: Polish and Optimization (Week 2)
- [ ] Optimize chunk size for best UX
- [ ] Add backpressure handling
- [ ] Implement connection pooling
- [ ] Add comprehensive error recovery
- [ ] Performance testing with various network conditions
- [ ] Documentation and examples

## Technical Considerations

### 1. Chunk Size Optimization
- Balance between responsiveness and efficiency
- Target 20-50 character chunks for smooth display
- Buffer very small chunks to reduce overhead

### 2. Error Handling
- Network interruptions
- LLM streaming failures
- Client disconnections
- Timeout handling
- Graceful fallback to non-streaming

### 3. State Management
- Maintain consistency between streamed and final state
- Handle partial responses gracefully
- Ensure tool calls complete atomically

### 4. Performance
- Minimize latency between LLM and client
- Efficient string concatenation on frontend
- Avoid UI re-renders on each chunk
- Use requestAnimationFrame for smooth updates

### 5. Testing Strategy
- Unit tests for streaming components
- Integration tests with mock LLM
- End-to-end tests with real LLM
- Network condition simulation
- Browser compatibility testing

## Migration Strategy

1. **Parallel Implementation**: Both streaming and non-streaming endpoints coexist
2. **Feature Flag**: Add client-side flag to enable/disable streaming
3. **Gradual Rollout**: Test with subset of users first
4. **Monitoring**: Track streaming performance and errors
5. **Full Migration**: Make streaming default after stability confirmed

## Success Metrics

- **Time to First Byte (TTFB)**: < 500ms
- **Streaming Latency**: < 100ms between chunks
- **User Perceived Performance**: 50%+ improvement
- **Error Rate**: < 0.1% streaming failures
- **Browser Support**: 95%+ of users

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Browser incompatibility | High | Fallback to polling, feature detection |
| Network instability | Medium | Automatic reconnection, buffering |
| Increased server load | Medium | Connection pooling, rate limiting |
| Complex state management | High | Comprehensive testing, gradual rollout |
| LLM streaming failures | Low | Fallback to non-streaming mode |

## Code Quality Guidelines

### Clean Code Principles
- **Single Responsibility**: Each component handles one aspect of streaming
- **Dependency Injection**: Make streaming handlers pluggable
- **Interface Segregation**: Separate streaming from non-streaming interfaces
- **Error Recovery**: All streaming operations must be recoverable

### Best Practices
- Use immutable data structures for stream events
- Implement proper resource cleanup (connections, listeners)
- Add comprehensive logging for debugging
- Use type-safe contracts between frontend and backend
- Document streaming protocol and event formats

## Future Enhancements

1. **Streaming Voice Synthesis**: Stream audio as text is generated
2. **Predictive Image Generation**: Start image generation before text completes
3. **Collaborative Streaming**: Multiple users see same stream
4. **Stream Recording**: Save and replay game streams
5. **Adaptive Streaming**: Adjust chunk size based on network conditions

## References

- [Server-Sent Events Specification](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [LLM4S Streaming Documentation](../src/main/scala/org/llm4s/llmconnect/streaming/)
- [Cask HTTP Framework](https://com-lihaoyi.github.io/cask/)
- [Vue 3 Composition API](https://vuejs.org/guide/extras/composition-api-faq.html)