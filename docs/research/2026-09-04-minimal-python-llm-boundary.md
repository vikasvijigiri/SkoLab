# How do world-class systems minimise the Python footprint of LLM functionality — and can it go to zero?

**Asked because:** The owner wants `services/backend` reduced to "purely LLM-related
services only, and minimal even there — only the method level should be Python."
This decides whether the Python/FastAPI service survives at all after the
non-LLM migration, and in what shape. Approval gate before implementation.

**Verdict:** "Method-level Python" is an explicit **anti-pattern** — the
industry boundary is a whole *service* (HTTP/gRPC), never a function. And for
SkoLab specifically, that service can be **zero**: nothing the AI code does
(prompt templating → completions HTTP call with fallback → JSON-out parsing →
a hand-rolled tool loop → cosine/MMR math) requires Python. The world-class
shape is an **AI gateway** — thin, language-agnostic — that owns provider
routing, fallback, retry/circuit-breaking, caching, rate/token limits, cost
tracking and observability; the app just sends prompts. Port the ~8 LLM
endpoints into the Go gateway (a new `internal/llm` package, optionally
fronting LiteLLM / Cloudflare AI Gateway), swap `pdfplumber`+`numpy` for Go
equivalents, then **delete `services/backend`**.

## Findings

### 1. Is Python required to call an LLM API? — HIGH (no)

- **Blaxel, *TypeScript vs Python for AI Agents* (opened):** "Python isn't
  required **if your agents only call LLM APIs** through provider SDKs."
- **LiteLLM landing page (opened):** "One OpenAI-compatible API to 140+
  providers" — "non-Python applications can integrate via standard REST
  endpoints without language-specific SDKs or Python dependencies in their
  codebases."
- **Vercel AI SDK docs (opened):** TS toolkit; "Core handles server-side
  model calls, tool execution, and structured generation… no dependency on
  Vercel's infrastructure… any Node.js server, AWS Lambda, Cloudflare
  Workers, or on-premise." Multi-step agent + tool-use loop + streaming +
  structured output, all in the app's own backend, no separate service.
- **Means here:** `services/backend/app/services/ai/llm_service.py` is
  `httpx.POST` to Groq/OpenRouter `/chat/completions` + a circuit breaker +
  fallback + a rate-limit flag. That is exactly the "only calls LLM APIs"
  case. Go's `net/http` + the gateway's existing `internal/circuitbreaker`
  covers it.

### 2. What genuinely still needs Python? — HIGH

- **Blaxel (opened) lists the real cases:** fine-tuning / custom model
  workflows; embedding & retrieval **pipelines** built on Python-only libs;
  **evaluation frameworks**; reasoning engines using **LangChain / CrewAI /
  LlamaIndex / DSPy**.
- **Means here — SkoLab does none of them (verified in-repo):**
  - `requirements.txt` (post-#25) has **no** `torch`, `sentence-transformers`,
    `langchain`, `llamaindex`, `dspy`, `crewai`, `instructor`, `guardrails`,
    `tiktoken`. It is `fastapi`, `httpx`, `numpy`, `networkx`, `pdfplumber`,
    `sqlalchemy`, `firebase-admin`.
  - `agent_service.py` (454 lines) is a **hand-rolled** tool-use loop:
    `TOOLS_SCHEMA` + `execute_tool_call` + prompt strings + `LLMService`. No
    framework.
  - Embeddings run via the **Hugging Face Inference API** (`#25`,
    `embedding_service.py::_embed_via_api`); the local `sentence-transformers`
    path is "dev/CI only" per its own docstring and is not in
    `requirements.txt`.
  - `numpy` is used only for cosine similarity, L2-normalisation and MMR —
    ~20 lines of arithmetic, trivial in Go (`gonum` or hand-rolled).
  - `pdfplumber` (agent document upload) is the one non-trivial library →
    Go `ledongthuc/pdf` / `unidoc`, or shell out to `pdftotext`.

### 3. Is "method-level Python only" a real pattern? — HIGH (no, it's an anti-pattern)

- **Blaxel (opened), verbatim:** "The anti-pattern to avoid is a single
  codebase that mixes TypeScript and Python through shell commands or
  subprocess calls." … "Each language should live in its own service with a
  defined API at the boundary." … they advocate "REST/HTTP APIs" or "gRPC
  with Protocol Buffer schemas" **"rather than method-level interop."**
- **Means here:** splitting a function across languages (subprocess, FFI,
  `gopy`) is rejected by the source the owner's phrasing points at. If any
  Python survives it is a *service* with an HTTP/gRPC contract — and finding 2
  shows there's no reason for even that.

### 4. What IS the world-class shape? The AI gateway — HIGH

- **Azure Architecture Center, *Access models through a gateway* (opened):**
  a reverse-proxy AI gateway centralises what would otherwise be
  reimplemented in every client — "retry mechanisms, circuit breakers, and
  backoff strategies", load-balancing / failover across model instances,
  **rate limiting**, **token-usage limits**, **semantic caching**, **cost /
  usage tracking**, cross-model **observability**, content safety, safe
  (canary/blue-green) rollout. "Without a gateway, all reliability concerns
  must be addressed exclusively by using client logic."
- It is an application of **Gateway Offloading** (the same pattern the SkoLab
  Go gateway already implements) — and that pattern's hard rule is
  **"business logic should never be offloaded to the gateway."** So: the
  gateway does the cross-cutting LLM plumbing; the *prompt construction and
  response handling* for each feature stay in that feature's handler (which,
  here, is Go).
