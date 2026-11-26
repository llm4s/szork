# The adventure library

A new game normally starts with the designer agent writing an outline, and then draws each scene's picture and composes
its music as the player arrives. The adventure library is the alternative: adventures kept on disk to be played again,
with the media made once.

## What is kept

Everything lives under `adventures/` (`SZORK_ADVENTURES_DIR`), as plain files:

```
adventures/
  the-drowned-archive/
    adventure.json                     the outline, its theme and its art style
    images/<location>.<style>.jpg      one picture per location and art style
  _music/<mood>.mp3                    one track per music mood, shared by every game
```

An adventure's directory is the whole adventure: commit it to ship it, delete it to remove it. Nothing in the library
expires (the `szork-cache/` media cache, which still holds pictures made for one game alone, does).

The repository ships three adventures, each with a picture of every location in its own art style, and a track for
every one of the sixteen moods:

| Adventure | Art style |
| --- | --- |
| `the-last-toll-of-ravenmoor` | `painting` |
| `the-bells-of-thalassa-vey` | `pixel` |
| `the-lighthouse-at-meridian-drift` | `comic` |

So a server with no image or music provider still shows these adventures' pictures and plays music in every game.

## Playing one

The start screen lists the library under **Ready-Made Adventures**; choosing one starts the game with no designer call.
When designing a new adventure, the **Keep adventure** switch on the last setup step adds it to the library as the
game starts.

Over the API: `GET /api/adventures` lists them, `POST /api/adventures` (`{outline, theme, artStyle}`) keeps one,
`DELETE /api/adventures/:id` removes one, and a WebSocket `newGame` with `adventureId` plays one.

## Pictures: once per place

A place is drawn once and keeps that picture:

- **A library adventure's location** is drawn from its picture brief, in the adventure's art style, and the file is
  kept in the library. The brief is written by the art director, a third agent that reads the whole outline once and
  says for each location what a picture of it shows: inside or outside, where the viewer stands, the few things that
  matter, the light. (The description written for the player draws badly: it never says the player is indoors, and
  it lists the exits.) Briefs are written when an adventure is kept or baked, and saved in `adventure.json`. Every later visit, and every later game of that adventure, shows the same file.
- **Any other location** (a game with no library adventure, or a place the Dungeon Master made up) is drawn from the
  scene's own `imageDescription` the first time the player arrives, and that picture is the game's for that place.
- **The Dungeon Master decides when a place needs a new picture.** Its answer has an `imageUpdate` field, null on
  almost every turn. When something visibly and lastingly changes how the place looks (a wall brought down, a fire
  lit, a character gone for good) it describes the new look there, on any kind of turn, and a new picture is drawn for
  that game. That game shows the new picture from then on; the library's picture is not replaced.

Pictures are kept and sent as JPEGs up to 1024 pixels across (`SZORK_IMAGE_PIXELS`); the game shows them small and
at full size when clicked. GPT Image models draw no smaller than 1024 pixels, so what a picture costs in time and
money is set by `SZORK_IMAGE_QUALITY` (default `low`), not by its size.

## Music: once per mood

There are sixteen moods, and a track is a short loop, so music is one track per mood, kept in `_music/` and shared by
every location of every game. A track is made the first time any game needs its mood; games that need a track while it is
being made wait for that one result, rather than each paying for their own. (An engine built with no library, as tests
and tools do, makes a mood's track each time and sends it inline.)

A track is made by Replicate's MusicGen: a prediction is created, polled until it succeeds (`SZORK_MUSIC_POLL_MAX_ATTEMPTS`
polls `SZORK_MUSIC_POLL_INTERVAL_MS` apart, as a deadline), and its audio downloaded. A prediction that is given up on
(the wait ran out, polling kept failing, the thread was interrupted) is cancelled, so it does not keep running and be
billed.

## Baking

A game makes whatever the library lacks the first time it needs it, so baking is optional. It means the first player
waits for nothing, and that a server with **no image or music provider** still shows and plays what was baked
elsewhere.

```bash
sbt "runMain org.llm4s.szork.adventures.BakeAdventure --list"
sbt "runMain org.llm4s.szork.adventures.BakeAdventure the-drowned-archive"     # its pictures, and the mood tracks
sbt "runMain org.llm4s.szork.adventures.BakeAdventure --all --style pixel"     # every adventure, in another style
sbt "runMain org.llm4s.szork.adventures.BakeAdventure --music"                 # only the mood tracks
sbt 'runMain org.llm4s.szork.adventures.BakeAdventure --new --theme "a haunted lighthouse" --theme-name "Haunted Lighthouse" --style painting'
```

Name what to bake: one or more adventure ids, `--all`, `--new` or `--music`. With none of these, and no `--list`, the
command prints its usage and bakes nothing.

| Option | Effect |
| --- | --- |
| `<adventure-id>...` | bake these adventures' pictures, then the mood tracks |
| `--all` | bake every adventure in the library |
| `--new` | design an adventure with the configured model, keep it and bake it; `--theme <theme>` says what it is about, `--theme-name <name>` names the theme (both only go with `--new`) |
| `--music` | bake only the mood tracks |
| `--list` | list the library, and exit |
| `--style <id>` | the art style of the pictures: `pixel`, `illustration`, `painting`, `comic` or `fantasy`; anything else is refused. Without it, each adventure's own style |
| `--art` | have the art director write an adventure's picture briefs again (delete its pictures to have them redrawn from the new briefs) |
| `--no-images` | bake no pictures |
| `--no-music` | bake no mood tracks |

Pictures need `SZORK_IMAGE_PROVIDER` and its key; music needs `REPLICATE_API_KEY`. What already exists is kept: delete a
file to have it made again. The command exits non-zero if anything it was asked for could not be made: a design that
failed, an id that is not in the library, a picture or track that failed.

## How the browser gets it

Library media is sent as a URL (`/api/library/...`), not as Base64 in the WebSocket message, so the browser fetches
and caches it. Each URL carries the file's modification time, so a re-baked file is fetched again. Pictures made for
one game alone are still sent inline.
