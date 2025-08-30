package org.llm4s.szork

import scala.io.StdIn.readLine

object SzorkMain {

  def main(args: Array[String]): Unit = {
    // Create the game engine
    val gameEngine = GameEngine.create()
    
    // Initialize the game
    val initialMessage = gameEngine.initialize()
    println(initialMessage)
    print("> ")

    var continue = true
    while (continue) {
      val userInput = readLine()
      if (userInput.toLowerCase.trim == "quit") {
        println("Goodbye, adventurer.")
        continue = false
      } else {
        println(s"User input received: $userInput")
        print("Processing...")
        
        gameEngine.processCommand(userInput, generateAudio = false) match {
          case Right(response) =>
            println(s"\n${response.text}")
            print("> ")
            
          case Left(error) =>
            println(s"\nError: $error")
            print("> ")
        }
      }
    }
  }
}

