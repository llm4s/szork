# llm4s in Szork: a guided tour

Szork is a demonstrator for [llm4s](https://github.com/llm4s/llm4s) 1.0. This page is a route through its code for
someone who wants to learn llm4s from it: each stop names the file, the symbol to look for, and the spec that shows the
feature working. Paths are under `src/main/scala/org/llm4s/szork/` and specs under `src/test/scala/org/llm4s/szork/`.

The llm4s modules Szork depends on are in [build.sbt](../build.sbt): `llm4s-agent`, `llm4s-openai`,
`llm4s-anthropic`, `llm4s-image`, `llm4s-speech`, `llm4s-schema-derivation`, `llm4s-observability`, and
`llm4s-testkit` for tests.

## Suggested reading order

### 1. A client for a model

`src/main/resources/application.conf` lists the models Szork can play on as llm4s named provider sections
(`llm4s.providers.openai`, `.anthropic`, `.llama`), each with its default model. `api/SzorkConfig.scala`:
`LLMConfig.toProviderConfig` has llm4s read the chosen section with `Llm4sConfig.from(config)` and
`defaultProvider()`, which gives a `ProviderConfig` (`OpenAIConfig` or `AnthropicConfig`, the key held so it is never
logged). Szork's key, model and base URL are laid over the file as a Typesafe `Config`, because they may come from
`.env`, which `${?VAR}` substitution does not read. `SzorkConfig.getLLMClient` turns the provider config into an
`LLMClient` with `LLMConnect.getClient`, given the `ModelRegistryService` (`Llm4sConfig.modelRegistryService()`) that
knows each model's context window and capabilities. Every agent in the game is built over this one client. `GameEngine.completionOptions` shows
`CompletionOptions.withMaxTokens` and `withReasoning(ReasoningEffort)`, which llm4s sends only to a model that reasons.

Specs: `SzorkConfigSpec`; `WireFormatSpec` shows what reaches the provider.

### 2. An agent with no tools that answers in a schema: the designer

`game/StructuredAgent.scala` (`StructuredAgent.ask`) is the smallest complete agent in Szork: `Agent.builder`,
`withSystemPrompt`, `withMaxSteps(1)`, `withCompletionOptions`, `withOutput(schema)`, optional `withTracing`, then
`run`, `AgentResult.decodeOutput[A]` to read the answer as a Scala value, and `forget` to drop the thread. llm4s states
the schema in the prompt, checks the answer, and asks once for a correction within the same step. An answer cut off at
the output limit ends the run `AgentStatus.Truncated`, which `ask` reports as no answer rather than decoding half an
outline.

Two agents use it: `game/AdventureGenerator.scala` (`generateAdventureOutline`) designs an adventure, with the schema
written by hand with llm4s's `Schema` builder in `game/AdventureOutlineSchema.scala`; and `game/ArtDirector.scala`
writes a picture brief for each location of a kept adventure.

Specs: `AdventureGeneratorSpec`, `ArtDirectorSpec`.

### 3. The Dungeon Master: an agent with tools and threads

`game/GameEngine.scala`, `buildAgents`: one `Agent.builder` with `withTools(ToolRegistry)`, `withSystemPrompt`,
`withMaxSteps(MaxModelCallsPerTurn)`, `withOutput(DungeonMasterOutput.schema)`, `withOutputCheck`, `withRuntime`,
middleware, subagents and tracing. Two agents are built from it, one blocking and one `withStreaming()`, sharing one
`GraphRuntime.inMemory()`, so either can play the next turn of the same thread.

`game/DmConversation.scala` (`runTurn`) holds the game's thread: `run` or `stream(...)(listener).await()` with a
`ThreadId`, a new thread seeded with the saved conversation after a restore, and `forget` to drop one. It reads each
`AgentStatus`: `Completed` keeps the turn; `Blocked` (a guardrail refused the command) keeps nothing; `Truncated`,
`StepLimitReached` and `Suspended` discard the turn, and `GameEngine.playTurn` puts the world and inventory back as
they were. `AgentResult.runUsage` gives each turn's model calls and tokens.

Specs: `GameEngineAgentSpec`, `GameStateCorrectnessSpec`, `Llm4sFeaturesSpec` (the step limit).

### 4. Structured output derived from a type

`game/DungeonMasterOutput.scala`: the answer is a case class, `Answer`, and its schema is derived from it with
`llm4s-schema-derivation`'s `SchemaOf` (`derives SchemaOf`, `@description` on fields, Scala enums for moods and
directions). `game/GameResponseValidator.scala` (`problems`) holds what a schema cannot say (ids that are ids, the
fields each kind of answer must give) and runs as `withOutputCheck`, so a breach gets the same one correction as a
schema error. `GameEngine.playTurn` reads a completed turn once with `decodeOutput[DungeonMasterOutput.Answer]`.

