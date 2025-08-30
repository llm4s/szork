package example

import org.llm4s.agent.Agent

import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.config.EnvLoader

import scala.io.StdIn.readLine

object SzorkMain {

  def main(args: Array[String]): Unit = {

    val client = LLM.client(EnvLoader)
    val toolRegistry = new ToolRegistry(Nil)

    // Create an agent
    val agent = new Agent(client)


    val prompt =
      """You are a Dungeon Master guiding a fantasy text adventure game.
        |Describe the current scene then as the player takes actions track their progress
        |and manage their actions - e.g. if they say 'go north' and there is no north keep them
        |in the current location and provide an appropriate message.
        |Keep track of the player's state, location, and inventory in memory.
        |At each stem, provide a description of the current scene and any relevant information.
        |If the player asks for help, provide a brief overview of the game mechanics.
        |If the player asks for a hint, provide a hint that is relevant to the current
        |scene or situation.
        |If the player asks for a summary of their current state, provide a summary
        |of their current location, inventory, and any relevant information.
        |If the player asks for a list of available actions, provide a list of actions
        |that are available in the current scene.
        |""".stripMargin

    var currentState = agent.initialize(
      "Lets go!",
      toolRegistry,
      systemPromptAddition = Some(prompt),
    )

    println("You are at the entrance to a dark cave.\n>")

    var continue = true
    while (continue) {
      val userInput = readLine("> ")
      if (userInput.toLowerCase.trim == "quit") {
        println("Goodbye, adventurer.")
        continue = false
      } else {
        print("User input received: " + userInput)
        print("...")
        currentState = currentState.addMessage(UserMessage(
          content = userInput))

        println("Running agent - step count: " + currentState.conversation.messages.length)
        val response = agent.run(currentState)
        response match {
          case Right(newState) =>
            currentState = newState
            println("Agent response received- step count: " + currentState.conversation.messages.length)
            println(s"\n${currentState.conversation.messages.last.content}\n>")

          case Left(error) =>
            println(s"Error: $error")
        }
      }
    }
  }
}

