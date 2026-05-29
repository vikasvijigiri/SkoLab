"""
app/core/cache.py
==================
All application-level caches.

Every cache is a PgBackedCache (L1 in-memory 30 s  +  L2 PostgreSQL with TTL).
Nothing is lost when the server restarts.

TTL reference
-------------
suggestions_cache           30 min  (author suggestion search results)
profile_cache               1 hour  (full author profile + works)
analyze_paper_cache         6 hours (paper analysis results)
daily_feed_cache            1 hour  (daily paper feed per author)
match_grants_cache          1 hour  (grant recommendations)
collaborator_synergy_cache  2 hours (pairwise synergy scores)
citation_heatmap_cache      1 hour  (citation heatmap data)
journal_advisor_cache       2 hours (journal recommendation)
network_collaborators_cache 1 hour  (co-author network — also in researcher_connections)
history_summary_cache       12 hrs  (agent chat history summaries — also in PG table)
_semantic_trending_cache    4 hours (trending papers / semantic search)
_user_memory_cache          1 hour  (per-user research memory / context)
"""

from app.db.pg_cache import PgBackedCache

# ── Author / Researcher caches ────────────────────────────────────────────────
suggestions_cache           = PgBackedCache(ttl_seconds=1800,  name="suggestions")
profile_cache               = PgBackedCache(ttl_seconds=3600,  name="profile")
analyze_paper_cache         = PgBackedCache(ttl_seconds=21600, name="analyze_paper")
network_collaborators_cache = PgBackedCache(ttl_seconds=3600,  name="network_collaborators")
collaborator_synergy_cache  = PgBackedCache(ttl_seconds=7200,  name="collaborator_synergy")
citation_heatmap_cache      = PgBackedCache(ttl_seconds=3600,  name="citation_heatmap")
journal_advisor_cache       = PgBackedCache(ttl_seconds=7200,  name="journal_advisor")
author_metrics_cache        = PgBackedCache(ttl_seconds=7200,  name="author_metrics")

# ── Feed / Content caches ─────────────────────────────────────────────────────
daily_feed_cache            = PgBackedCache(ttl_seconds=3600,  name="daily_feed")
match_grants_cache          = PgBackedCache(ttl_seconds=3600,  name="match_grants")
_semantic_trending_cache    = PgBackedCache(ttl_seconds=14400, name="semantic_trending")

# ── Agent caches ──────────────────────────────────────────────────────────────
history_summary_cache       = PgBackedCache(ttl_seconds=43200, name="history_summary")
_user_memory_cache          = PgBackedCache(ttl_seconds=3600,  name="user_memory")
