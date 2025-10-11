# Szork Debug Tools Documentation

## Overview

The Szork debug tools provide a step-by-step execution environment for debugging game logic, particularly tool calling and state management issues. These tools allow you to:

- Generate adventures and save complete state
- Execute commands one at a time with full visibility
- Track tool calls and agent conversations
- Analyze state changes between steps
- Reproduce and diagnose bugs

## Tools

### 1. CreateAdventure - Adventure Generation

**Purpose**: Generate a new adventure and save the initial game state.

**Location**: `src/main/scala/org/llm4s/szork/debug/CreateAdventure.scala`

**Usage**:
```bash
sbt "runMain org.llm4s.szork.debug.CreateAdventure <session-name> [OPTIONS]"
```

**Arguments**:
- `<session-name>` - Name for this debug session (required)

**Options**:
- `--theme <theme>` - Adventure theme (default: "classic fantasy adventure")
- `--art-style <style>` - Art style: fantasy|pixel|illustration|painting|comic (default: "fantasy")

**Examples**:
```bash
# Create with default theme
sbt "runMain org.llm4s.szork.debug.CreateAdventure my-test-session"

# Create with custom theme (single word themes work best currently)
sbt "runMain org.llm4s.szork.debug.CreateAdventure space-quest --theme scifi --art-style comic"
```

**Output Files** (in `runs/<session-name>/step-1/`):
- `adventure-outline.json` - Generated adventure design with locations, items, NPCs
- `game-state.json` - Complete serialized game state
- `agent-messages.json` - Full LLM conversation including system prompts
- `conversation.txt` - Human-readable conversation log
- `metadata.json` - Session metadata (timestamps, counts, status)

**Known Issues**:
- ✅ **JSON Parsing**: ~~AdventureOutlineParser may fail on complex JSON responses~~ **FIXED** - Now handles incomplete JSON gracefully
- Multi-word themes in quotes may not parse correctly due to argument handling (use single words or underscores)

### 2. RunUserStep - Command Execution

**Purpose**: Execute a user command on an existing adventure session and save the resulting state.

**Location**: `src/main/scala/org/llm4s/szork/debug/RunUserStep.scala`

**Usage**:
```bash
sbt "runMain org.llm4s.szork.debug.RunUserStep <session-name> <previous-step> <command>"
```

**Arguments**:
- `<session-name>` - Name of the debug session (required)
- `<previous-step>` - Previous step number to load from (required)
- `<command>` - User command to execute (required, must be quoted)

**Examples**:
```bash
# Look around
sbt "runMain org.llm4s.szork.debug.RunUserStep my-test-session 1 'look around'"

# Move north
sbt "runMain org.llm4s.szork.debug.RunUserStep my-test-session 2 'go north'"

# Take an item (triggers tool call)
sbt "runMain org.llm4s.szork.debug.RunUserStep my-test-session 3 'take lantern'"

# Check inventory (triggers tool call)
sbt "runMain org.llm4s.szork.debug.RunUserStep my-test-session 4 'inventory'"
```

**Output Files** (in `runs/<session-name>/step-<N+1>/`):
- `user-command.txt` - The command that was executed
- `response.json` - Game response including narration and scene data
- `game-state.json` - Updated game state after command
- `agent-messages.json` - Updated LLM conversation with new messages
- `conversation.txt` - Human-readable conversation log
- `tool-calls.json` - Detailed tool execution log (if tools were called)
- `music-prompt.txt` - Music generation prompt (if applicable, not executed)
- `image-prompt.txt` - Image generation prompt (if applicable, not executed)
- `metadata.json` - Step metadata with timing and success status

### 3. DebugHelpers - Utility Functions

**Purpose**: Shared utilities for file I/O, formatting, and data extraction.

**Location**: `src/main/scala/org/llm4s/szork/debug/DebugHelpers.scala`

**Key Functions**:
- `createStepDir()` - Create step directory structure
- `saveJson()` - Save JSON with pretty printing
- `loadJson()` - Load JSON from file
- `extractToolCalls()` - Parse tool usage from agent messages
- `formatConversation()` - Generate human-readable conversation logs
- `printStepSummary()` - Display step results to console
- `printAdventureSummary()` - Display adventure creation results

**Data Structures**:
- `ToolCallInfo` - Captured tool call with arguments and results
- `StepMetadata` - Metadata about each execution step

## Directory Structure

```
runs/
└── <session-name>/
    ├── step-1/              # Initial adventure creation
    │   ├── adventure-outline.json
    │   ├── game-state.json
    │   ├── agent-messages.json
    │   ├── conversation.txt
    │   └── metadata.json
    ├── step-2/              # First user command
    │   ├── user-command.txt
    │   ├── response.json
    │   ├── game-state.json
    │   ├── agent-messages.json
    │   ├── conversation.txt
    │   ├── tool-calls.json  # If tools were used
    │   └── metadata.json
    └── step-N/              # Subsequent steps
        └── ...
```

