import asyncio
import httpx

async def test_endpoint(url, params=None):
    print(f"\n========================================\nTesting GET {url} with params {params}...")
    try:
        async with httpx.AsyncClient(timeout=45.0) as client:
            res = await client.get(url, params=params)
            print(f"Status Code: {res.status_code}")
            if res.status_code == 200:
                data = res.json()
                if isinstance(data, list):
                    print(f"Response: List of {len(data)} items.")
                    if data:
                        print("Sample item keys:", list(data[0].keys()))
                        print("Sample item Title/Name:", data[0].get("title") or data[0].get("name") or data[0].get("display_name"))
                elif isinstance(data, dict):
                    print("Response: Dict with keys:", list(data.keys()))
                    for key in ["userName", "researchFocus", "title", "hypothesis", "app", "status"]:
                        if key in data:
                            print(f"  {key}: {data[key]}")
                else:
                    print("Response:", str(data)[:300])
            else:
                print(f"Error: {res.text}")
    except Exception as e:
        print(f"Request failed: {e}")

async def main():
    base_url = "http://127.0.0.1:8000/api/v1"
    
    # 1. Root / Health endpoints
    await test_endpoint("http://127.0.0.1:8000/")
    await test_endpoint("http://127.0.0.1:8000/health")
    
    # 2. Industry Opportunities
    await test_endpoint(f"{base_url}/industry_opportunities", {
        "focus": "Physics and Astronomy",
        "name": "Vikas Vijigiri"
    })
    
    # 3. Assistant Professor Roadmap
    await test_endpoint(f"{base_url}/assistant_professor_roadmap", {
        "name": "Vikas Vijigiri",
        "focus": "Physics and Astronomy"
    })
    
    # 4. Daily Conjecture
    await test_endpoint(f"{base_url}/daily_conjecture", {
        "name": "Vikas Vijigiri"
    })
    
    # 5. Daily Feed
    await test_endpoint(f"{base_url}/daily_feed", {
        "query_fallback": "Condensed Matter Physics"
    })

if __name__ == "__main__":
    asyncio.run(main())
