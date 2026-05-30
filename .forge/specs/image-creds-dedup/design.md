# image-creds-dedup

## Problem

The logic that decides whether the configured image provider has the
credentials it needs is copy-pasted across the backend, and the
HuggingFace key-fallback chain is repeated on top of that. The same
`config.imageProvider match { ... }` boolean block appears in five places,
and the HuggingFace key chain appears in a sixth:

- `api/SzorkConfig.scala` — `validate()` (~lines 261-279), a validation
  variant that appends error strings.
- `api/SzorkServer.scala` — `imageKeysPresent` field (~line 114).
- `api/SzorkServer.scala` — `/api/feature-flags` endpoint, local
  `imageCreds` (~line 146).
- `websocket/TypedWebSocketServer.scala` — `handleNewGame()`, local
  `imageCredsAvailable` (~line 278).
- `websocket/TypedWebSocketServer.scala` — `handleLoadGame()`, local
  `imageCredsAvailable` (~line 486).
- `media/ImageGeneration.scala` — client construction (~lines 27-30)
  repeats the HuggingFace key chain
  (`HUGGINGFACE_API_KEY` → `HF_API_KEY` → `HUGGINGFACE_TOKEN`) before
  `.getOrElse(throw ...)`.

The four boolean copies are byte-for-byte identical:

```scala
config.imageProvider match {
  case ImageProvider.HuggingFace | ImageProvider.HuggingFaceSDXL =>
    EnvLoader.get("HUGGINGFACE_API_KEY")
      .orElse(EnvLoader.get("HF_API_KEY"))
      .orElse(EnvLoader.get("HUGGINGFACE_TOKEN"))
      .exists(_.nonEmpty)
  case ImageProvider.OpenAIDalle2 | ImageProvider.OpenAIDalle3 => openAIKeyPresent
  case ImageProvider.LocalStableDiffusion => true
  case ImageProvider.None => false
}
```

Because the rule lives in six spots, the HuggingFace key-fallback chain
and the per-provider rules can (and will) drift between them. A new image
provider, or a new env-var fallback, has to be edited everywhere.

## Approach

Backend DRY refactor only. Add two shared members to the `ImageProvider`
companion object in `api/SzorkConfig.scala` and route every site through
them:

- `def huggingFaceKey(reader: ConfigReader = EnvLoader): Option[String]` —
  resolves the HuggingFace key fallback chain
  (`HUGGINGFACE_API_KEY` → `HF_API_KEY` → `HUGGINGFACE_TOKEN`), returning
  the first present, non-empty value.
- `def imageCredsAvailable(provider: ImageProvider, reader: ConfigReader =
  EnvLoader): Boolean` — the per-provider availability rule:
  - HuggingFace / HuggingFaceSDXL ⇒ `huggingFaceKey(reader).exists(_.nonEmpty)`.
  - OpenAIDalle2 / OpenAIDalle3 ⇒ `OPENAI_API_KEY` present and non-empty.
  - LocalStableDiffusion ⇒ `true`.
  - None ⇒ `false`.

Both take an optional `ConfigReader` (the existing trait in
`org.llm4s.config`) defaulting to `EnvLoader`. Production call sites omit
it and get the `EnvLoader` default — no behaviour change — while the unit
test injects a fake reader to exercise every branch deterministically.

Then:

- Replace the four boolean duplicates (`imageKeysPresent`, the
  `featureFlags` local, both `imageCredsAvailable` locals) with
  `ImageProvider.imageCredsAvailable(config.imageProvider)`.
- Rework the image-provider branch of `SzorkConfig.validate()` to reuse
  `imageCredsAvailable`, appending today's error messages when it returns
  `false` for an enabled non-`None` provider.
- In `ImageGeneration.scala`, replace the inline HuggingFace key chain
  with `ImageProvider.huggingFaceKey`, keeping the existing
  `.getOrElse(throw new IllegalStateException(...))` and its exact
  message. Other branches (OpenAI, LocalStableDiffusion) are unchanged.

The OpenAI-DALL-E branch of `imageCredsAvailable` reads the OpenAI key via
the injected `reader` (`reader.get("OPENAI_API_KEY").exists(_.nonEmpty)`).

## Testing

Add one ScalaTest spec (`*Spec.scala`, matching the existing suite) that
constructs a fake `ConfigReader` and asserts the full mapping:
HuggingFace/HuggingFaceSDXL keyed on the three-way key chain (including the
fallback order and the empty-string-is-absent rule), DALL-E 2/3 keyed on
`OPENAI_API_KEY`, LocalStableDiffusion always `true`, None always `false`,
plus `huggingFaceKey` returning the first non-empty key in priority order.

## Non-goals

- No change to the duplicated `openAIKeyPresent` / `replicateKeyPresent`
  fields shared between `SzorkServer` and `TypedWebSocketServer` (they are
  used elsewhere and stay as-is).
- No consolidation of TTS / STT / music client construction or their
  credential handling.
- No change to the OpenAI or LocalStableDiffusion client-construction
  branches in `ImageGeneration.scala` beyond the HF-key extraction.
- No new behaviour, no change to which providers are considered available,
  and no change to user-visible feature flags or error/exception messages.

## Decisions

- The shared members live on the `ImageProvider` companion object (same
  file as the provider definition) rather than a new module — natural
  home, no new abstraction for a small refactor.
- Helper names follow the existing call-site vocabulary:
  `imageCredsAvailable` (the boolean) and `huggingFaceKey` (the resolved
  key).
- Scope is image credentials only, matching the feature name. Broader
  media-credential consolidation was considered and deliberately deferred.
