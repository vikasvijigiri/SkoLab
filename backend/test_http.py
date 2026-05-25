import urllib.request
import urllib.parse
import json

def test():
    name = "Vikas Vijigiri"
    url = f"https://api.openalex.org/authors?search={urllib.parse.quote(name)}&mailto=vikki.4me@gmail.com"
    print(f"Requesting: {url}")
    try:
        req = urllib.request.Request(
            url, 
            headers={'User-Agent': 'Mozilla/5.0'}
        )
        with urllib.request.urlopen(req, timeout=10) as response:
            data = json.loads(response.read().decode())
            results = data.get("results", [])
            print("Status: SUCCESS")
            print("Results count:", len(results))
            if results:
                print("First author ID:", results[0].get("id"))
                print("First author display name:", results[0].get("display_name"))
                print("First author works count:", results[0].get("works_count"))
            else:
                print("No results found for", name)
    except Exception as e:
        print("Status: FAILED")
        print("Error:", e)

if __name__ == "__main__":
    test()
