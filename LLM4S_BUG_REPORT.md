# llm4s v0.1.12 Tool Calling Bug Report

## Issue Summary

When using the `Agent` class with tool calling, the library creates an `AssistantMessage` with empty content after a tool executes, which causes subsequent LLM API calls to fail with a 400 error.

**Error Message:**
```
400: {type=error, error={type=invalid_request_error, message=messages.9: all messages must have non-empty content except for the optional final assistant message}}
```

## Affected Version
- **llm4s**: v0.1.12
- **LLM Provider**: Anthropic Claude (confirmed), likely affects all providers

## Root Cause

After a tool executes successfully and returns a result, the Agent creates an `AssistantMessage` with:
- `content = null` or `content = ""` (empty)
- `toolCalls = List()` (empty, since the tool call was already processed)

This violates the Anthropic API requirement that "all messages must have non-empty content except for the optional final assistant message". Since this empty `AssistantMessage` is followed by a `ToolMessage`, it's not the final message and thus causes the API to reject the request.

## Steps to Reproduce

### 1. Create a Tool

```scala
import org.llm4s.toolapi._

object InventoryTools {
  private var inventory: List[String] = List.empty

  val addInventoryItem: Tool[Map[String, String], Map[String, Any]] = Tool(
    name = "add_inventory_item",
    description = "Add an item to the player's inventory",
    parameters = List(
      ToolParameter(
        name = "item",
        parameterType = "string",
        description = "The item to add to inventory",
        required = true
      )
    ),
    handler = { params =>
      val item = params("item")
      inventory = inventory :+ item

      Map(
        "success" -> true,
        "message" -> s"Added '$item' to your inventory",
        "item" -> item,
        "inventory" -> inventory
      )
    }
  )

  val allTools = Seq(addInventoryItem)
}
```

### 2. Create an Agent with Tool Support

```scala
import org.llm4s.agent._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi.ToolRegistry

// Initialize LLM client (Anthropic Claude)
val llmClient: LLMClient = ??? // Your LLM client initialization

// Create tool registry
val toolRegistry = ToolRegistry(InventoryTools.allTools)

// Create agent with tools
val agent = Agent(
  llmClient = llmClient,
  toolRegistry = Some(toolRegistry),
  systemPrompt = Some("You are a game assistant. Use the add_inventory_item tool when the player takes an item.")
)

// Initial state
var agentState = AgentState(
  conversation = Conversation(
    messages = List(
      SystemMessage("You are a game assistant. Use tools as needed."),
      UserMessage("I want to take the sword")
    )
  ),
  status = AgentStatus.InProgress
)
```

### 3. Run the Agent (Triggers Bug)

```scala
// First call - agent decides to use tool
agent.run(agentState) match {
  case Right(newState) =>
    // Tool executes successfully, returns:
    // {"success": true, "message": "Added 'sword' to your inventory", ...}

    // At this point, newState.conversation.messages contains:
    // - Original messages
    // - AssistantMessage with tool_calls requesting add_inventory_item
    // - ToolMessage with the tool result
    // - AssistantMessage with EMPTY CONTENT and no tool calls <-- BUG!

    agentState = newState

    // Second call with user input - THIS FAILS
    val updatedState = agentState.addMessage(UserMessage("What's in my inventory?"))

    agent.run(updatedState) match {
      case Right(finalState) =>
        // Never reaches here
        println("Success")

      case Left(error) =>
        // FAILS HERE with error:
        // "messages.9: all messages must have non-empty content except for
        //  the optional final assistant message"
        println(s"Error: $error")
    }

  case Left(error) =>
    println(s"First call error: $error")
}
```

## Actual Message Sequence (Causing Failure)

After tool execution, the conversation contains:

```
Message 0: UserMessage("I want to take the sword")
Message 1: AssistantMessage(content=null, toolCalls=[ToolCall(id="call_123", name="add_inventory_item", ...)])
Message 2: ToolMessage(toolCallId="call_123", content="{\"success\":true,...}")
Message 3: AssistantMessage(content=null, toolCalls=[])  <-- PROBLEM: Empty content, not final message
Message 4: UserMessage("What's in my inventory?")
```

When `agent.run()` is called again, it sends all these messages to the LLM API, which rejects message 3 because it has empty content and isn't the final message.

## Expected Behavior

**Option 1: Don't create empty AssistantMessage**
After tool execution completes, don't add an `AssistantMessage` with empty content. The conversation should be:

```
Message 0: UserMessage("I want to take the sword")
Message 1: AssistantMessage(content=null, toolCalls=[ToolCall(...)])
Message 2: ToolMessage(toolCallId="call_123", content="{\"success\":true,...}")
Message 3: UserMessage("What's in my inventory?")  <-- No empty assistant message
```

