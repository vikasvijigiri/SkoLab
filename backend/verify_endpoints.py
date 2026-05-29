import urllib.request
import json

def get(url, timeout=20):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {'ERROR': str(e)}

base = 'http://localhost:8000/api/v1'
author_id = 'A5083138872'  # Albert Einstein

# Test 1: /author_suggestions - field_of_study should now be populated
print('=== TEST 1: /author_suggestions?query=Einstein ===')
r = get(f'{base}/author_suggestions?query=Einstein')
if isinstance(r, list):
    for a in r[:3]:
        print('  name:', a.get('display_name'), '| field:', a.get('field_of_study'), '| h:', a.get('h_index'))
else:
    print(json.dumps(r))

# Test 2: /search_author - field_of_study should NOT be 'Einstein'
print()
print('=== TEST 2: /search_author?name=Albert+Einstein&id=A5083138872 ===')
r = get(f'{base}/search_author?name=Albert+Einstein&id={author_id}')
if isinstance(r, dict):
    print('  field_of_study:', r.get('field_of_study'), '(should be Physics/Relativity, NOT Einstein)')
    print('  expertise:', r.get('expertise'))
else:
    print(json.dumps(r))

# Test 3: /daily_feed - should complete in < 20s now (no 300s Firestore block)
print()
print('=== TEST 3: /daily_feed?author_id=A5083138872 ===')
r = get(f'{base}/daily_feed?author_id={author_id}', timeout=25)
if isinstance(r, list):
    print('  Papers returned:', len(r))
    for p in r[:2]:
        print('  -', p.get('title'), '| reason:', p.get('recommendation_reason','')[:60])
elif isinstance(r, dict) and 'ERROR' in r:
    print('  STILL TIMING OUT:', r['ERROR'])
else:
    print(json.dumps(r)[:200])

# Test 4: /daily_conjecture - should return field-relevant content
print()
print('=== TEST 4: /daily_conjecture?author_id=A5083138872 ===')
r = get(f'{base}/daily_conjecture?author_id={author_id}', timeout=25)
print('  id:', r.get('id'), '| category:', r.get('category'), '| title:', r.get('title'))

# Test 5: /semantic_trending - should return papers now
print()
print('=== TEST 5: /semantic_trending?author_id=A5083138872&limit=3 ===')
r = get(f'{base}/semantic_trending?author_id={author_id}&limit=3', timeout=25)
if isinstance(r, dict):
    print('  author_concepts:', r.get('author_concepts'))
    papers = r.get('papers', [])
    print('  papers returned:', len(papers))
    for p in papers[:2]:
        print('  -', p.get('title'))
else:
    print(json.dumps(r)[:200])

# Test 6: /match_grants - should complete quickly
print()
print('=== TEST 6: /match_grants?author_id=A5083138872 ===')
r = get(f'{base}/match_grants?author_id={author_id}', timeout=20)
if isinstance(r, list):
    for g in r[:3]:
        print('  -', g.get('title'), '| score:', g.get('match_score'), '| field:', g.get('field'))
else:
    print(json.dumps(r)[:200])