Specs: `DungeonMasterOutputSpec`, `GameEngineValidationSpec`, `Llm4sFeaturesSpec` (correction of an invalid answer).

### 5. Streaming and partial JSON

`GameEngine.narrationListener` handles the agent's stream events (`AgentEvents.ModelCallStarted`, `TextDelta`,
`ToolCallStarted`, `SubagentEvents.TaskStarted`/`TaskEnded`). The answer streams as JSON text;
`streaming/StreamingJsonParser.scala` reads the narration out of it as it grows with llm4s's `PartialJson`, from the first brace
that opens an object.

Specs: `StreamingParsersSpec`, `Llm4sFeaturesSpec` (a streamed turn ends with the corrected narration).

### 6. Tools

`game/GameTools.scala`: `ToolBuilder[R](...)` with a schema per tool and a handler reading a `SafeParameterExtractor`
(`getOptionalString` and the like), built as values (`Result`) and gathered into a `ToolRegistry`. The tools close
over per-game state (`PlayerInventory`, `WorldState`), never a global.

Specs: `PlayerInventorySpec`, `WorldStateSpec`.

### 7. Middleware

The Dungeon Master's middleware, in the order `buildAgents` adds them (llm4s nests the first added outermost):

| Middleware | Hook | What it does | File | Spec |
|---|---|---|---|---|
| `GuardrailMiddleware` (llm4s) | `beforeAgent` | Checks each command before the agent starts | `game/PlayerInputGuardrail.scala` | `PlayerInputGuardrailSpec` |
| `WorldStateMiddleware` | `promptSections` | Adds the current game state to every model call's system prompt, stored nowhere | `game/WorldStateMiddleware.scala` | `WorldStateMiddlewareSpec` |
| `ToolRoundsMiddleware` | `wrapModelCall`, `wrapToolCall` | After a turn's first three model calls, offers no tools (`request.withTools(ToolSet.empty)`) | `game/RepeatedToolCalls.scala` | `RepeatedToolCallsSpec` |
| `ExitGuardMiddleware` | `wrapModelCall` | Sends back, once, an answer that moves the player through an exit that is not open | `game/ExitGuard.scala` | `ExitGuardSpec` |
| `RepeatedToolCallMiddleware` | `beforeAgent`, `wrapToolCall` | Does not run a tool call repeated with nothing changed since | `game/RepeatedToolCalls.scala` | `RepeatedToolCallsSpec` |
| `ContextWindowMiddleware` (llm4s) | `wrapModelCall` | Sends each call the recent conversation that fits; the thread keeps all of it | `GameEngine.buildAgents`, `GameEngine.contextWindowFor` | `GameEngineContextWindowSpec` |

`ToolRoundsMiddleware` counts each model call that reaches it, including llm4s's request to correct an invalid answer;
it wraps the exit guard, so the guard's own correction counts as part of the round it corrects.

### 8. Typed subagents

