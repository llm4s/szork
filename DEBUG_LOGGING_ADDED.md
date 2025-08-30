# Debug Logging Added to SZork Server

## What was added:
Temporary debug logging has been added to print the entire LLM response to the console for user actions in SZork.

## Files modified:
1. `/szork/src/main/scala/org/llm4s/szork/GameEngine.scala`
   - Added debug logging in `processCommand` method (lines 257-268)
   - Added debug logging in `processCommandStreaming` method (lines 389-391)

2. `/szork/src/main/scala/org/llm4s/szork/StreamingAgent.scala`
   - Added debug logging in `handleInProgressStreaming` method (lines 82-86)

## What the logging shows:
- Complete LLM response content
- Tool calls made by the LLM
- Token usage statistics (prompt, completion, total)
- Extracted response text

## How to test:
1. Start the SZork server (already running)
2. Open the frontend at http://localhost:3091/
3. Start a new adventure
4. Enter any command (e.g., "look around", "go north", "examine room")
5. Check the server console for lines starting with `[DEBUG]`

## Sample output format:
```
[info] [DEBUG] Complete LLM Response for user command: look around
[info] [DEBUG] Assistant Message Content: [full response text]
[info] [DEBUG] Assistant Tool Calls: [any tool calls]
[info] [DEBUG] Extracted response text: [processed text]
```

For streaming:
```
[info] [DEBUG] Complete Streaming LLM Response for user command: go north
[info] [DEBUG] Full response text: [complete response]
```

## To remove the debug logging:
Simply remove the lines marked with "TEMPORARY:" comments in the modified files.