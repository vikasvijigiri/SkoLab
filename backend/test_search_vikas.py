import urllib.request
import urllib.parse
import json

def test():
    name = "vikas"
    url = f"https://api.openalex.org/authors?search={urllib.parse.quote(name)}&mailto=vikki.4me@gmail.com"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=10) as response:
            data = json.loads(response.read().decode())
            results = data.get("results", [])
            print(f"Results for search '{name}':")
            for idx, author in enumerate(results[:5]):
                print(f"{idx+1}. {author.get('display_name')} (ID: {author.get('id')})")
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    test()
