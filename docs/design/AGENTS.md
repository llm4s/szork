# Repository Guidelines

## Project Structure & Module Organization
- `src/main/scala/org/llm4s/szork/` — backend source (server, game engine, LLM integration).
- `src/test/scala/` — ScalaTest suites (unit/behavior tests).
- `frontend/` — Vue 3 + Vite + TypeScript UI (Vuetify). Static assets in `frontend/public/`.
- `assets/`, `talk/` — assets and talk materials; not runtime dependencies.
- `szork-cache/`, `szork-saves/` — runtime caches/saves (do not commit).

## Build, Test, and Development Commands
- Backend compile/tests: `sbt compile`, `sbt test`.
- Run server: `sbt szorkStart` (Revolver); hot reload: `sbt "~szorkStart"`; stop: `sbt szorkStop`.
- Frontend dev: `cd frontend && npm install && npm run dev` (serves on `http://localhost:3090`).
- Frontend build/lint/format: `npm run build`, `npm run lint`, `npm run format`.

## Coding Style & Naming Conventions
- Scala 2.13. Use 2‑space indentation, avoid wildcard imports, keep methods small and cohesive.
- Naming: `PascalCase` for classes/objects, `camelCase` for vals/methods, `SCREAMING_SNAKE_CASE` for constants.
- Package layout mirrors directories under `org.llm4s.szork`.
- Frontend uses ESLint + Prettier; prefer Composition API, typed props/emit, single-file components in `PascalCase.vue`.

## Testing Guidelines
- Framework: ScalaTest.
- Location: `src/test/scala/...`.
- Names: end files with `*Spec.scala` or `*Test.scala` (e.g., `IdGeneratorTest.scala`).
- Run: `sbt test`. Write deterministic tests; mock external services where possible.

## Commit & Pull Request Guidelines
- Commits: use clear, focused messages. Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`) are encouraged and already used in history.
- PRs must include: concise description, rationale, scope, testing notes, and any config/API changes. Link issues.
- UI changes: add screenshots/GIFs. Backend changes affecting API/config should update `README.md` and `frontend/README.md` when relevant.
- Keep PRs small and cohesive; add or update tests alongside code.

## Security & Configuration Tips
- Configuration via `.env` (API keys for OpenAI/Anthropic and `SZORK_*` options). Never commit secrets.
- Be mindful of logs: avoid printing prompts, PII, or keys. Use provided caching directories; do not store secrets in `assets/` or VCS.

