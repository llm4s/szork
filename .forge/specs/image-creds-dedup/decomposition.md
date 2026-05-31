# Deduplicate image-credential check

> Feature **image-creds-dedup** — base branch `main`,
> branch prefix `forge`.
> Design PR: #9

## Pieces

<!-- forge:order-start -->
1. <!-- forge:piece p1 -->**p1: Extract shared image-credential check**<!-- /forge:piece -->
   <!-- forge:editable-summary p1 -->
   Add ImageProvider.imageCredsAvailable + huggingFaceKey and route all six duplicated image-credential sites through them.
   <!-- /forge:editable-summary -->
   <!-- forge:status p1 -->`in progress`<!-- /forge:status -->

<!-- forge:order-end -->

---

*Rendered by [Forge](https://github.com/anthropics/forge) from
`manifest.json`. Edit a piece summary or reorder the list between the
`forge:order-start` / `forge:order-end` markers above, then run
`forge reconcile image-creds-dedup` to import the change.*
