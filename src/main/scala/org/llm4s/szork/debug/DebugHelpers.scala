package org.llm4s.szork.debug

import org.llm4s.szork.game._
import org.llm4s.szork.persistence.{StepData, SceneResponse}

/** Console summaries for the debug tools ([[CreateAdventure]], [[RunUserStep]]). */
object DebugHelpers {

  /** Print step summary to console.
    *
    * Displays a formatted summary of a game step including:
    *   - User command
    *   - Narration text
    *   - Scene details (location, exits, items, NPCs)
    *   - Tool calls
    *   - Metadata
    *
    * @param stepData
    *   Complete step data to summarize
    */
  def printStepSummary(stepData: StepData): Unit = {
    val metadata = stepData.metadata

    println("\n" + "=" * 80)
    println(s"STEP ${metadata.stepNumber} SUMMARY")
    println("=" * 80)

    stepData.userCommand.foreach { cmd =>
      println(s"\nUser Command: $cmd")
    }

    println(s"\nNarration (${stepData.narrationText.length} chars):")
    println("-" * 80)
    println(stepData.narrationText)
    println("-" * 80)

    stepData.response match {
      case Some(SceneResponse(scene)) =>
        println(s"\nScene: ${scene.locationName} (${scene.locationId})")
        println(s"Exits: ${scene.exits.map(_.direction).mkString(", ")}")
        if (scene.items.nonEmpty) println(s"Items: ${scene.items.mkString(", ")}")
        if (scene.npcs.nonEmpty) println(s"NPCs: ${scene.npcs.mkString(", ")}")
      case None =>
        println("\nNo structured response")
    }

    if (stepData.toolCalls.nonEmpty) {
      println(s"\nTool Calls (${stepData.toolCalls.length}):")
      stepData.toolCalls.foreach { tc =>
        println(s"  - ${tc.name}")
        println(s"    Args: ${ujson.write(tc.arguments, indent = 0)}")
        tc.result.foreach(r => println(s"    Result: $r"))
      }
    }

    println(s"\nMetadata:")
    println(s"  Messages: ${metadata.messageCount}")
    println(s"  Execution Time: ${metadata.executionTimeMs}ms")
    println(s"  Success: ${metadata.success}")
    metadata.error.foreach(e => println(s"  Error: $e"))

    println("\n" + "=" * 80 + "\n")
  }

  /** Print adventure creation summary to console.
    *
    * Displays a formatted summary of a newly created adventure including:
    *   - Adventure title and tagline
    *   - Main quest
    *   - Key locations
    *   - Initial scene
    *
    * @param gameId
    *   Game identifier
    * @param outline
    *   Adventure outline
    * @param initialScene
    *   Initial game scene
    */
  def printAdventureSummary(
    gameId: String,
    outline: AdventureOutline,
    initialScene: GameScene
  ): Unit = {
    println("\n" + "=" * 80)
    println(s"ADVENTURE CREATED: $gameId")
    println("=" * 80)

    println(s"\nTitle: ${outline.title}")
    outline.tagline.foreach(t => println(s"Tagline: $t"))

    println(s"\nMain Quest: ${outline.mainQuest}")

    println(s"\nKey Locations (${outline.keyLocations.length}):")
    outline.keyLocations.take(3).foreach { loc =>
      println(s"  - ${loc.name} (${loc.id})")
    }
    if (outline.keyLocations.length > 3) {
      println(s"  ... and ${outline.keyLocations.length - 3} more")
    }

    println(s"\nInitial Scene: ${initialScene.locationName}")
    println("-" * 80)
    println(initialScene.narrationText)
    println("-" * 80)

    println(s"\nFiles saved to: szork-saves/$gameId/step-0001/")
    println("=" * 80 + "\n")
  }
}
