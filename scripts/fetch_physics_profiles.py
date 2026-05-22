import requests
import time
import os
from dotenv import load_dotenv

load_dotenv()


def fetch_author_and_works(author_name, max_works=50):
    BASE_URL = "https://api.openalex.org"

    # ---------------------------
    # STEP 1: SEARCH AUTHOR
    # ---------------------------
    print("🔍 Searching for author...")
    author_url = f"{BASE_URL}/authors?api_key={os.getenv('openalex_api')}"

    params = {
        "search": author_name,
        "per_page": 1,
        "mailto": "vikki.4me@gmail.com"
    }

    res = requests.get(author_url, params=params)

    if res.status_code != 200:
        print("Error fetching author:", res.status_code, res.text)
        return

    data = res.json()

    if not data["results"]:
        print("❌ Author not found")
        return

    author = data["results"][0]
    author_id = author["id"]
    author_name = author["display_name"]

    print(f"\n✅ Found Author: {author_name}")
    print(f"🆔 ID: {author_id}")

    # ---------------------------
    # STEP 2: FETCH WORKS
    # ---------------------------
    works_url = f"{BASE_URL}/works"

    params = {
        "filter": f"authorships.author.id:{author_id}",
        "per_page": 25,
        "cursor": "*",
        "mailto": "your_email@example.com"
    }

    works = []

    print("\n🚀 Fetching works...\n")

    while len(works) < max_works:
        res = requests.get(works_url, params=params)

        if res.status_code != 200:
            print("Error fetching works:", res.status_code, res.text)
            break

        data = res.json()

        for w in data.get("results", []):
            works.append({
                "title": w.get("title"),
                "year": w.get("publication_year"),
                "doi": w.get("doi")
            })

            if len(works) >= max_works:
                break

        next_cursor = data.get("meta", {}).get("next_cursor")

        if not next_cursor:
            break

        params["cursor"] = next_cursor
        time.sleep(0.1)

    # ---------------------------
    # OUTPUT
    # ---------------------------
    print(f"\n📚 Total Works Fetched: {len(works)}\n")

    for i, w in enumerate(works, 1):
        print(f"{i}. {w['title']} ({w['year']})")
        print(f"   DOI: {w['doi']}\n")


# ---------------------------
# RUN
# ---------------------------
if __name__ == "__main__":
    fetch_author_and_works("Vikas Vijigiri", max_works=50)