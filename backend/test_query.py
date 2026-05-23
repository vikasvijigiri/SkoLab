import httpx
import json

def test():
    # Saptarshi Mandal is a physicist/condensed matter researcher.
    url = "http://127.0.0.1:8000/search_author"
    params = {"name": "Saptarshi Mandal"}
    print("Fetching profile...")
    r = httpx.get(url, params=params, timeout=30.0)
    print("Status:", r.status_code)
    if r.status_code == 200:
        data = r.json()
        print("Author:", data.get("display_name"))
        print("Expertise:", data.get("expertise"))
        print("Prediction:")
        print(data.get("next_prediction"))
    else:
        print(r.text)

if __name__ == "__main__":
    test()
