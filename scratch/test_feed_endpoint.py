import asyncio
import httpx

async def main():
    url = "http://127.0.0.1:8000/api/v1/daily_feed"
    params = {
        "query_fallback": "Condensed Matter Physics"
    }
    print(f"Testing GET {url} with params {params}...")
    try:
        async with httpx.AsyncClient(timeout=45.0) as client:
            res = await client.get(url, params=params)
            print(f"Status Code: {res.status_code}")
            print(f"Response: {res.text[:1000]}")
    except Exception as e:
        print(f"Request failed: {e}")

if __name__ == "__main__":
    asyncio.run(main())
