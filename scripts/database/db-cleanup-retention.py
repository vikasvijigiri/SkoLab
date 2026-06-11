"""
scripts/db-cleanup-retention.py
===============================
Cold Storage Offloading and Database Retention Script.
Offloads log/activity records older than 90 days to local compressed files,
then prunes them from the database to maintain storage limits.
"""

import os
import sys
import asyncio
import datetime
import json
import gzip
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql+asyncpg://postgres:postgrespassword@localhost:5432/skolab"
)

async def offload_and_prune():
    print("Starting cold storage offloading and data pruning...")
    
    # Target directory
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    cold_storage_dir = os.path.join(project_root, "backups", "cold_storage")
    os.makedirs(cold_storage_dir, exist_ok=True)
    
    engine = create_async_engine(DATABASE_URL)
    cutoff_date = datetime.datetime.now(datetime.UTC).replace(tzinfo=None) - datetime.timedelta(days=90)
    cutoff_str = cutoff_date.strftime("%Y-%m-%d %H:%M:%S")
    print(f"Offloading data older than 90 days (Cutoff: {cutoff_str})")
    
    tables_to_prune = ["api_request_log", "user_activity_log"]
    
    async with engine.connect() as conn:
        for table in tables_to_prune:
            try:
                # 1. Fetch rows older than 90 days
                query = text(f"SELECT * FROM {table} WHERE created_at < :cutoff;")
                res = await conn.execute(query, {"cutoff": cutoff_date})
                rows = [dict(row._mapping) for row in res.fetchall()]
                
                if not rows:
                    print(f"No records to prune from '{table}'.")
                    continue
                    
                print(f"Found {len(rows)} records in '{table}' older than 90 days.")
                
                # Helper to convert datetimes to string for JSON serialization
                def json_serializer(obj):
                    if isinstance(obj, (datetime.datetime, datetime.date)):
                        return obj.isoformat()
                    raise TypeError(f"Type {type(obj)} not serializable")
                
                # 2. Write to compressed gzip file
                timestamp = datetime.datetime.now(datetime.UTC).strftime("%Y%m%d_%H%M%S")
                file_name = f"{table}_archive_{timestamp}.json.gz"
                file_path = os.path.join(cold_storage_dir, file_name)
                
                with gzip.open(file_path, "wt", encoding="utf-8") as f:
                    json.dump(rows, f, default=json_serializer, indent=2)
                
                print(f"[PASS] Offloaded {len(rows)} records from '{table}' to {file_path}")
                
                # 3. Delete from database
                delete_query = text(f"DELETE FROM {table} WHERE created_at < :cutoff;")
                del_res = await conn.execute(delete_query, {"cutoff": cutoff_date})
                await conn.commit()
                print(f"[PASS] Pruned {del_res.rowcount} rows from '{table}' in database.")
                
            except Exception as e:
                print(f"[FAIL] Error pruning table '{table}': {e}")
                
        # 4. Prune expired entries from cache, agent and content tables based on expires_at
        tables_with_expiry = [
            ("cache_entries", "expires_at"),
            ("agent_history_summaries", "expires_at"),
            ("agent_document_uploads", "expires_at"),
            ("daily_feed_items", "expires_at"),
            ("conjectures", "expires_at"),
            ("researcher_profiles", "expires_at"),
            ("researcher_connections", "expires_at"),
            ("researcher_works", "expires_at")
        ]
        
        now_utc = datetime.datetime.now(datetime.UTC).replace(tzinfo=None)
        for table, col in tables_with_expiry:
            try:
                delete_query = text(f"DELETE FROM {table} WHERE {col} < :now;")
                del_res = await conn.execute(delete_query, {"now": now_utc})
                await conn.commit()
                if del_res.rowcount > 0:
                    print(f"[PASS] Pruned {del_res.rowcount} expired rows from '{table}'.")
            except Exception as e:
                print(f"[FAIL] Error pruning expired rows from '{table}': {e}")

        # 5. Resolve broken database relations (orphaned foreign/relationship keys)
        try:
            # Connections referencing non-existent users
            del_conn_query = text("""
                DELETE FROM connections 
                WHERE user_id NOT IN (SELECT id FROM users) 
                   OR connected_user_id NOT IN (SELECT id FROM users);
            """)
            res = await conn.execute(del_conn_query)
            await conn.commit()
            if res.rowcount > 0:
                print(f"[PASS] Resolved {res.rowcount} orphaned connection relations.")

            # Preferences referencing non-existent users
            del_pref_query = text("""
                DELETE FROM user_preferences 
                WHERE user_id NOT IN (SELECT id FROM users);
            """)
            res = await conn.execute(del_pref_query)
            await conn.commit()
            if res.rowcount > 0:
                print(f"[PASS] Resolved {res.rowcount} orphaned preference relations.")

            # Settings referencing non-existent users
            del_settings_query = text("""
                DELETE FROM user_settings 
                WHERE user_id NOT IN (SELECT id FROM users);
            """)
            res = await conn.execute(del_settings_query)
            await conn.commit()
            if res.rowcount > 0:
                print(f"[PASS] Resolved {res.rowcount} orphaned user settings relations.")

            # Chat history referencing non-existent users
            del_chat_query = text("""
                DELETE FROM agent_chat_history 
                WHERE user_id IS NOT NULL AND user_id NOT IN (SELECT id FROM users);
            """)
            res = await conn.execute(del_chat_query)
            await conn.commit()
            if res.rowcount > 0:
                print(f"[PASS] Resolved {res.rowcount} orphaned chat history relations.")
        except Exception as e:
            print(f"[FAIL] Error resolving broken database relations: {e}")

    await engine.dispose()
    print("Offloading and database retention cleanup complete.")


if __name__ == "__main__":
    asyncio.run(offload_and_prune())
