package example

import llm4s.api.Agent
import llm4s.api.LLMClient

import scala.io.StdIn.readLine

object SzorkMain {

  def main(args: Array[String]): Unit = {
    val prompt =
      """You are a Dungeon Master guiding a fantasy text adventure. 
        |Describe the current scene vividly, and wait for the player's input.
        |Keep track of the player's state, location, and inventory in memory.
        |Respond to each input with a description of the result and what's next.
        |""".stripMargin

    val agent = Agent(systemPrompt = prompt)

    println("You are at the entrance to a dark cave.\n>")

    var continue = true
    while (continue) {
      val userInput = readLine("> ")

      if (userInput.toLowerCase.trim == "quit") {
        println("Goodbye, adventurer.")
        continue = false
      } else {
        val response = agent.run(userInput)
        println(response)
      }
    }
  }
}

