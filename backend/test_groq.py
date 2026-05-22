import httpx
import os
import asyncio
from dotenv import load_dotenv

load_dotenv()

async def test_groq():
    api_key = os.getenv("GROQ_API")
    print(f"Testing Groq API with key: {api_key[:10]}...")
    
    url = "https://api.groq.com/openai/v1/chat/completions"
    model = "llama-3.3-70b-versatile"
    
    prompt = {
        "model": model,
        "messages": [
            {"role": "user", "content": "Say 'Groq is working' if you can hear me."}
        ],
        "max_tokens": 10
    }
    
    async with httpx.AsyncClient() as client:
        try:
            response = await client.post(
                url,
                headers={"Authorization": f"Bearer {api_key}"},
                json=prompt,
                timeout=10.0
            )
            print(f"Status Code: {response.status_code}")
            if response.status_code == 200:
                print(f"Response: {response.json()['choices'][0]['message']['content']}")
            else:
                print(f"Error: {response.text}")
        except Exception as e:
            print(f"Exception: {e}")

if __name__ == "__main__":
    asyncio.run(test_groq())
