# Extract media network config

> Feature **extract-media-network-config** — base branch `main`,
> branch prefix `forge`.

## Pieces

<!-- forge:order-start -->
1. <!-- forge:piece p1 -->**p1: Extract media network timeouts into MediaNetworkConfig**<!-- /forge:piece -->
   <!-- forge:editable-summary p1 -->
   Add env-overridable MediaNetworkConfig and route the five duplicated connect/read timeout sites in TextToSpeech, SpeechToText, and MusicGeneration through it.
   <!-- /forge:editable-summary -->
   <!-- forge:status p1 -->`pending`<!-- /forge:status -->

<!-- forge:order-end -->

---

*Rendered by [Forge](https://github.com/anthropics/forge) from
`manifest.json`. Edit a piece summary or reorder the list between the
`forge:order-start` / `forge:order-end` markers above, then run
`forge reconcile extract-media-network-config` to import the change.*
