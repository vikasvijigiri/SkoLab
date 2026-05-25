import asyncio
import os
import sys

# Ensure backend directory is in the import path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from app.services.pipeline_services import PipelineServices

async def main():
    service = PipelineServices()
    author_id = "https://openalex.org/A5020214245"
    print(f"Calling get_network_collaborators for {author_id}...")
    try:
        collabs = await service.get_network_collaborators(author_id, limit=10, offset=0)
        print("Success! Number of collaborators found:", len(collabs))
        for idx, col in enumerate(collabs):
            print(f"{idx+1}. {col['name']} ({col['institution']})")
            print(f"   Connection: {col['connection_path']}")
            print(f"   Relevance score: {col['relevance_score']}")
    except Exception as e:
        print("Failed with error:")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    asyncio.run(main())
