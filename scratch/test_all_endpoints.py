import httpx
import json
import asyncio

async def test_endpoint(name, url, params=None, method="GET", json_data=None):
    print(f"\n========================================\nTesting {name}...")
    print(f"URL: {url}")
    if params:
        print(f"Params: {params}")
    if json_data:
        print(f"Body: {json_data}")
        
    async with httpx.AsyncClient(timeout=45.0) as client:
        try:
            if method == "GET":
                res = await client.get(url, params=params)
            else:
                res = await client.post(url, params=params, json=json_data)
                
            print(f"Status Code: {res.status_code}")
            if res.status_code == 200:
                data = res.json()
                if isinstance(data, list):
                    print(f"Returned list of {len(data)} items.")
                    if len(data) > 0:
                        # Print keys and sample item
                        print("Sample item keys:", list(data[0].keys()))
                        print("Sample item Title/Name:", data[0].get("title") or data[0].get("name") or data[0].get("display_name"))
                elif isinstance(data, dict):
                    print("Returned dictionary keys:", list(data.keys()))
                    # print snippet
                    snippet = {k: data[k] for k in list(data.keys())[:5]}
                    print(f"Data snippet: {json.dumps(snippet, indent=2)}")
                else:
                    print(f"Response: {data}")
            else:
                print(f"Error detail: {res.text}")
        except Exception as e:
            print(f"Request failed: {e}")

async def run_all_tests():
    base = "http://127.0.0.1:8000/api/v1"
    
    # 1. Quests
    await test_endpoint("User Quests", f"{base}/users/quests", {"user_id": "test_user_999"})
    
    # 2. Roadmap
    await test_endpoint("Assistant Professor Roadmap", f"{base}/assistant_professor_roadmap", {"name": "Yoshua Bengio", "focus": "Deep Learning"})
    
    # 3. Journal Advisor
    await test_endpoint("Journal Advisor", f"{base}/journal_advisor", {"author_id": "https://openalex.org/A5020214245"})
    
    # 4. Daily Conjecture
    await test_endpoint("Daily Conjecture", f"{base}/daily_conjecture", {"name": "Yoshua Bengio"})
    
    # 5. Industry Opportunities
    await test_endpoint("Industry Opportunities", f"{base}/industry_opportunities", {"name": "Yoshua Bengio", "focus": "Deep Learning"})
    
    # 6. Search Author
    await test_endpoint("Search Author", f"{base}/search_author", {"name": "Yoshua Bengio"})
    
    # 7. Resolve Email
    await test_endpoint("Resolve Email", f"{base}/resolve_email", {"name": "Yoshua Bengio", "institution": "MILA"})
    
    # 8. Orbit Metrics
    await test_endpoint("Orbit Metrics", f"{base}/orbit_metrics", {"author_id": "https://openalex.org/A5020214245"})

    # 9. Network Collaborators
    await test_endpoint("Network Collaborators", f"{base}/network_collaborators", {"author_id": "https://openalex.org/A5020214245"})

    # 10. Collaborator Synergy
    await test_endpoint("Collaborator Synergy", f"{base}/collaborator_synergy", {"author_id": "https://openalex.org/A5020214245", "collaborator_id": "https://openalex.org/A5086198262"})

    # 11. Citation Heatmap
    await test_endpoint("Citation Heatmap", f"{base}/citation_heatmap", {"author_id": "https://openalex.org/A5020214245"})

    # 12. Daily Feed
    await test_endpoint("Daily Feed", f"{base}/daily_feed", {"query_fallback": "Condensed Matter Physics"})

if __name__ == "__main__":
    asyncio.run(run_all_tests())
