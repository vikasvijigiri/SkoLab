# Design system changelog

## Current package contract

`design_tokens.json` is the source of truth for shared visual tokens.
`compile-tokens.js` generates the token block consumed by the Android Compose
theme. Run `make compile-tokens` after changing tokens and include the generated
Android change in the same review.

Keep this changelog for released package-level changes only. Product planning,
design explorations, and session notes do not belong here.