- **LiteLLM / Portkey / Cloudflare AI Gateway (search synthesis + LiteLLM
  page opened):** off-the-shelf implementations of exactly this — one
  OpenAI-compatible endpoint, provider fallback, retries, caching, spend
  caps, logging. Drop-in as a component; the calling app carries no LLM
  client code.
- **Means here:** the ~8 SkoLab LLM endpoints (`agent/chat`,
  `daily_conjecture`, `assistant_professor_roadmap`, `discovery/predict`,
  `discovery/nexus-chat`, `chat_with_author`, `summarize_work`,
  `analyze_paper`, plus the LLM rationale inside `journal_advisor` /
  `match_grants` / `collaborator_synergy`) become Go handlers that build a
  prompt and call **one** internal LLM client — either a small
  `internal/llm` package in the Go gateway, or the gateway proxying to a
  LiteLLM / Cloudflare AI Gateway instance for the routing/cache/spend layer.

### 5. Net for SkoLab — HIGH

- "Minimal Python for LLM" resolves to **no Python**. After streams
  F / N / I (non-LLM → Go) land, the remaining Python surface is entirely
  finding-1-and-2 work. Port it and `services/backend` is deletable — one
  fewer runtime, deploy unit, Dockerfile, `requirements.txt`, CI job, and the
  ~175 lines of Windows/`builtins.print` shims in `app/main.py` go with it.

## Disagreements

- **`decisions/0002` ("AI/enrichment stays Python") vs this verdict.** 0002
  was correct *in 2026-06* — embeddings were self-hosted `torch`
  (`decisions/0003`), which genuinely anchors Python. `#25` (2026-09-03)
  reversed 0003 to a hosted embeddings API, which removes the anchor. 0002's
  split is now stale for the AI half; its fast-path/edge reasoning still
  stands (that's the Go gateway).
- **General polyglot guidance says "keep a Python ML service with a clean
  boundary."** That advice is written for teams doing finding-2 work (RAG
  pipelines, evals, fine-tuning). Applied to a codebase that only calls
  completions APIs, it keeps a service that does nothing Python-specific —
  cost without benefit. The sources that *condition* on workload (Blaxel)
  agree with this verdict; the ones that state it as a blanket rule do not.

## Not adopted

- **Method-level Python interop** (subprocess / `cgo` / `gopy` / FFI) —
  explicit anti-pattern (finding 3); brittle serialisation, couples deploys,
  no upside.
- **Keep a thin Python "LLM microservice"** — works today, lowest immediate
  risk, but it is not "minimal": a Go `internal/llm` package is strictly less
  code and removes a whole runtime. Reasonable as an *interim* step if the
  endpoint port is staged.
- **Adopt LangChain / LlamaIndex / DSPy to justify keeping Python** —
  backwards: SkoLab's agent is a 450-line hand-rolled loop; a framework would
  add dependency weight to defend a language choice, not to solve a problem.
- **Run a model-serving stack (vLLM / Triton / KServe)** — only relevant if
  SkoLab self-hosts a model. It does not (Groq/OpenRouter for chat, HF API
  for embeddings).
- **Cloudflare AI Gateway as the only gateway** — attractive (repo rules
  already favour Cloudflare, zero-ops, free tier) but it is a
  routing/cache/observability layer, not a place for prompt logic; it sits
  *behind* the Go gateway's handlers, it doesn't replace them. Worth piloting
  for the spend-cap + semantic-cache features; not a blocker.

## Sources

- Azure Architecture Center — *Access Foundry Models and Other Language
  Models Through a Gateway*
  <https://learn.microsoft.com/en-us/azure/architecture/ai-ml/guide/azure-openai-gateway-guide>
  (opened, full)
- Blaxel — *TypeScript vs Python for AI Agents: A Decision Framework*
  <https://blaxel.ai/blog/typescript-vs-python-ai-agents> (opened)
- LiteLLM — landing page <https://www.litellm.ai/> (opened)
- Vercel AI SDK — docs introduction <https://ai-sdk.dev/docs/introduction>
  (opened via search result summary + prior knowledge of the SDK; the
  streaming/agent/tool-loop claims are from the fetched summary)
- Search synthesis on AI-gateway comparisons (LiteLLM / Portkey / Kong /
  Cloudflare) — openziti.io, tooljunction.io, contabo.com — **summaries
  only**; the substantive claims above trace to the four opened primaries.
- In-repo (opened this session): `services/backend/app/services/ai/*.py`,
  `requirements.txt`, `requirements-dev.txt`, `decisions/0002`, `0003`,
  `#25` commit message.