`game/DmSubagents.scala`: two `TypedAgent`s, NPC dialogue and hints, each with typed input and output, a brief rendered
when the task runs (`withInputText`) and a `TaskBudget`. The Dungeon Master is offered them with
`withSubagents(...)` and bounded by `withDelegation(DelegationConfig(...))`; llm4s gives it a `task_<id>` tool for
each, and runs a task on a child thread of the turn's thread, traced with the parent.

Spec: `DmSubagentsSpec` (scripts each subagent by matching its system prompt).

### 9. Input guardrail

`game/PlayerInputGuardrail.scala`: `PromptInjectionDetector.defaults.without(DroppedLlm4sPatterns*)` keeps llm4s's
default patterns except those that match ordinary adventure commands ("debug mode", "system message", "guardrails"),
and adds Szork's own `InjectionPattern`s for the out-of-game forms. A refused command ends the run `Blocked`.

Spec: `PlayerInputGuardrailSpec`.

### 10. Images and speech

`media/SceneImages.scala`: `SceneImages.providerConfig` picks an llm4s image config (`OpenAIConfig`,
`HuggingFaceConfig`, `StableDiffusionConfig`), and `llm4sClient` builds the client with `ImageGeneration.client`, given
the Dungeon Master's `Tracing` so each picture is traced with the game.

`media/TextToSpeech.scala` and `media/SpeechToText.scala`: a `TTSConfig` or `STTConfig` handed to
`SpeechProviderSelector.getTTSClient` / `getSTTClient`, which returns the llm4s client for the provider.

Specs: `SceneImagesSpec` (configuration, caching and errors against a fake llm4s image client, and a failed call on
the real client still traced). `SpeechSpec` and `Llm4sFeaturesSpec` show the speech clients are built through the selector and need a key;
no test synthesises or transcribes speech.

### 11. Tracing

`api/AgentTracing.scala`: `Llm4sConfig.tracing(ConfigSource)` reads the `TRACING_MODE` and `LANGFUSE_*` settings from
the `.env` values, handed in as a Typesafe config source rather than system properties, and `Tracing.fromSettings`
builds the tracer (`console`, or Langfuse via `llm4s-observability`). It is passed to every agent's `withTracing` and to
the image client.

Specs: `EnvLoaderSpec` and `Llm4sFeaturesSpec` (tracing off unless `TRACING_MODE` names a backend);
`Llm4sFeaturesSpec` (each Dungeon Master turn, streamed or not, traced as a completed run);
`AdventureGeneratorSpec` (the designer runs as a traced agent); `SceneImagesSpec` (image calls traced).

### 12. Testing with the testkit

`llm4s-testkit`'s `ScriptedLLMClient` answers each model call from rules over the conversation and records every
request (`RecordedCall`); `Reply` builds its answers (`Reply.text`, `Reply.toolCall`, `Reply.failure`). `stubs/ScriptedDm.scala` plays a
two-room cavern with it and takes each test's own rules first; `stubs/DmAnswers.scala` writes answers in the Dungeon
Master's format. Below the client, `stubs/FakeAnthropic.scala` is a local Messages API that llm4s's real Anthropic
client talks to, so `WireFormatSpec` can assert on the request bodies. [TESTING.md](TESTING.md) describes the layers.

## Where Szork does a job llm4s could, or chooses not to use it

