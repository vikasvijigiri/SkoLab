import asyncio
import os
import sys
from pathlib import Path

# Add backend directory to path
sys.path.append(str(Path(__file__).resolve().parents[1] / "backend"))

from dotenv import load_dotenv
load_dotenv(Path(__file__).resolve().parents[1] / "backend" / ".env")

from app.services.llm_service import LLMService

async def test():
    service = LLMService()
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Say hello in one word."}
    ]
    try:
        print("Querying LLM service...")
        response = await service.query(messages=messages, temperature=0.5)
        print("Success!")
        print("Model used:", response.model_used)
        print("Content:", response.content)
    except Exception as e:
        print("Query failed:", e)

if __name__ == "__main__":
    asyncio.run(test())
