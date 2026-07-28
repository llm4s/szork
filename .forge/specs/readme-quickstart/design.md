# README Quick Start corrections

## Problem

The root `README.md` has a "🚀 Quick Start" section that looks complete but
misleads a first-time contributor:

- It lists **Scala 2.13** in the Quick Start prerequisites and again in
  **Architecture → Technology Stack**, but the build is **Scala 3.7.1**
  (`build.sbt: ThisBuild / scalaVersion := "3.7.1"`).
- The rest of the Quick Start (prerequisites, `.env` setup, backend/frontend
  run steps, ports) is spread across the file and has not been verified to be
  internally consistent and correct against the repo.

Net effect: a new user following the steps top-to-bottom can hit an incorrect
fact and stall before getting a server running.

## Approach

A documentation-only revision of `README.md`. No source, build, or
`.env.example` changes.

1. **Correct the Scala version everywhere it appears.** Replace "Scala 2.13"
   with "Scala 3" in both the Quick Start prerequisites and the
   Architecture → Technology Stack section. Plain "Scala 3" is preferred over
   pinning "3.7.1" in prose so the README does not drift on the next bump; the
   exact version stays in `build.sbt`.

2. **Verify and correct the remaining Quick Start facts** against the repo, and
   leave correct ones unchanged. Verified during design:
   - Toolchain in use: Java 21 (`java -version`), sbt 1.11.3
     (`project/build.properties`). The README's "Java 17+" / "sbt 1.8+" are
     stated as minimums — keep unless shown to be wrong; do not regress them.
   - Node.js prerequisite for the frontend.
   - `.env` setup: `.env.example` exists in the repo root; `cp .env.example .env`
     is correct.
   - Backend start: `sbt szorkStart` and `sbt "~szorkStart"`.
   - Frontend: `cd frontend`, `npm install`, `npm run dev`; dev server is Vite
     on **port 3090** (`frontend/vite.config.mts`), URL `http://localhost:3090`.
   - Ports: HTTP **8090** (`SzorkConfig.scala`) and WebSocket **9002**
     (`SzorkServer.scala`) — already correct in the README.

3. **Keep the section tight and linear** — a single clone → configure → run
   path, ordered so the steps work in sequence.

## Decisions

- The human dismissed the clarifying questions, so defaults were taken:
  - Interpretation: **improve the existing README Quick Start**, not create a
    new file.
  - **Factual accuracy is in scope** — the Scala version mismatch is fixed as
    part of this work.
- Version is written as "Scala 3" (not "3.7.1") in prose to avoid future drift;
  `build.sbt` remains the single source of truth for the exact version.

## Scope: single piece

This is one small, internally-consistent edit to a single file, comfortably
reviewable in one sitting. The feature is therefore a single piece (`p1`).
Splitting it into setup/edit/cleanup stages would add overhead without value.

## Non-goals

- No new standalone `QUICKSTART.md` — the README section is the home for this.
- No rewrite of unrelated README sections (Features, How It Works, About,
  Contributors, License) beyond the version corrections noted above.
- No changes to source code, build files, or `.env.example`.
- No expansion into troubleshooting, Docker, or deployment docs.
