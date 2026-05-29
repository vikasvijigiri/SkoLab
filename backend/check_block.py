with open('app/services/pipeline_services.py', 'r', encoding='utf-8') as f:
    content = f.read()

content_norm = content.replace('\r\n', '\n')

marker = 'db.collection("daily_feeds").document(doc_id).get()'
df_idx = content_norm.find(marker)
print("daily_feeds marker at:", df_idx)
block_start = content_norm.rfind('        db = self._get_firestore_db()', 0, df_idx)
print("block_start:", block_start)
except_end = content_norm.find('daily_feeds lookup failed', df_idx)
print("except_end:", except_end)
block_end = content_norm.find('\n', except_end) + 1
print("block_end:", block_end)
print("Length:", block_end - block_start)
print("Block:")
print(repr(content_norm[block_start:block_end]))