| Code | Why | Upstream |
|---|---|---|
| `OutlineMap.repair` fixes a generated outline after the fact | `withOutputCheck` could send the problems back to the model ([llm4s#2920](https://github.com/llm4s/llm4s/issues/2920)). Szork keeps the repair: it is free and certain, where a retry is another minute-long model call that may fail the generation | by choice |
| `ExitGuardMiddleware` re-asks the model itself | `withOutputCheck` ends the turn with an error when the second answer is also wrong; the guard instead replaces it with "the way is shut", so the player always gets a turn | by choice |
| `DungeonMasterOutput.answerIn` reads a raw reply with `PartialJson` from each object start | llm4s's own JSON extraction (`LLMClient.extractJson`) is not public, and the exit guard must read the reply before llm4s has checked it | |
| `SzorkConfig.loadLLMConfig` chooses the provider | `Llm4sConfig.defaultProvider()` reads `llm4s.providers` from Typesafe config, which sees neither Szork's `.env` nor `SZORK_LLM_*`, and has no "whichever key is set" fallback | by choice |
| `ThemeValidator` asks with `LLMClient.complete` and reads the JSON leniently | Not yet moved to structured output; the game goes ahead with the player's theme when the answer cannot be read | |
| `media/MusicGeneration.scala` calls Replicate over plain HTTP | llm4s has no music generation API | |
| `stubs/FakeAnthropic` test server | The testkit has no provider-level fake, so request shape is otherwise untested | [llm4s#459](https://github.com/llm4s/llm4s/issues/459), open |

## Limits found in llm4s 1.0.0

- An output schema's root must be an object, so the Dungeon Master's answer cannot be a union of a full scene and a
  simple response. `Answer` is one object with nullable fields, and `GameResponseValidator.problems` checks the
  fields each kind must give.
- `StringSchema` has no `pattern`, so "a location id is lower_snake_case" cannot be in the schema; it is an output
  check.
- `SchemaOf` reads a `@description` only when it is a single string literal. A description built by concatenation or
  interpolation is silently replaced by the field's name, so the narration limits are written out in the literal and
  `DungeonMasterOutputSpec` checks them.
- `streamStructuredOf` (in `llm4s-schema-derivation`) streams a typed value from one model call on an `LLMClient`, not
  from an agent's stream events, so the Dungeon Master's narration is read from the text deltas with `PartialJson`.
- OpenAI's current models (`gpt-5.6-*`, `gpt-6-*`, `gpt-6.1-*`) call function tools on Chat Completions only with
  `reasoning_effort: "none"`; reasoning with tools needs the Responses API, which llm4s-openai does not use. llm4s
  handles this for `gpt-6-luna` (it sets the effort to none and logs the adjustment), but refuses tools on
  `gpt-6.1-terra`, and sends the `gpt-5.6` models the configured effort, which OpenAI rejects
  ([llm4s#2940](https://github.com/llm4s/llm4s/issues/2940), open; the Responses API client is
  [llm4s#2900](https://github.com/llm4s/llm4s/issues/2900)).

## Appendix: fixed upstream

The first live tests, on llm4s 0.6.0, found problems that later releases fixed; Szork dropped its workarounds.

| Was | Now | Release | Upstream |
|---|---|---|---|
| Szork marked the Claude models that refuse a `temperature` | An unset sampling parameter is not sent | 0.7.0 | [llm4s#2909](https://github.com/llm4s/llm4s/issues/2909) |
| The Claude 5 models could not be used: no way to set the reasoning effort | `CompletionOptions.withReasoning`, set from `SZORK_REASONING_EFFORT` | 0.7.0 | [llm4s#2909](https://github.com/llm4s/llm4s/issues/2909) |
| Szork subtracted the thread's usage before and after a turn | `AgentResult.runUsage` | 0.7.0 | [llm4s#2922](https://github.com/llm4s/llm4s/issues/2922) |
| Several default prompt-injection patterns matched adventure commands | Tightened upstream, and `PromptInjectionDetector.defaults.without(...)`, which Szork now uses | 0.7.0 | [llm4s#2923](https://github.com/llm4s/llm4s/issues/2923) |
| Narration was read from the streamed JSON by a hand-written parser ([llm4s#2921](https://github.com/llm4s/llm4s/issues/2921)) | `PartialJson` in `llm4s-schema-derivation` | by 1.0.0 | |
| An outline cut off at `max_tokens` was retried and reported as `OutputInvalid` | The run ends `Truncated`, and the partial answer is not decoded | 1.0.0 | [llm4s#2933](https://github.com/llm4s/llm4s/issues/2933) |