**Option 2: Add content to AssistantMessage**
If the `AssistantMessage` must be created, give it meaningful content:

```
Message 3: AssistantMessage(content="Tool execution complete", toolCalls=[])
```

**Option 3: Filter messages before API call**
Before sending messages to the LLM API, filter out any `AssistantMessage` that has both:
- Empty or null content
- No tool calls

## Technical Details

### Where the Bug Occurs

The bug appears to be in the `Agent.run()` method (or related tool execution flow) in llm4s v0.1.12. After processing tool results:

1. Tool executes successfully ✅
2. `ToolMessage` is added to conversation ✅
3. Empty `AssistantMessage` is added to conversation ❌ (BUG)
4. Next `agent.run()` call sends all messages including empty one ❌

### Anthropic API Requirements

From Anthropic's API documentation:
> All messages must have non-empty content except for the optional final assistant message

This means:
- ✅ `AssistantMessage(content=null, toolCalls=[...])` is valid (has tool calls)
- ✅ `AssistantMessage(content="text", toolCalls=[])` is valid (has content)
- ✅ Final `AssistantMessage(content=null, toolCalls=[])` is valid (final message)
- ❌ Non-final `AssistantMessage(content=null, toolCalls=[])` is **INVALID**

## Suggested Fix

### Minimal Fix (Option 1 - Recommended)

In the Agent's tool execution flow, don't create an `AssistantMessage` after processing tool results. Let the next LLM call naturally create the assistant's response.

```scala
// In Agent.scala (pseudo-code)
def run(state: AgentState): Either[String, AgentState] = {
  // ... existing code ...

  // After tool execution
  val stateWithToolResult = state.addMessage(
    ToolMessage(toolCallId = toolCall.id, content = toolResult)
  )

  // DON'T add empty AssistantMessage here
  // Return stateWithToolResult directly or continue processing

  // ... existing code ...
}
```

### Alternative Fix (Option 3)

Add message validation before LLM API calls:

```scala
private def validateMessages(messages: List[Message]): List[Message] = {
  messages.filter {
    case AssistantMessage(contentOpt, toolCalls) =>
      // Keep if has content OR has tool calls OR is last message
      val hasContent = contentOpt.exists(c => c != null && c.trim.nonEmpty)
      val hasToolCalls = toolCalls.nonEmpty
      val isLastMessage = messages.lastOption.contains(msg)

      hasContent || hasToolCalls || isLastMessage

    case _ => true // Keep all other message types
  }
}

// Before calling LLM API
val validatedMessages = validateMessages(state.conversation.messages)
```

## Reproduction Project

A complete reproduction case is available in the Szork project:
- **Repository**: https://github.com/rorygraves/szork (if public, or provide access)
- **File**: `src/main/scala/org/llm4s/szork/GameEngine.scala:140-150`
- **Debug Tool**: `src/main/scala/org/llm4s/szork/debug/RunUserStep.scala`

### Quick Reproduction

```bash
# Clone and setup
git clone <szork-repo>
cd szork
# Add .env file with ANTHROPIC_API_KEY=...

# Create test adventure
sbt 'runMain org.llm4s.szork.debug.CreateAdventure bug-test --theme pirate'

# Look around (works)
sbt 'runMain org.llm4s.szork.debug.RunUserStep bug-test 1 "look around"'

# Take item (triggers tool, then fails on next LLM call)
sbt 'runMain org.llm4s.szork.debug.RunUserStep bug-test 2 "take rope"'
# ^ This will fail with the empty message error
```

## Impact

**Severity**: High - Blocks all tool calling functionality

**Affected Use Cases**:
- Any agent that uses tools and needs to continue conversation after tool execution
- Multi-turn conversations with tool calling
- Game engines, assistants, and automation systems using tools

**Workaround**:
Currently, there is no effective workaround without modifying the llm4s library itself. Pre-filtering messages before `agent.run()` doesn't work because the empty message is created during execution.

## Additional Context

- This bug was discovered while implementing inventory management tools for a text adventure game
- The bug is reproducible 100% of the time when a tool executes followed by another user message
- The same code pattern works correctly if tools are not used
- Debug data available in `runs/test-success/step-5/` directory

## Version Compatibility

**Working**: (if any older versions work, list them)
**Broken**: v0.1.12 (confirmed)
**Unknown**: v0.1.13 (not tested due to other compilation issues)

## Contact

**Reporter**: Rory Graves
**Project**: Szork Text Adventure
**Date**: 2025-10-11
