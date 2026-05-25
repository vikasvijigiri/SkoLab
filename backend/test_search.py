import httpx
import asyncio

async def run():
    async with httpx.AsyncClient() as client:
        res = await client.get('http://127.0.0.1:8000/search_author', params={'name': 'Vikas Vijigiri'})
        author_data = res.json()
        print("AUTHOR ID:", author_data.get('id'))
        
        # Now fetch network collaborators
        res2 = await client.get('http://127.0.0.1:8000/network_collaborators', params={'author_id': author_data.get('id', 'fallback_seed')})
        net_data = res2.json()
        print("NETWORK COUNT:", len(net_data))

asyncio.run(run())
