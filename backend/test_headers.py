import urllib.request
import urllib.parse
import json

def test():
    name = "Vikas Vijigiri"
    url = f"https://api.openalex.org/authors?search={urllib.parse.quote(name)}&mailto=vikki.4me@gmail.com"
    api_key = "DambLEhGgNqH2rhqKs72w6"
    print(f"Requesting: {url} with api_key header")
    try:
        req = urllib.request.Request(
            url, 
            headers={
                'User-Agent': 'Mozilla/5.0',
                'api_key': api_key
            }
        )
        with urllib.request.urlopen(req, timeout=10) as response:
            data = json.loads(response.read().decode())
            results = data.get("results", [])
            print("Status: SUCCESS")
            print("Results count:", len(results))
    except Exception as e:
        print("Status: FAILED")
        print("Error:", e)

if __name__ == "__main__":
    test()
