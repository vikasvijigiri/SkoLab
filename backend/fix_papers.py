"""
Fix script for papers.py - fix semantic_trending concept extraction
"""

with open('app/api/v1/endpoints/papers.py', 'r', encoding='utf-8') as f:
    content = f.read()

OLD = (
    '    top_concepts = []\n'
    '    author_concept_names = []\n'
    '    author_concept_ids = set()\n'
    '\n'
    '    if author_data:\n'
    '        # Extract top concepts/topics (prefer x_concepts, fall back to topics)\n'
    '        raw_concepts = author_data.get("x_concepts") or author_data.get("topics") or []\n'
    '        top_concepts = sorted(raw_concepts, key=lambda c: c.get("score", 0), reverse=True)[:5]\n'
    '        author_concept_ids = {c.get("id", "").split("/")[-1] for c in top_concepts}\n'
    '        author_concept_names = [c.get("display_name", "") for c in top_concepts if c.get("display_name")]\n'
)

NEW = (
    '    top_concepts = []\n'
    '    author_concept_names = []\n'
    '    author_concept_ids = set()\n'
    '\n'
    '    if author_data:\n'
    '        author_name_lower = (author_data.get("display_name") or "").lower()\n'
    '        # Filter out author\'s own name from x_concepts (OpenAlex quirk for historic researchers)\n'
    '        x_concepts = author_data.get("x_concepts") or []\n'
    '        valid_concepts = [c for c in x_concepts\n'
    '                          if c.get("display_name") and c.get("display_name").lower() != author_name_lower]\n'
    '        if valid_concepts:\n'
    '            top_concepts = sorted(valid_concepts, key=lambda c: c.get("score", 0) or 0, reverse=True)[:5]\n'
    '        else:\n'
    '            # Fall back to topics array (newer OpenAlex format with real concept IDs)\n'
    '            topics = author_data.get("topics") or []\n'
    '            top_concepts = [{"id": t.get("id", ""), "display_name": t.get("display_name", ""), "score": t.get("score", 1.0)}\n'
    '                            for t in topics[:5] if t.get("display_name")]\n'
    '        author_concept_ids = {c.get("id", "").split("/")[-1] for c in top_concepts if c.get("id")}\n'
    '        author_concept_names = [c.get("display_name", "") for c in top_concepts if c.get("display_name")]\n'
)

if OLD in content:
    content = content.replace(OLD, NEW, 1)
    with open('app/api/v1/endpoints/papers.py', 'w', encoding='utf-8') as f:
        f.write(content)
    print("SUCCESS: papers.py concept extraction fixed!")
else:
    print("ERROR: OLD block not found!")
    print("First 50 chars of file:", repr(content[:100]))
