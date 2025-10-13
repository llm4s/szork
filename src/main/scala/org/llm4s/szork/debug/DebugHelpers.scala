package org.llm4s.szork.debug

import org.llm4s.szork.game._
import org.llm4s.szork.persistence.{StepData, ToolCallInfo, SceneResponse, ActionResponse}
import org.llm4s.llmconnect.model._

/** Debugging utilities for game development and testing.
  *
  * These utilities provide console output and convenience functions
  * for debugging game sessions. The core persistence logic is now
  * handled by StepPersistence.
  */
object DebugHelpers {

  /** Extract tool calls from agent messages.
    *
    * Scans through messages to find tool calls and their results,
    * matching ToolMessages with their corresponding AssistantMessage tool calls.
    *
    * @param messages Sequence of agent messages
    * @return List of tool call info with results
    */
  def extractToolCalls(messages: Seq[Message]): List[ToolCallInfo] = {
    val toolCalls = scala.collection.mutable.ListBuffer[ToolCallInfo]()

    messages.foreach {
      case AssistantMessage(_, calls) if calls.nonEmpty =>
        calls.foreach { tc =>
          toolCalls += ToolCallInfo(
            id = tc.id,
            name = tc.name,
            arguments = tc.arguments,
            result = None,
            timestamp = System.currentTimeMillis()
          )
        }
      case ToolMessage(toolCallId, content) =>
        // Update the result for the matching tool call
        val idx = toolCalls.indexWhere(_.id == toolCallId)
        if (idx >= 0) {
          val existing = toolCalls(idx)
          toolCalls(idx) = existing.copy(result = Some(content))
        }
      case _ => // Ignore other message types
    }

    toolCalls.toList
  }

  /** Format conversation as human-readable text.
    *
    * Produces a formatted text output of the conversation history
    * suitable for logging or manual review.
    *
    * @param messages Sequence of agent messages
    * @return Formatted conversation string
    */
  def formatConversation(messages: Seq[Message]): String = {
    val sb = new StringBuilder()
    sb.append("=== CONVERSATION HISTORY ===\n\n")

    messages.zipWithIndex.foreach { case (msg, idx) =>
      sb.append(s"[Message ${idx + 1}] ")
      msg match {
        case SystemMessage(content) =>
          sb.append("SYSTEM:\n")
          sb.append(s"${content.take(200)}...\n\n")

        case UserMessage(content) =>
          sb.append("USER:\n")
          sb.append(s"$content\n\n")

        case AssistantMessage(contentOpt, toolCalls) =>
          sb.append("ASSISTANT:\n")
          contentOpt.foreach(c => sb.append(s"$c\n"))
          if (toolCalls.nonEmpty) {
            sb.append("Tool Calls:\n")
            toolCalls.foreach { tc =>
              sb.append(s"  - ${tc.name}(${ujson.write(tc.arguments, indent = 0)})\n")
            }
          }
          sb.append("\n")

        case ToolMessage(toolCallId, content) =>
          sb.append(s"TOOL RESULT (id=$toolCallId):\n")
          sb.append(s"$content\n\n")
      }
    }

    sb.toString()
  }

  /** Print step summary to console.
    *
    * Displays a formatted summary of a game step including:
    * - User command
    * - Narration text
    * - Scene details (location, exits, items, NPCs)
    * - Tool calls
    * - Metadata
    *
    * @param stepData Complete step data to summarize
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
      case Some(ActionResponse(_, locationId, action)) =>
        println(s"\nAction: $action at $locationId")
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
    * - Adventure title and tagline
    * - Main quest
    * - Key locations
    * - Initial scene
    *
    * @param gameId Game identifier
    * @param outline Adventure outline
    * @param initialScene Initial game scene
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
