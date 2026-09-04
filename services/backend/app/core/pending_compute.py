"""
app/core/pending_compute.py
============================
Single-flight + bounded-wait for a synchronous route backed by slow,
cacheable compute (an LLM/embedding generation pipeline).

Why this exists
----------------
`daily_feed` (and its siblings — see the comment in
`app/api/v1/endpoints/feed.py`) synchronously runs an OpenAlex fetch +
embedding + LLM-ranking pipeline documented at ~40-80s uncached, up to
~2m48s for the widest search. Left as a plain `await`, that blocks the
HTTP request the entire time — which three independent layers then
truncate at three different, uncoordinated points: the browser's default
15s fetch timeout (`apps/web/src/lib/api/client.ts`), the Go gateway's
proxy timeout, and Render's own edge timeout. The result in production
was a request that failed at whichever layer's clock ran out first,
while the backend kept working — and, worse, a second concurrent request
for the same author independently re-ran the entire expensive pipeline
rather than sharing the first one's result (no single-flight protection
on this path; see the note where `run_bounded` is used).

The fix is the standard one for "slow, cacheable compute behind a
request-response cycle": don't hold the HTTP connection open for
minutes. `run_bounded` kicks off `compute()` as a background task the
first time a key is requested; a request that catches it within
`wait_timeout` gets the real result inline (the common warm-or-fast
case, response shape unchanged). A request that doesn't gets `PENDING`
back immediately — the task keeps running regardless, uncancelled, so
the *next* poll (by the same or a different caller) either finds it
already finished or joins the same in-flight task instead of starting a
duplicate. The route handler turns `PENDING` into a `202 Accepted` with
a `Retry-After` header (RFC 9110 §15.3.3 / §10.2.3) — a real, standard
HTTP mechanism for "accepted, not ready yet, ask again in N seconds" —
and the client polls, per `apps/web/src/lib/api/client.ts`.

This is process-local: it does not coordinate across multiple backend
instances (`WEB_CONCURRENCY` > 1, or a future horizontal scale-out). A
second instance would independently start its own compute for the same
key. That's a real limitation, not a hidden one — it costs at most one
redundant pipeline run per additional instance, never correctness, and
matches this repo's current single-instance-by-default deployment
(`WEB_CONCURRENCY=1` in render.yaml). A cross-instance version would key
this off the Postgres/Redis L2 cache instead of an in-process dict —
worth doing if/when this backend actually scales horizontally.
"""

from __future__ import annotations

import asyncio
from typing import Any, Awaitable, Callable, Dict

#: Sentinel returned by `run_bounded` when `compute()` has not finished
#: within `wait_timeout`. Distinct from `None`, which is a legitimate
#: compute result for some callers.
PENDING = object()

_inflight: Dict[str, "asyncio.Task[Any]"] = {}


async def run_bounded(
    key: str,
    compute: Callable[[], Awaitable[Any]],
    wait_timeout: float,
) -> Any:
    """Run (or join) `compute()` for `key`, waiting up to `wait_timeout`.

    Returns the result if `compute()` finishes in time, else `PENDING`.
    `compute()` runs to completion in the background regardless of
    whether any particular caller waited it out — only the task itself
    (not a waiter's timeout) removes it from the in-flight registry, so
    a bounded caller bailing out early never orphans or duplicates the
    work in progress.
    """
    task = _inflight.get(key)
    if task is None:

        async def _run() -> Any:
            try:
                return await compute()
            finally:
                _inflight.pop(key, None)

        task = asyncio.ensure_future(_run())
        _inflight[key] = task

    done, _pending = await asyncio.wait({task}, timeout=wait_timeout)
    if task in done:
        return task.result()
    return PENDING
