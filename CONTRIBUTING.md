# Contributing to Szork

Contributions are welcome: open an issue for a bug or an idea, or a pull request. Szork is a demonstrator for
[llm4s](https://github.com/llm4s/llm4s), so a change that uses llm4s the way it is meant to be used is preferred to one
that works around it; [docs/LLM4S_FEATURES.md](docs/LLM4S_FEATURES.md) lists where Szork still does.

## Building and testing

The README's [quick start](README.md#quick-start) gets the game running. Before a pull request, run what CI runs.

Backend, from the repository root:

```bash
sbt scalafmtCheckAll Test/compile test    # sbt scalafmtAll fixes formatting
```

`sbt coverage test coverageReport` measures test coverage (the report is `target/scala-*/scoverage-report/index.html`;
CI keeps it as the `coverage-report` artifact). Nothing fails on a low figure.

`build.sbt` compiles with `-Werror` and `-Wunused`, so a warning fails the build, locally as in CI.

Frontend, from `frontend/`:

```bash
npm ci
npm run lint:check     # npm run lint fixes most of it
npm run typecheck
npm test
npm run build
```

`sbt test` needs no network and no keys: tests script the model with the llm4s testkit. A change to how the game plays
is worth a live playtest too (`sbt "runMain org.llm4s.szork.playtest.Playtest"`, which needs a key);
[docs/TESTING.md](docs/TESTING.md) describes the test layers and where to add a test.

## Sign-off

Every commit must carry a Developer Certificate of Origin sign-off: a `Signed-off-by:` line with your name and email,
which `git commit -s` adds. By signing off you certify that you wrote the change or otherwise have the right to submit
it under the project's licence (see [developercertificate.org](https://developercertificate.org/)).

`hooks/install.sh` installs two git hooks: `commit-msg` refuses a commit without the sign-off, and `pre-commit` checks
formatting and compiles (and lints the frontend when frontend files are staged).

```bash
hooks/install.sh
```

## Commit messages

Look at `git log` for the style. A subject starts with a type (`fix:`, `feat:`, `refactor:`, `test:`, `docs:`,
`chore:`) and says in plain English what is now true, not what was done to the code:

```
fix: a turn that does not finish leaves the game as it was
refactor: the server's routes, app and main each have their own file
```

The body says what was wrong or missing and why the change is the way it is, in short paragraphs. Keep each commit to
one logical change.

## Pull requests

Say what the change does and why, how it was tested, and any change to configuration or the protocol. Add a
screenshot for a change to the UI. Keep pull requests small; add or update tests with the code.

## Making common changes

[CLAUDE.md](CLAUDE.md)'s Development Patterns section has the steps for adding a game tool, a WebSocket message (the
Scala protocol, the TypeScript mirror and the spec) and a field of the saved game. A new setting is read in
`api/SzorkConfig.scala` only, and added to the README's configuration table and to `.env.example`.

## Code and security

- Scala 3, formatted with scalafmt; errors as values (`Either`), not exceptions; state shared across threads kept safe.
- Frontend: Vue single-file components in TypeScript, `<script setup>` for new ones, ESLint and Prettier.
- Never commit `.env` or any key, and never log a key. `szork-saves/` and `szork-cache/` are runtime
  data and are not committed; `adventures/` is, as the shipped adventures.

## Licence

Szork is licensed under the Apache License 2.0 ([LICENSE](LICENSE)); contributions are accepted under the same licence.
