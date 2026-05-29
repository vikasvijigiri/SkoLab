"""
Patch script v3 for pipeline_services.py
Uses index-based insertion instead of string replacement to avoid escaping issues.
"""

with open('app/services/pipeline_services.py', 'r', encoding='utf-8') as f:
    content = f.read()

# Normalize line endings
content = content.replace('\r\n', '\n')

# ── 1. Add _firestore_get_safe after _get_firestore_db ────────────────────
# Find insertion point: after 'return None' at end of _get_firestore_db
marker = '_get_firestore_db(self):'
idx = content.find(marker)
if idx == -1:
    print("ERROR: _get_firestore_db not found!")
    exit(1)

# Find the next occurrence of "    async def " after _get_firestore_db
next_method = content.find('\n    async def ', idx)
next_def = content.find('\n    def ', idx + 50)
# Insert before the next method
insert_at = min(m for m in [next_method, next_def] if m > idx)

HELPER = '''
    async def _firestore_get_safe(self, collection: str, doc_id: str, timeout: float = 5.0):
        """
        Wraps a synchronous Firestore document.get() in a thread executor with a short timeout.
        Prevents the blocking Firestore SDK from stalling the asyncio event loop when
        credentials are expired or the network is unavailable.
        Returns the document dict if found and within time, else None.
        """
        db = self._get_firestore_db()
        if not db:
            return None
        loop = asyncio.get_event_loop()
        try:
            def _blocking_get():
                doc = db.collection(collection).document(doc_id).get()
                return doc.to_dict() if doc.exists else None
            result = await asyncio.wait_for(
                loop.run_in_executor(None, _blocking_get),
                timeout=timeout
            )
            return result
        except asyncio.TimeoutError:
            print(f"[PipelineServices] Firestore get timed out ({timeout}s) for {collection}/{doc_id}", flush=True)
        except Exception as e:
            print(f"[PipelineServices] Firestore get error for {collection}/{doc_id}: {e}", flush=True)
        return None
'''

# Check if already added
if '_firestore_get_safe' in content:
    print("INFO: _firestore_get_safe already exists, skipping insertion")
else:
    content = content[:insert_at] + HELPER + content[insert_at:]
    print(f"SUCCESS: Added _firestore_get_safe helper at position {insert_at}")

# ── 2. Patch daily_feeds blocking Firestore call ──────────────────────────
# Find the blocking pattern by searching for unique surrounding strings
DF_MARKER = 'db.collection("daily_feeds").document(doc_id).get()'
df_idx = content.find(DF_MARKER)
if df_idx != -1:
    # Find start of the db = self._get_firestore_db() block before this
    block_start = content.rfind('        db = self._get_firestore_db()', 0, df_idx)
    # Find end of the except block
    except_end = content.find('daily_feeds lookup failed', df_idx)
    block_end = content.find('\n', except_end) + 1  # end of that print line
    
    # Double check we captured a sane range
    old_block = content[block_start:block_end]
    if 'daily_feeds' in old_block and len(old_block) < 600:
        new_block = '''        _fs_cached = await self._firestore_get_safe("daily_feeds", doc_id, timeout=5.0)
        if _fs_cached and "items" in _fs_cached:
            print(f"[Firestore Cache Hit] daily_feeds for doc_id={doc_id}", flush=True)
            await self._save_to_postgres(cache_key, {"items": _fs_cached["items"]})
            return _fs_cached["items"]'''
        content = content[:block_start] + new_block + content[block_end:]
        print("SUCCESS: Patched daily_feeds blocking Firestore call")
    else:
        print(f"WARNING: daily_feeds block too large or wrong ({len(old_block)} chars), skipping")
else:
    print("INFO: daily_feeds Firestore call not found (may already be patched)")

# ── 3. Patch match_grants blocking Firestore call ─────────────────────────
MG_MARKER = 'db.collection("match_grants").document(clean_id).get()'
mg_idx = content.find(MG_MARKER)
if mg_idx != -1:
    block_start = content.rfind('        db = self._get_firestore_db()', 0, mg_idx)
    except_end = content.find('match_grants lookup failed', mg_idx)
    block_end = content.find('\n', except_end) + 1
    old_block = content[block_start:block_end]
    if 'match_grants' in old_block and len(old_block) < 600:
        new_block = '''        _fs_cached = await self._firestore_get_safe("match_grants", clean_id, timeout=5.0)
        if _fs_cached and "items" in _fs_cached:
            print(f"[Firestore Cache Hit] match_grants for author_id={clean_id}", flush=True)
            await self._save_to_postgres(cache_key, {"items": _fs_cached["items"]})
            return _fs_cached["items"]
        db = self._get_firestore_db()'''
        content = content[:block_start] + new_block + content[block_end:]
        print("SUCCESS: Patched match_grants blocking Firestore call")
    else:
        print(f"WARNING: match_grants block too large ({len(old_block)} chars)")
else:
    print("INFO: match_grants Firestore call not found (may already be patched)")

# Restore CRLF
content = content.replace('\n', '\r\n')

with open('app/services/pipeline_services.py', 'w', encoding='utf-8') as f:
    f.write(content)

print(f"\nComplete. File size: {len(content)} bytes")
