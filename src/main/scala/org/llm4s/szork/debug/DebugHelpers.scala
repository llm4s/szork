package org.llm4s.szork.debug

import org.llm4s.szork._
import org.llm4s.szork.error._
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory
import ujson._
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import scala.util.{Try, Success, Failure}

object DebugHelpers {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val RUNS_DIR = "runs"

  case class ToolCallInfo(
    id: String,
    name: String,
    arguments: ujson.Value,
    result: Option[String],
    timestamp: Long
  )

  case class StepMetadata(
    sessionName: String,
    stepNumber: Int,
    timestamp: Long,
    userCommand: Option[String],
    responseLength: Int,
    toolCallCount: Int,
    messageCount: Int,
    success: Boolean,
    error: Option[String] = None
  )

  /** Ensure the runs directory exists */
  def ensureRunsDir(): Path = {
    val dir = Paths.get(RUNS_DIR)
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      logger.info(s"Created runs directory: $RUNS_DIR")
    }
    dir
  }

  /** Create a step directory for a session */
  def createStepDir(sessionName: String, step: Int): Path = {
    ensureRunsDir()
    val sessionDir = Paths.get(RUNS_DIR, sessionName)
    val stepDir = sessionDir.resolve(s"step-$step")
    Files.createDirectories(stepDir)
    logger.info(s"Created step directory: ${stepDir.toAbsolutePath}")
    stepDir
  }

  /** Get step directory for reading */
  def getStepDir(sessionName: String, step: Int): Path = {
    val stepDir = Paths.get(RUNS_DIR, sessionName, s"step-$step")
    if (!Files.exists(stepDir)) {
      throw new IllegalArgumentException(s"Step directory does not exist: ${stepDir.toAbsolutePath}")
    }
    stepDir
  }

  /** Save JSON with pretty printing */
  def saveJson(path: Path, data: ujson.Value): Unit = {
    val jsonString = ujson.write(data, indent = 2)
    Files.write(path, jsonString.getBytes(StandardCharsets.UTF_8))
    logger.debug(s"Saved JSON to: ${path.getFileName}")
  }

  /** Save text content */
  def saveText(path: Path, content: String): Unit = {
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    logger.debug(s"Saved text to: ${path.getFileName}")
  }

  /** Load JSON from file */
  def loadJson(path: Path): ujson.Value = {
    val jsonString = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    ujson.read(jsonString)
  }

  /** Load text from file */
  def loadText(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  /** Extract tool calls from agent messages */
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

  /** Format tool calls as JSON */
  def toolCallsToJson(toolCalls: List[ToolCallInfo]): ujson.Value =
    ujson.Arr(toolCalls.map { tc =>
      ujson.Obj(
        "id" -> tc.id,
        "name" -> tc.name,
        "arguments" -> tc.arguments,
        "result" -> tc.result.map(ujson.Str(_)).getOrElse(ujson.Null),
        "timestamp" -> tc.timestamp
      )
    }: _*)

  /** Format conversation as human-readable text */
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

  /** Print step summary to console */
  def printStepSummary(
    step: Int,
    userCommand: Option[String],
    response: org.llm4s.szork.GameEngine#GameResponse,
    toolCalls: List[ToolCallInfo],
    metadata: StepMetadata
  ): Unit = {
    println("\n" + "=" * 80)
    println(s"STEP $step SUMMARY")
    println("=" * 80)

    userCommand.foreach { cmd =>
      println(s"\nUser Command: $cmd")
    }

    println(s"\nResponse (${response.text.length} chars):")
    println("-" * 80)
    println(response.text)
    println("-" * 80)

    if (response.scene.isDefined) {
      val scene = response.scene.get
      println(s"\nScene: ${scene.locationName} (${scene.locationId})")
      println(s"Exits: ${scene.exits.map(_.direction).mkString(", ")}")
      if (scene.items.nonEmpty) println(s"Items: ${scene.items.mkString(", ")}")
      if (scene.npcs.nonEmpty) println(s"NPCs: ${scene.npcs.mkString(", ")}")
    }

    if (toolCalls.nonEmpty) {
      println(s"\nTool Calls (${toolCalls.length}):")
      toolCalls.foreach { tc =>
        println(s"  - ${tc.name}")
        println(s"    Args: ${ujson.write(tc.arguments, indent = 0)}")
        tc.result.foreach(r => println(s"    Result: $r"))
      }
    }

    println(s"\nMetadata:")
    println(s"  Messages: ${metadata.messageCount}")
    println(s"  Success: ${metadata.success}")
    metadata.error.foreach(e => println(s"  Error: $e"))

    println("\n" + "=" * 80 + "\n")
  }

  /** Print adventure creation summary */
  def printAdventureSummary(
    sessionName: String,
    outline: AdventureOutline,
    initialScene: GameScene
  ): Unit = {
    println("\n" + "=" * 80)
    println(s"ADVENTURE CREATED: $sessionName")
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

    println(s"\nFiles saved to: runs/$sessionName/step-1/")
    println("=" * 80 + "\n")
  }

  /** Create metadata JSON */
  def metadataToJson(metadata: StepMetadata): ujson.Value =
    ujson.Obj(
      "sessionName" -> metadata.sessionName,
      "stepNumber" -> metadata.stepNumber,
      "timestamp" -> metadata.timestamp,
      "userCommand" -> metadata.userCommand.map(ujson.Str(_)).getOrElse(ujson.Null),
      "responseLength" -> metadata.responseLength,
      "toolCallCount" -> metadata.toolCallCount,
      "messageCount" -> metadata.messageCount,
      "success" -> metadata.success,
      "error" -> metadata.error.map(ujson.Str(_)).getOrElse(ujson.Null)
    )

  /** Save all step data */
  def saveStepData(
    stepDir: Path,
    metadata: StepMetadata,
    gameState: GameState,
    response: Option[org.llm4s.szork.GameEngine#GameResponse] = None,
    userCommand: Option[String] = None,
    toolCalls: List[ToolCallInfo] = Nil,
    outline: Option[AdventureOutline] = None,
    agentMessages: Option[Seq[Message]] = None
  ): Unit = {
    // Save metadata
    saveJson(stepDir.resolve("metadata.json"), metadataToJson(metadata))

    // Save game state
    saveJson(stepDir.resolve("game-state.json"), GameStateCodec.toJson(gameState))

    // Save user command if present
    userCommand.foreach(cmd => saveText(stepDir.resolve("user-command.txt"), cmd))

    // Save response if present
    response.foreach { resp =>
      val responseJson = ujson.Obj(
        "text" -> resp.text,
        "scene" -> resp.scene.map(s => ujson.read(GameScene.toJson(s))).getOrElse(ujson.Null)
      )
      saveJson(stepDir.resolve("response.json"), responseJson)
    }

    // Save tool calls if present
    if (toolCalls.nonEmpty) {
      saveJson(stepDir.resolve("tool-calls.json"), toolCallsToJson(toolCalls))
    }

    // Save adventure outline if present
    outline.foreach { o =>
      saveJson(stepDir.resolve("adventure-outline.json"), AdventureGenerator.outlineToJson(o))
    }

    // Save agent messages if present
    agentMessages.foreach { messages =>
      val messagesJson = messages.map { msg =>
        msg match {
          case UserMessage(content) =>
            ujson.Obj("type" -> "user", "content" -> content)
          case AssistantMessage(contentOpt, toolCalls) =>
            ujson.Obj(
              "type" -> "assistant",
              "content" -> contentOpt.map(ujson.Str(_)).getOrElse(ujson.Null),
              "toolCalls" -> toolCalls.map(tc =>
                ujson.Obj(
                  "id" -> tc.id,
                  "name" -> tc.name,
                  "arguments" -> tc.arguments
                ))
            )
          case SystemMessage(content) =>
            ujson.Obj("type" -> "system", "content" -> (content.take(500) + "..."))
          case ToolMessage(toolCallId, content) =>
            ujson.Obj(
              "type" -> "tool",
              "toolCallId" -> toolCallId,
              "content" -> content
            )
        }
      }
      saveJson(stepDir.resolve("agent-messages.json"), ujson.Arr(messagesJson: _*))

      // Also save human-readable conversation
      saveText(stepDir.resolve("conversation.txt"), formatConversation(messages))
    }
  }
}