## Successfully Reproduced Bugs

### Inventory Tool Bug

**Issue**: Inventory tool calls execute successfully but then fail with LLM API error

**Error Message**: `messages.9: all messages must have non-empty content except for the optional final assistant message`

**Reproduction Steps**:
```bash
# 1. Create adventure
sbt 'runMain org.llm4s.szork.debug.CreateAdventure test-success --theme pirate'

# 2. Look around (establishes baseline)
sbt 'runMain org.llm4s.szork.debug.RunUserStep test-success 1 "look around"'

# 3. Take item (triggers add_inventory_item tool) - THIS FAILS
sbt 'runMain org.llm4s.szork.debug.RunUserStep test-success 2 "take coiled rope"'
```

**Observations**:
- Tool call executes successfully: `Tool add_inventory_item completed successfully in 5ms`
- Tool returns valid result: `{"success":true,"message":"Added 'coiled rope' to your inventory"}`
- Subsequent LLM API call fails with 400 error about empty message content

**Root Cause**: After tool execution completes, the agent sends a message with empty content to the LLM

**Debug Data**: Available in `runs/test-success/step-5/` for detailed analysis:
- `tool-calls.json` - Shows successful tool execution
- `agent-messages.json` - Contains the problematic empty message
- `conversation.txt` - Human-readable view of the conversation

**Fix Location**: `GameEngine.scala` - Tool response handling needs to ensure non-empty content

## Debugging Workflow

### Basic Workflow

1. **Create Adventure**:
   ```bash
   sbt "runMain org.llm4s.szork.debug.CreateAdventure my-debug-session"
   ```

2. **Execute Commands**:
   ```bash
   sbt "runMain org.llm4s.szork.debug.RunUserStep my-debug-session 1 'look around'"
   sbt "runMain org.llm4s.szork.debug.RunUserStep my-debug-session 2 'go north'"
   ```

3. **Analyze Results**:
   ```bash
   # View conversation
   cat runs/my-debug-session/step-2/conversation.txt

   # View tool calls
   cat runs/my-debug-session/step-2/tool-calls.json | jq

   # View game state
   cat runs/my-debug-session/step-2/game-state.json | jq '.currentScene'
   ```

### Debugging Inventory Tool Issue

**Goal**: Identify why inventory tool calls get stuck

1. **Create Test Session**:
   ```bash
   sbt "runMain org.llm4s.szork.debug.CreateAdventure inventory-test"
   ```

2. **Take an Item** (should trigger `add_inventory_item` tool):
   ```bash
   sbt "runMain org.llm4s.szork.debug.RunUserStep inventory-test 1 'take lantern'"
   ```

3. **Check Inventory** (should trigger `list_inventory` tool):
   ```bash
   sbt "runMain org.llm4s.szork.debug.RunUserStep inventory-test 2 'inventory'"
   ```

4. **Analyze Tool Calls**:
   ```bash
   # Check if tool was called
   cat runs/inventory-test/step-2/tool-calls.json

   # Check tool arguments
   jq '.[] | {name, arguments}' runs/inventory-test/step-2/tool-calls.json

   # Check tool results
   jq '.[] | {name, result}' runs/inventory-test/step-2/tool-calls.json

   # Check if agent got stuck (look for repeated messages)
   cat runs/inventory-test/step-2/conversation.txt
   ```

5. **Compare Message Counts**:
   ```bash
   # Count messages at each step
   jq '.messageCount' runs/inventory-test/step-*/metadata.json

   # Large jumps indicate agent looping
   ```

### Analyzing Tool Call Behavior

**Tool Call JSON Structure**:
```json
[
  {
    "id": "tool_call_12345",
    "name": "add_inventory_item",
    "arguments": {
      "item": "brass lantern"
    },
    "result": "{\"success\": true, \"message\": \"Added 'brass lantern' to your inventory\"}",
    "timestamp": 1696789012345
  }
]
```

**What to Look For**:
- **Missing Results**: `result` is `null` → tool call sent but no response
- **Repeated Calls**: Same tool called multiple times → agent stuck in loop
- **Invalid Arguments**: `arguments` don't match tool schema
- **Successful but Ignored**: `result` shows success but agent doesn't acknowledge it

## Common Issues

### Issue: "Session not found"
**Symptom**: `Step directory does not exist: runs/my-session/step-N`

**Cause**: Trying to load from a step that doesn't exist

**Solution**:
```bash
# Check what steps exist
ls -la runs/my-session/

# Start from step that exists
sbt "runMain org.llm4s.szork.debug.RunUserStep my-session <existing-step> '<command>'"
```

### Issue: "No initial scene after initialization"
**Symptom**: Adventure creation completes but no scene is generated

