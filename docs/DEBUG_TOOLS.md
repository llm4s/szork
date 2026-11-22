# Debug Tools

Two command-line tools play a game one step at a time, without the web UI, and save every step in the same format
as the server. Use them to reproduce a problem, inspect exactly what the Dungeon Master saw and answered, or replay a
command from any earlier step.

Both need an LLM configured (see the main README). Images and narration are generated when their providers are
configured.

## CreateAdventure

Generates an adventure outline, starts a game, and plays its opening turn.

```bash
sbt "runMain org.llm4s.szork.debug.CreateAdventure"
sbt "runMain org.llm4s.szork.debug.CreateAdventure --theme 'sci-fi space adventure' --art-style comic"
```

Options: `--theme <text>` (default "classic fantasy adventure"), `--art-style fantasy|pixel|illustration|painting|comic`.

The tool prints the new game's id (for example `game-1a2b3c4d`) and the command to run the next step.

## RunUserStep

Loads a saved step, runs one command, and saves the result as the next step.

```bash
sbt "runMain org.llm4s.szork.debug.RunUserStep game-1a2b3c4d 1 'look around'"
sbt "runMain org.llm4s.szork.debug.RunUserStep game-1a2b3c4d 2 'take lantern'"
```

Arguments: the game id, the step to continue from, and the command. Running from an earlier step again replays from
that point, overwriting the directory of the step after it.

## What is saved

```
szork-saves/<game-id>/
├── game.json             # Game metadata: title, theme, current step
├── step-0001/            # The opening turn
│   ├── metadata.json     # Step number, timing, tool call and message counts, success
│   ├── state.json        # Full game state, including the agent conversation (agentMessages)
│   ├── response.txt      # The narration shown to the player
│   ├── response.json     # The scene, when the step described one
│   └── outline.json      # The adventure outline (step 1 only)
└── step-0002/
    ├── command.txt       # The player's command
    ├── tool-calls.json   # Tool calls made during this step, with results
    └── ...               # metadata.json, state.json, response.txt, response.json as above
```

The server writes the same layout as games are played, so these commands work on games from the web UI too (find
their ids with `ls szork-saves`).

## Inspecting a game

```bash
G=szork-saves/game-1a2b3c4d

jq '.currentScene | {locationName, exits}' $G/step-0003/state.json   # Where the player is
jq '.inventory' $G/step-0003/state.json                               # What they carry
jq '.[] | {name, arguments, result}' $G/step-0003/tool-calls.json     # Tools the Dungeon Master called
jq '.agentMessages[-1]' $G/step-0003/state.json                       # Its raw (JSON) answer
jq -r '"\(.stepNumber) \(.executionTimeMs)ms \(.toolCallCount) tools"' $G/step-*/metadata.json
```

## Troubleshooting

- **"is not a game id"**: ids look like `game-1a2b3c4d`; use the id `CreateAdventure` printed.
- **"Step N not found"**: list the game's steps with `ls szork-saves/<game-id>/`.
- **No LLM configured**: set `OPENAI_API_KEY` or `ANTHROPIC_API_KEY` in `.env`.
