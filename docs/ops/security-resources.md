# Security resources

When work touches a trust boundary — authn, authz, input handling, secrets,
crypto, dependency risk — consult the highly-rated external references
below before deciding from the request text alone. They are references to
read, not dependencies: nothing here is vendored into this repo or
installed as a skill.

| Resource | Repo | What it gives you |
|---|---|---|
| OWASP Cheat Sheet Series | `OWASP/CheatSheetSeries` | Concise per-topic secure-build guidance — authn, authz, injection, crypto, headers |
| OWASP ASVS | `OWASP/ASVS` | A levelled application-security requirements checklist to verify against |
| OWASP Web Security Testing Guide | `OWASP/wstg` | How to actually probe each control |
| API Security Checklist | `shieldfy/API-Security-Checklist` | A fast pre-release API security countermeasure list |
| Static Analysis (curated) | `analysis-tools-dev/static-analysis` | SAST / linter tooling per language, to wire code + dependency scanning into CI |

- **This list is a floor, not a ceiling.** Also look for a more current
  guide at the time of the work and prefer it when you find one.
- **Ground, don't copy wholesale.** Use these to pick controls and tests;
  the in-repo procedure stays in the `security-and-hardening` skill
  (`.claude/skills/security-and-hardening/SKILL.md`), backed by the
  `security-auditor` persona for a dedicated review pass.
