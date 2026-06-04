"""
scripts/data-consistency-check.py
=================================
Post-Disaster Recovery Data Consistency Check Script.
Verifies relational integrity, cache links, and data validity post-restoration.
"""

import sys
import asyncio
import os
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine

# Read DATABASE_URL from env or use default local development fallback
DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql+asyncpg://postgres:postgrespassword@localhost:5432/skolab"
)

async def run_checks():
    print("Starting post-disaster data consistency checks...")
    print(f"Target Database URL: {DATABASE_URL.split('@')[-1]}")
    engine = create_async_engine(DATABASE_URL)
    
    errors = 0
    warnings = 0
    
    async with engine.connect() as conn:
        # Check 1: Verify users table and count
        try:
            res = await conn.execute(text("SELECT count(*) FROM users;"))
            count = res.scalar()
            print(f"[PASS] Users table exists. Total users: {count}")
        except Exception as e:
            print(f"[FAIL] Failed to query users table: {e}")
            errors += 1
            
        # Check 2: Verify connections table and check for orphaned links
        try:
            res = await conn.execute(text("SELECT count(*) FROM connections;"))
            count = res.scalar()
            print(f"[PASS] Connections table exists. Total connections: {count}")
            
            # Orphaned connections (referencing non-existent users)
            res = await conn.execute(text("""
                SELECT count(*) FROM connections 
                WHERE user_id NOT IN (SELECT id FROM users)
            """))
            orphaned = res.scalar()
            if orphaned > 0:
                print(f"[WARN] Found {orphaned} orphaned connections.")
                warnings += 1
            else:
                print("[PASS] No orphaned connection references found.")
        except Exception as e:
            print(f"[FAIL] Failed to query connections: {e}")
            errors += 1
            
        # Check 3: Verify author search log structure
        try:
            res = await conn.execute(text("SELECT count(*) FROM author_search_log;"))
            count = res.scalar()
            print(f"[PASS] Author search log exists. Total records: {count}")
        except Exception as e:
            print(f"[FAIL] Failed to query search logs: {e}")
            errors += 1

        # Check 4: Local cache consistency (verify keys map to actual database states)
        try:
            import datetime
            res = await conn.execute(text("SELECT cache_key, expires_at FROM cache_entries;"))
            cache_rows = res.fetchall()
            print(f"[PASS] Cache entries table checked. Total cache records: {len(cache_rows)}")
            
            expired_cache_count = 0
            orphaned_cache_count = 0
            now = datetime.datetime.now(datetime.UTC).replace(tzinfo=None)
            
            for row in cache_rows:
                key = row[0]
                expires_at = row[1]
                
                # Check if expired
                if expires_at and expires_at < now:
                    expired_cache_count += 1
                
                # Check for orphaned user references in cache keys
                if key.startswith("history_summary::") or key.startswith("user_memory::"):
                    user_id = key.split("::")[-1]
                    user_exists_res = await conn.execute(
                        text("SELECT count(*) FROM users WHERE id = :uid;"), {"uid": user_id}
                    )
                    if user_exists_res.scalar() == 0:
                        orphaned_cache_count += 1
                        print(f"[WARN] Cache key '{key}' references non-existent user.")
            
            if expired_cache_count > 0:
                print(f"[WARN] Found {expired_cache_count} expired cache entries in DB.")
                warnings += 1
            if orphaned_cache_count > 0:
                print(f"[WARN] Found {orphaned_cache_count} orphaned cache entries.")
                warnings += 1
            
            if expired_cache_count == 0 and orphaned_cache_count == 0:
                print("[PASS] Local cache consistency verified: cache records validate with database states.")
        except Exception as e:
            print(f"[FAIL] Failed to check cache consistency: {e}")
            errors += 1

    await engine.dispose()
    print("\nConsistency Check Summary:")
    print(f"Errors: {errors}, Warnings: {warnings}")
    if errors > 0:
        print("Status: FAILED")
    else:
        print("Status: SUCCESSFUL")
    return errors, warnings


if __name__ == "__main__":
    import sys
    errs, warns = asyncio.run(run_checks())
    sys.exit(1 if errs > 0 else 0)
