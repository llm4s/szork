package org.llm4s.szork

import ujson._
import org.llm4s.llmconnect.model._

/** JSON codecs for step-based persistence data structures. */

object GameMetadataCodec {
  def toJson(metadata: GameMetadata): Value = ujson.Obj(
    "gameId" -> ujson.Str(metadata.gameId),
    "theme" -> ujson.Str(metadata.theme),
    "artStyle" -> ujson.Str(metadata.artStyle),
    "adventureTitle" -> ujson.Str(metadata.adventureTitle),
    "createdAt" -> ujson.Num(metadata.createdAt.toDouble),
    "lastSaved" -> ujson.Num(metadata.lastSaved.toDouble),
    "lastPlayed" -> ujson.Num(metadata.lastPlayed.toDouble),
    "totalPlayTime" -> ujson.Num(metadata.totalPlayTime.toDouble),
    "currentStep" -> ujson.Num(metadata.currentStep),
    "totalSteps" -> ujson.Num(metadata.totalSteps)
  )

  def fromJson(json: Value): GameMetadata = GameMetadata(
    gameId = json("gameId").str,
    theme = json("theme").str,
    artStyle = json("artStyle").str,
    adventureTitle = json("adventureTitle").str,
    createdAt = json("createdAt").num.toLong,
    lastSaved = json("lastSaved").num.toLong,
    lastPlayed = json("lastPlayed").num.toLong,
    totalPlayTime = json("totalPlayTime").num.toLong,
    currentStep = json.obj.get("currentStep").map(_.num.toInt).getOrElse(1),
    totalSteps = json.obj.get("totalSteps").map(_.num.toInt).getOrElse(1)
  )
}

object StepMetadataCodec {
  def toJson(metadata: StepMetadata): Value = ujson.Obj(
    "gameId" -> ujson.Str(metadata.gameId),
    "stepNumber" -> ujson.Num(metadata.stepNumber),
    "timestamp" -> ujson.Num(metadata.timestamp.toDouble),
    "userCommand" -> metadata.userCommand.map(ujson.Str(_)).getOrElse(ujson.Null),
    "responseLength" -> ujson.Num(metadata.responseLength),
    "toolCallCount" -> ujson.Num(metadata.toolCallCount),
    "messageCount" -> ujson.Num(metadata.messageCount),
    "success" -> ujson.Bool(metadata.success),
    "error" -> metadata.error.map(ujson.Str(_)).getOrElse(ujson.Null),
    "executionTimeMs" -> ujson.Num(metadata.executionTimeMs.toDouble)
  )

  def fromJson(json: Value): StepMetadata = StepMetadata(
    gameId = json("gameId").str,
    stepNumber = json("stepNumber").num.toInt,
    timestamp = json("timestamp").num.toLong,
    userCommand = json.obj.get("userCommand").flatMap {
      case Null => None
      case s => Some(s.str)
    },
    responseLength = json("responseLength").num.toInt,
    toolCallCount = json("toolCallCount").num.toInt,
    messageCount = json("messageCount").num.toInt,
    success = json("success").bool,
    error = json.obj.get("error").flatMap {
      case Null => None
      case s => Some(s.str)
    },
    executionTimeMs = json.obj.get("executionTimeMs").map(_.num.toLong).getOrElse(0L)
  )
}

object ToolCallCodec {
  def toJson(toolCall: ToolCallInfo): Value = ujson.Obj(
    "id" -> ujson.Str(toolCall.id),
    "name" -> ujson.Str(toolCall.name),
    "arguments" -> toolCall.arguments,
    "result" -> toolCall.result.map(ujson.Str(_)).getOrElse(ujson.Null),
    "timestamp" -> ujson.Num(toolCall.timestamp.toDouble)
  )

  def fromJson(json: Value): ToolCallInfo = ToolCallInfo(
    id = json("id").str,
    name = json("name").str,
    arguments = json("arguments"),
    result = json.obj.get("result").flatMap {
      case Null => None
      case s => Some(s.str)
    },
    timestamp = json("timestamp").num.toLong
  )
}

object MessageCodec {
  def toJson(message: Message): Value = message match {
    case UserMessage(content) =>
      ujson.Obj(
        "type" -> "user",
        "content" -> content
      )

    case AssistantMessage(contentOpt, toolCalls) =>
      ujson.Obj(
        "type" -> "assistant",
        "content" -> contentOpt.map(ujson.Str(_)).getOrElse(ujson.Null),
        "toolCalls" -> ujson.Arr(toolCalls.map { tc =>
          ujson.Obj(
            "id" -> tc.id,
            "name" -> tc.name,
            "arguments" -> tc.arguments
          )
        }: _*)
      )

    case SystemMessage(content) =>
      ujson.Obj(
        "type" -> "system",
        "content" -> content
      )

    case ToolMessage(toolCallId, content) =>
      ujson.Obj(
        "type" -> "tool",
        "toolCallId" -> toolCallId,
        "content" -> content
      )
  }

  def fromJson(json: Value): Message = {
    json("type").str match {
      case "user" =>
        UserMessage(content = json("content").str)

      case "assistant" =>
        val contentOpt = json.obj.get("content").flatMap {
          case Null => None
          case s => Some(s.str)
        }
        val toolCalls = json.obj.get("toolCalls") match {
          case Some(arr) => arr.arr.map { tc =>
            ToolCall(
              id = tc("id").str,
              name = tc("name").str,
              arguments = tc("arguments")
            )
          }.toList
          case None => List.empty
        }
        AssistantMessage(contentOpt, toolCalls)

      case "system" =>
        SystemMessage(content = json("content").str)

      case "tool" =>
        ToolMessage(
          toolCallId = json("toolCallId").str,
          content = json("content").str
        )

      case other =>
        throw new IllegalArgumentException(s"Unknown message type: $other")
    }
  }
}
