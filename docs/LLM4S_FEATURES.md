# llm4s features in Szork

Szork is a demonstrator for [llm4s](https://github.com/llm4s/llm4s) 1.0. This is where each feature is used, and the
test that shows it working.

| llm4s feature | Used for | Code | Test |
|---|---|---|---|
| `Agent.builder`, tools, `withMaxSteps` | The Dungeon Master: one agent with inventory and world-state tools | `game/GameEngine.scala`, `game/GameTools.scala` | `GameEngineAgentSpec`, `Llm4sFeaturesSpec` |
| Threads (`run`, `stream`, `forget`, seeding a thread) | A game is one thread; a restored game seeds a new one | `game/DmConversation.scala` | `GameEngineAgentSpec`, `GameStateCorrectnessSpec` |
| Structured output (`withOutput`) and its correction retry | Every Dungeon Master answer, and the adventure outline | `game/DungeonMasterOutput.scala`, `game/AdventureOutlineSchema.scala` | `Llm4sFeaturesSpec`, `AdventureGeneratorSpec` |
| Streaming agent events | Narration shown as it is written | `GameEngine.narrationListener`, `streaming/StreamingJsonParser.scala` | `StreamingParsersSpec`, `Llm4sFeaturesSpec` |
| `ContextWindowMiddleware` | Each call is sent the recent conversation that fits; the thread keeps all of it | `GameEngine.buildAgents` | `GameEngineContextWindowSpec` |
| `GuardrailMiddleware`, `PromptInjectionDetector` | Player commands are checked before the model sees them | `game/PlayerInputGuardrail.scala` | `PlayerInputGuardrailSpec` |
| A custom middleware's prompt section | The current game state joins every model call, stored nowhere | `game/WorldStateMiddleware.scala` | `WorldStateMiddlewareSpec` |
| A custom middleware's `wrapModelCall` | An answer that breaks a game rule goes back to the model once | `game/ExitGuard.scala` | `ExitGuardSpec` |
| Typed subagents and delegation (`withSubagents`, `TaskBudget`) | NPC dialogue and hints | `game/DmSubagents.scala` | `DmSubagentsSpec` |
| `withCompletionOptions`, `ReasoningEffort` | Room for a whole answer, and how hard a reasoning model thinks (`SZORK_REASONING_EFFORT`) | `game/AdventureGenerator.scala`, `GameEngine.completionOptions` | `AdventureGeneratorSpec`, `WireFormatSpec` |
| A custom middleware's `wrapToolCall` | A tool call repeated within a turn is not run again | `game/RepeatedToolCalls.scala` | `Llm4sFeaturesSpec` |
| `AgentResult.runUsage` | Each turn's model calls and tokens | `game/DmConversation.scala` | `WorldStateMiddlewareSpec` |
| Tracing (console, Langfuse) | The designer, the Dungeon Master and scene images in one trace | `api/AgentTracing.scala` | `Llm4sFeaturesSpec` |
| `llm4s-image` | Scene art | `media/SceneImages.scala` | `SceneImagesSpec` |
| `llm4s-speech` | Narration and voice input | `media/TextToSpeech.scala`, `media/SpeechToText.scala` | `Llm4sFeaturesSpec` |
| `llm4s-testkit` (`ScriptedLLMClient`) | Every test that needs a model | `src/test/.../stubs/ScriptedDm.scala` | all of the above |

## Fixed in llm4s 0.7.0

The first live tests, on 0.6.0, found problems in llm4s. 0.7.0 fixed these, and Szork dropped the code that worked
around them:

| Was | Now | Upstream |
|---|---|---|
| `SzorkModelRegistry` marked the Claude models that refuse a `temperature` | Removed: an unset sampling parameter is not sent | [llm4s#2909](https://github.com/llm4s/llm4s/issues/2909) |
| The Claude 5 models could not be used: no way to set the reasoning effort | `CompletionOptions.withReasoning`, set from `SZORK_REASONING_EFFORT` | [llm4s#2909](https://github.com/llm4s/llm4s/issues/2909) |
| `TurnUsage` subtracted the thread's usage before and after a turn | `AgentResult.runUsage` | [llm4s#2922](https://github.com/llm4s/llm4s/issues/2922) |
| Several default prompt-injection patterns matched adventure commands | Tightened upstream; `PlayerInputGuardrail` still chooses its own patterns, and could now use `PromptInjectionDetector.defaults.without(...)` | [llm4s#2923](https://github.com/llm4s/llm4s/issues/2923) |

## Where Szork still does llm4s's job, or chooses not to use it

| Code | Why | Upstream |
|---|---|---|
| `StreamingJsonParser` | A structured answer streams as raw JSON text; reading one field means parsing partial JSON | [llm4s#2921](https://github.com/llm4s/llm4s/issues/2921), open |
| `FakeAnthropic` test server | The testkit has no provider-level fake, so request shape is otherwise untested | [llm4s#459](https://github.com/llm4s/llm4s/issues/459), open |
| `OutlineMap.repair` fixes a generated outline after the fact | 0.7.0 has `withOutputCheck`, which would send the problems back to the model ([llm4s#2920](https://github.com/llm4s/llm4s/issues/2920)). Szork keeps the repair: it is free and certain, where a retry is another minute-long model call that may fail the generation | by choice |
| `ExitGuardMiddleware` re-asks the model itself | `withOutputCheck` ends the turn with an error when the second answer is also wrong; the guard instead replaces it with "the way is shut", so the player always gets a turn | by choice |

## Fixed in llm4s 1.0.0

- An outline cut off at `max_tokens` was retried and reported as `OutputInvalid`, with nothing to say the output limit
  was the cause. 1.0.0 ends the run `Truncated` without asking again and does not decode the partial answer
  ([llm4s#2933](https://github.com/llm4s/llm4s/issues/2933)); `AdventureGenerator` reads that status and tells the
  player the design ran past the output limit.

## Open on 1.0.0

- OpenAI's current models (`gpt-5.6-*`, `gpt-6-*`, `gpt-6.1-*`) call function tools on Chat Completions only with
  `reasoning_effort: "none"`; reasoning with tools needs the Responses API, which llm4s-openai does not use. llm4s
  1.0.0 (as 0.7.0 did) handles this for `gpt-6-luna` (it sets the effort to none and logs the adjustment), but refuses tools on
  `gpt-6.1-terra` ("calls tools only through OpenAI's Responses API", though it calls them on Chat Completions with
  the effort at none), and sends the `gpt-5.6` models the configured effort, which OpenAI rejects - while its own
  error message recommends `gpt-5.6-terra` ([llm4s#2940](https://github.com/llm4s/llm4s/issues/2940), open;
  the Responses API client is [llm4s#2900](https://github.com/llm4s/llm4s/issues/2900)).
