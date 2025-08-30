# SZork Narration Text Optimization

## Summary
Optimized the LLM response format to avoid generating narrative text twice, reducing token usage and speeding up response generation while maintaining streaming capability.

## Problem
Previously, the LLM was instructed to output:
1. The narration text on its own line
2. Then `<<<JSON>>>` marker
3. Then the full JSON response (which includes the same narration text in the `narrationText` field)

This resulted in the narration text being generated twice by the LLM, wasting tokens and time.

## Solution
Modified the response format to:
1. Output the narration text on its own line (for streaming)
2. Then `<<<JSON>>>` marker
3. Then the JSON response WITHOUT the `narrationText` field

The narration text is programmatically added back to the JSON when parsing, avoiding duplication while maintaining streaming.

## Changes Made

### 1. GameEngine.scala
- Updated the prompt to instruct LLM NOT to include `narrationText` field in JSON
- Modified `parseResponseData` to capture narration text before `<<<JSON>>>` and add it to the parsed JSON
- Removed `narrationText` field from JSON schema examples in the prompt

### 2. StreamingTextParser.scala
- Maintains original streaming behavior for narration text before `<<<JSON>>>`
- `getJson()` method now adds the captured narration text to the JSON as `narrationText` field
- Uses proper JSON parsing with ujson instead of regex

## Benefits
- **Reduced Token Usage**: Eliminates duplicate generation of narration text
- **Faster Response**: Less text for LLM to generate means quicker responses
- **Maintained Streaming**: Narration text still streams immediately as it's generated
- **Clean Separation**: Clear distinction between streaming text and structured data

## Technical Details
The streaming parser now:
1. Streams narration text as it arrives (before `<<<JSON>>>` marker)
2. Captures the complete narration text when marker is found
3. Parses the JSON after the marker
4. Adds the captured narration text to the JSON as `narrationText` field
5. Returns complete JSON with all fields for downstream processing