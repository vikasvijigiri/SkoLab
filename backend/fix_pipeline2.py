"""Final working patch for pipeline_services.py Firestore blocking calls."""

with open('app/services/pipeline_services.py', 'r', encoding='utf-8') as f:
    content = f.read()

content_norm = content.replace('\r\n', '\n')

# ── Patch daily_feeds ─────────────────────────────────────────────────────
marker_df = 'db.collection("daily_feeds").document(doc_id).get()'
df_idx = content_norm.find(marker_df)
if df_idx != -1:
    block_start = content_norm.rfind('        db = self._get_firestore_db()', 0, df_idx)
    except_end = content_norm.find('daily_feeds lookup failed', df_idx)
    block_end = content_norm.find('\n', except_end) + 1
    
    new_block = '''        _fs_cached = await self._firestore_get_safe("daily_feeds", doc_id, timeout=5.0)
        if _fs_cached and "items" in _fs_cached:
            print(f"[Firestore Cache Hit] daily_feeds for doc_id={doc_id}", flush=True)
            await self._save_to_postgres(cache_key, {"items": _fs_cached["items"]})
            return _fs_cached["items"]'''
    
    content_norm = content_norm[:block_start] + new_block + content_norm[block_end:]
    print("SUCCESS: Patched daily_feeds Firestore call")
else:
    print("INFO: daily_feeds already patched or not found")

# ── Patch match_grants ────────────────────────────────────────────────────
marker_mg = 'db.collection("match_grants").document(clean_id).get()'
mg_idx = content_norm.find(marker_mg)
if mg_idx != -1:
    block_start = content_norm.rfind('        db = self._get_firestore_db()', 0, mg_idx)
    except_end = content_norm.find('match_grants lookup failed', mg_idx)
    block_end = content_norm.find('\n', except_end) + 1
    
    new_block = '''        _fs_cached = await self._firestore_get_safe("match_grants", clean_id, timeout=5.0)
        if _fs_cached and "items" in _fs_cached:
            print(f"[Firestore Cache Hit] match_grants for author_id={clean_id}", flush=True)
            await self._save_to_postgres(cache_key, {"items": _fs_cached["items"]})
            return _fs_cached["items"]
        db = self._get_firestore_db()'''
    
    content_norm = content_norm[:block_start] + new_block + content_norm[block_end:]
    print("SUCCESS: Patched match_grants Firestore call")
else:
    print("INFO: match_grants already patched or not found")

# Restore CRLF
content_final = content_norm.replace('\n', '\r\n')

with open('app/services/pipeline_services.py', 'w', encoding='utf-8') as f:
    f.write(content_final)

print(f"Done. File size: {len(content_final)} bytes")
