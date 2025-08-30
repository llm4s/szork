// Quick Start with LLM4S
import org.llm4s.llmconnect.LLM
import org.llm4s.agent.Agent

// Initialize client (uses env vars)
val client = LLM.client()

// Create a simple agent
val agent = new Agent(client)

// Start conversation
val response = agent.chat(
  "Tell me about Scala's type system"
)

// Type-safe response handling
response match {
  case Right(message) => 
    println(s"AI: ${message.content}")
  case Left(error) => 
    println(s"Error: ${error.message}")
}