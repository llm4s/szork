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
    images/<location>.<style>.png      one picture per location and art style
  _music/<mood>.mp3                    one track per music mood, shared by every game
```

An adventure's directory is the whole adventure: commit it to ship it, delete it to remove it. Nothing in the library
expires (the `szork-cache/` media cache, which still holds pictures made for one game alone, does).

## Playing one

The start screen lists the library under **Ready-Made Adventures**; choosing one starts the game with no designer call.
When designing a new adventure, the **Keep adventure** switch on the last setup step adds it to the library as the
game starts.

Over the API: `GET /api/adventures` lists them, `POST /api/adventures` (`{outline, theme, artStyle}`) keeps one,
`DELETE /api/adventures/:id` removes one, and a WebSocket `newGame` with `adventureId` plays one.

## Pictures: once per place

A place is drawn once and keeps that picture:

- **A library adventure's location** is drawn from the outline's description, in the adventure's art style, and the
  file is kept in the library. Every later visit, and every later game of that adventure, shows the same file.
- **Any other location** (a game with no library adventure, or a place the Dungeon Master made up) is drawn from the
  scene's own `imageDescription` the first time the player arrives, and that picture is the game's for that place.
- **The Dungeon Master decides when a place needs a new picture.** Its answer has an `imageUpdate` field, null on
  almost every turn. When something visibly and lastingly changes how the place looks (a wall brought down, a fire
  lit, a character gone for good) it describes the new look there, on any kind of turn, and a new picture is drawn for
  that game. That game shows the new picture from then on; the library's picture is not replaced.

## Music: once per mood

There are sixteen moods, and a track is a short loop, so music is one track per mood, kept in `_music/` and shared by
every location of every game. A track is made the first time any game needs its mood.

## Baking

A game makes whatever the library lacks the first time it needs it, so baking is optional. It means the first player
waits for nothing, and that a server with **no image or music provider** still shows and plays what was baked
elsewhere.

```bash
sbt "runMain org.llm4s.szork.adventures.BakeAdventure --list"
sbt "runMain org.llm4s.szork.adventures.BakeAdventure the-drowned-archive"     # its pictures, and the mood tracks
sbt "runMain org.llm4s.szork.adventures.BakeAdventure --all --style pixel"     # every adventure, in another style
sbt "runMain org.llm4s.szork.adventures.BakeAdventure --music"                 # only the mood tracks
sbt 'runMain org.llm4s.szork.adventures.BakeAdventure --new --theme "a haunted lighthouse" --style painting'
```

`--new` designs an adventure with the configured model, keeps it and bakes it. Pictures need `SZORK_IMAGE_PROVIDER` and
its key; music needs `REPLICATE_API_KEY`. What already exists is kept: delete a file to have it made again.

## How the browser gets it

Library media is sent as a URL (`/api/library/...`), not as Base64 in the WebSocket message, so the browser fetches
and caches it. Each URL carries the file's modification time, so a re-baked file is fetched again. Pictures made for
one game alone are still sent inline.