**Cause**: LLM didn't return valid scene JSON or parsing failed

**Solution**:
1. Check `runs/<session>/step-1/agent-messages.json` for the actual LLM response
2. Check `runs/<session>/step-1/metadata.json` for error details
3. May need to retry with different theme or model

### Issue: "Failed to parse adventure outline" ✅ FIXED
**Symptom**: `exhausted input` or JSON parsing error during adventure creation

**Cause**: LLM responses were cut off due to token limits, resulting in incomplete JSON

**Status**: **FIXED** - Parser now detects incomplete JSON and adds missing closing braces/brackets

**Solution**: The parser automatically repairs incomplete JSON by:
1. Counting open vs closed braces and brackets
2. Adding missing closures to complete the JSON structure
3. Defaulting optional fields if missing

**Note**: Adventures may have truncated `adventureArc` or `specialMechanics` fields, but will still be playable

### Issue: "LLM service unavailable"
**Symptom**: `Failed to initialize LLM client` or `Failed to get LLM client`

**Cause**: Missing API keys or invalid credentials

**Solution**:
```bash
# Check .env file exists
ls -la .env

# Verify API keys are set
grep "API_KEY" .env

# Ensure correct key for your model
# For OpenAI: OPENAI_API_KEY
# For Anthropic: ANTHROPIC_API_KEY
```

## Advanced Usage

### Scripting Multiple Steps

Create a bash script for batch testing:

```bash
#!/bin/bash
SESSION="test-$(date +%s)"

# Create adventure
sbt "runMain org.llm4s.szork.debug.CreateAdventure $SESSION"

# Run sequence of commands
COMMANDS=(
  "look around"
  "go north"
  "examine lantern"
  "take lantern"
  "inventory"
  "go south"
  "drop lantern"
  "inventory"
)

STEP=1
for cmd in "${COMMANDS[@]}"; do
  echo "=== Step $((STEP+1)): $cmd ==="
  sbt "runMain org.llm4s.szork.debug.RunUserStep $SESSION $STEP '$cmd'"
  STEP=$((STEP+1))
done

echo "=== Analysis ==="
# Count tool calls across all steps
grep -r "\"name\":" runs/$SESSION/*/tool-calls.json | wc -l
```

### Extracting Metrics

Analyze performance across multiple steps:

```bash
# Response times per step
jq -r '.timestamp as $t | "\($t) \(.responseLength)"' runs/my-session/step-*/metadata.json

# Tool call frequency
jq -r 'select(.toolCallCount > 0) | "\(.stepNumber) \(.toolCallCount)"' runs/my-session/step-*/metadata.json

# Success rate
jq -r '"\(.stepNumber) \(.success)"' runs/my-session/step-*/metadata.json | grep -c true
```

## Integration with Main Server

The debug tools are **standalone** and don't interfere with the running server:

- Different directory: `runs/` vs `szork-saves/`
- No port conflicts
- Independent LLM client instances
- Can run simultaneously with server

However, they use the **same game engine code**, so bugs reproduced in debug mode will match production.

## Future Enhancements

Potential improvements to debug tools:

1. **Visual Diff Tool**: Show state changes between steps
2. **Conversation Analyzer**: Detect patterns indicating stuck behavior
3. **Tool Call Tracer**: Real-time tool call monitoring
4. **Replay Mode**: Re-execute saved sessions with different models
5. **Comparative Testing**: Run same commands with multiple models
6. **Performance Profiling**: Measure time spent in each component
7. **State Validation**: Check for inconsistencies in game state

## Contributing

When adding new debug functionality:

1. Add to `DebugHelpers.scala` if it's a reusable utility
2. Create new main class if it's a standalone tool
3. Update this documentation
4. Add usage examples
5. Include error handling and logging

## Troubleshooting

### Enable Debug Logging

Add to `src/main/resources/logback.xml`:
```xml
<logger name="org.llm4s.szork.debug" level="DEBUG"/>
```

### Inspect Raw Data

All data is saved as JSON, so you can inspect manually:
```bash
# Pretty-print any JSON file
jq '.' runs/my-session/step-2/game-state.json

# Extract specific fields
jq '.currentScene.locationName' runs/my-session/step-2/game-state.json

# Search across all steps
grep -r "inventory" runs/my-session/*/tool-calls.json
```

## Related Documentation

- [Architecture](ARCHITECTURE.md) - System design and component relationships
- [Testing](TESTING.md) - Unit and integration testing
- [Troubleshooting](TROUBLESHOOTING.md) - General troubleshooting guide
- [API](API.md) - HTTP/WebSocket API reference

## Support

For issues with debug tools:
1. Check logs in `runs/<session-name>/step-N/metadata.json`
2. Review `conversation.txt` for LLM interaction issues
3. File an issue with session data attached
4. Include `sbt version` and LLM model used
