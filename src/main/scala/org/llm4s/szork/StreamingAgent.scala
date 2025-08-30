package org.llm4s.szork

import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Extension of Agent that supports streaming responses.
 * Only streams user-facing text, not tool calls or internal operations.
 */
class StreamingAgent(client: LLMClient) extends Agent(client) {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  
  /**
   * Run a single step of the agent's reasoning process with streaming support.
   * 
   * @param state The current agent state
   * @param onUserText Callback for streaming user-facing text chunks
   * @return The updated agent state
   */
  def runStepStreaming(
    state: AgentState,
    onUserText: String => Unit
  ): Result[AgentState] = {
    state.status match {
      case AgentStatus.InProgress =>
        handleInProgressStreaming(state, onUserText)
        
      case AgentStatus.WaitingForTools =>
        // Process tools without streaming (internal operations)
        logger.debug("Processing tools without streaming")
        super.runStep(state)
        
      case _ =>
        // If the agent is already complete or failed, don't do anything
        Right(state)
    }
  }
  
  /**
   * Handle the InProgress state with streaming support
   */
  private def handleInProgressStreaming(
    state: AgentState,
    onUserText: String => Unit
  ): Result[AgentState] = {
    val detector = new StreamingResponseDetector()
    val options = CompletionOptions(tools = state.tools.tools)
    
    logger.debug("Running streaming completion step with tools: {}", 
                 state.tools.tools.map(_.name).mkString(", "))
    
    var isUserResponse = false
    var hasToolCalls = false
    
    // Request streaming completion from LLM
    client.streamComplete(state.conversation, options, chunk => {
      detector.processChunk(chunk) match {
        case (detector.UserText, Some(text)) =>
          // Stream user-facing text to frontend
          logger.debug(s"Streaming text chunk: ${text.take(50)}...")
          onUserText(text)
          isUserResponse = true
          
        case (detector.ToolCall, _) =>
          // Tool call detected - don't stream
          logger.debug("Tool call detected, not streaming to user")
          hasToolCalls = true
          isUserResponse = false
          
        case (detector.Unknown, _) =>
          // Still detecting type, buffer internally
          logger.debug("Response type still unknown, buffering")
          
        case _ =>
          // No text to stream in this chunk
      }
    }) match {
      case Right(completion) =>
        // TEMPORARY: Log complete LLM response to console
        logger.info(s"[DEBUG] Complete LLM Response for user command:")
        logger.info(s"[DEBUG] Content: ${completion.message.content}")
        logger.info(s"[DEBUG] Tool Calls: ${completion.message.toolCalls}")
        logger.info(s"[DEBUG] Token Usage - Prompt: ${completion.usage.map(_.promptTokens)}, Completion: ${completion.usage.map(_.completionTokens)}, Total: ${completion.usage.map(_.totalTokens)}")
        
        val logMessage = completion.message.toolCalls match {
          case Seq() => 
            s"[assistant] streaming text: ${completion.message.content.take(100)}"
          case toolCalls =>
            val toolNames = toolCalls.map(_.name).mkString(", ")
            s"[assistant] tools: ${toolCalls.size} tool calls requested (${toolNames})"
        }
        
        val updatedState = state
          .log(logMessage)
          .addMessage(completion.message)
        
        // Determine next state based on what we detected
        if (hasToolCalls || completion.message.toolCalls.nonEmpty) {
          // Tool calls detected, need to process them
          logger.debug("Tool calls identified, setting state to waiting for tools")
          Right(updatedState.withStatus(AgentStatus.WaitingForTools))
        } else if (isUserResponse) {
          // User-facing response streamed successfully
          logger.debug("User response streamed, marking complete")
          Right(updatedState.withStatus(AgentStatus.Complete))
        } else {
          // Edge case: no tool calls but also no user text
          logger.warn("No tool calls or user text detected, marking complete")
          Right(updatedState.withStatus(AgentStatus.Complete))
        }
        
      case Left(error) =>
        logger.error(s"Streaming completion failed: ${error.message}")
        Left(error)
    }
  }
  
  /**
   * Run the agent until completion with streaming support.
   * Handles the multi-step nature of agent reasoning while only
   * streaming user-facing responses.
   * 
   * @param initialState The initial agent state
   * @param onUserText Callback for streaming user-facing text
   * @return The final agent state
   */
  def runStreaming(
    initialState: AgentState,
    onUserText: String => Unit
  ): Result[AgentState] = {
    @annotation.tailrec
    def runLoop(state: AgentState, stepCount: Int = 0): Result[AgentState] = {
      if (stepCount > 20) {
        logger.error("Agent exceeded maximum steps (20)")
        return Right(state.withStatus(AgentStatus.Failed("Maximum steps exceeded")))
      }
      
      logger.debug(s"Running agent step $stepCount, status: ${state.status}")
      
      runStepStreaming(state, onUserText) match {
        case Right(newState) =>
          newState.status match {
            case AgentStatus.InProgress | AgentStatus.WaitingForTools =>
              // Continue processing
              runLoop(newState, stepCount + 1)
              
            case AgentStatus.Complete =>
              // Agent completed successfully
              logger.info(s"Agent completed after $stepCount steps")
              Right(newState)
              
            case AgentStatus.Failed(reason) =>
              // Agent failed
              logger.error(s"Agent failed: $reason")
              Right(newState)
              
            case _ =>
              // Unknown status
              logger.warn(s"Unknown agent status: ${newState.status}")
              Right(newState)
          }
          
        case Left(error) =>
          logger.error(s"Agent step failed: ${error.message}")
          Left(error)
      }
    }
    
    runLoop(initialState)
  }
}