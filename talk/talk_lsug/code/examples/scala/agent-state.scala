// Immutable Agent State Management from SZork
class GameEngine(sessionId: String, theme: String, artStyle: String) {
  
  // Immutable state representation
  case class AgentState(
    conversation: ConversationHistory,
    status: AgentStatus,
    currentScene: Option[GameScene],
    visitedLocations: Set[String]
  )
  
  // Every action creates a new state
  def processCommand(command: String, state: AgentState): AgentState = {
    val newConversation = state.conversation.addMessage(
      UserMessage(command)
    )
    
    // Call LLM with full context
    val response = agent.run(newConversation)
    
    // Return completely new state
    state.copy(
      conversation = newConversation.addMessage(response),
      currentScene = parseScene(response),
      visitedLocations = state.visitedLocations + sceneId
    )
  }
}