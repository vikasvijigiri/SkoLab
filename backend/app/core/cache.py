import asyncio
import time

class SimpleAsyncCache:
    def __init__(self, ttl_seconds: float, max_size: int = 200):
        self.ttl = ttl_seconds
        self.max_size = max_size
        self.cache = {}  # key -> (value, expiry_timestamp)
        self.lock = asyncio.Lock()

    async def get(self, key: str):
        async with self.lock:
            entry = self.cache.get(key)
            if entry is None:
                return None
            value, expiry = entry
            if time.time() > expiry:
                del self.cache[key]
                return None
            return value

    async def set(self, key: str, value):
        async with self.lock:
            print(f"[SimpleAsyncCache] set: '{key}'", flush=True)
            now = time.time()
            # Clean expired keys
            expired_keys = [k for k, (_, exp) in self.cache.items() if now > exp]
            for k in expired_keys:
                del self.cache[k]
            
            # Evict oldest if full
            if len(self.cache) >= self.max_size:
                oldest_key = next(iter(self.cache))
                del self.cache[oldest_key]
                
            self.cache[key] = (value, now + self.ttl)

    async def delete(self, key: str):
        async with self.lock:
            print(f"[SimpleAsyncCache] delete: '{key}'", flush=True)
            if key in self.cache:
                del self.cache[key]

    async def clear(self):
        async with self.lock:
            self.cache.clear()

suggestions_cache = SimpleAsyncCache(ttl_seconds=1800, max_size=300)
profile_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)
analyze_paper_cache = SimpleAsyncCache(ttl_seconds=21600, max_size=200)
daily_feed_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)
match_grants_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)
collaborator_synergy_cache = SimpleAsyncCache(ttl_seconds=7200, max_size=200)
citation_heatmap_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)
journal_advisor_cache = SimpleAsyncCache(ttl_seconds=7200, max_size=100)
network_collaborators_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)
history_summary_cache = SimpleAsyncCache(ttl_seconds=43200, max_size=500)
_semantic_trending_cache = SimpleAsyncCache(ttl_seconds=14400, max_size=200)
_user_memory_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=500)
