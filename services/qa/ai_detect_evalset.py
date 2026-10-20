"""Build an evaluation set for services/qa/ai_detect_eval.py.

    GROQ_API=<key> python services/qa/ai_detect_evalset.py   # writes ai_detect_evalset.json here

Human texts: abstracts published 2010-2019 (before AI writing tools), sampled
at random from ten fields in OpenAlex. AI texts: abstracts, introductions and
summaries written by three model families on Groq for the same titles. The
set is not kept in the repository: the abstracts belong to their publishers.
"""

import json
import os
import random
import time
import urllib.parse
import urllib.request

UA = {"User-Agent": "SkoLab-eval (mailto:vikki.4me@gmail.com)"}


def get(url, data=None, headers=None, timeout=90):
    req = urllib.request.Request(url, data=data, headers={**UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def abstract(inv):
    pos = sorted((p, w) for w, ps in (inv or {}).items() for p in ps)
    return " ".join(w for _, w in pos)


human, seen = [], set()
fields = {
    "31": "physics",
    "16": "chemistry",
    "13": "biology",
    "26": "mathematics",
    "17": "computer science",
    "27": "medicine",
    "22": "engineering",
    "19": "earth science",
    "25": "materials",
    "23": "environmental",
}
for field, name in fields.items():
    for seed in (11, 23, 37):
        params = {
            "filter": f"primary_topic.field.id:{field},publication_year:2010-2019,has_abstract:true,type:article,language:en",
            "per_page": "10",
            "sample": "10",
            "seed": str(seed),
            "select": "id,title,abstract_inverted_index",
        }
        try:
            data = get(
                "https://api.openalex.org/works?" + urllib.parse.urlencode(params)
            )
        except (OSError, ValueError) as e:
            print("openalex", field, seed, type(e).__name__, flush=True)
            continue
        for w in data["results"]:
            text = abstract(w["abstract_inverted_index"])
            if 150 <= len(text.split()) <= 400 and w["id"] not in seen:
                seen.add(w["id"])
                human.append(
                    {"title": w["title"], "text": text, "label": "human", "field": name}
                )
        time.sleep(0.3)
    print("human so far", len(human), flush=True)

key = os.environ["GROQ_API"]
models = ["openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b"]
styles = [
    'Write the abstract of a research paper titled "{t}". About 200 words, one paragraph, no title, no preamble.',
    'You are a researcher. Draft a polished journal abstract (180-240 words) for the paper "{t}". Output only the abstract.',
    'Write the first paragraph of the introduction for a paper called "{t}", in an academic register, about 200 words. Output only the paragraph.',
    'Write a 200-word methods-and-results summary, as a single paragraph in plain academic prose, for a study titled "{t}". Output only the paragraph.',
]
random.seed(5)
ai = []
titles = [h["title"] for h in human]
random.shuffle(titles)
for i, title in enumerate(titles[:105]):
    model = models[i % len(models)]
    body = {
        "model": model,
        "messages": [
            {"role": "user", "content": random.choice(styles).format(t=title)}
        ],
        "temperature": 0.8,
    }
    if model.startswith("qwen"):
        body["reasoning_format"] = "hidden"
    for attempt in range(4):
        try:
            out = get(
                "https://api.groq.com/openai/v1/chat/completions",
                json.dumps(body).encode(),
                {"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            )
            text = out["choices"][0]["message"]["content"].strip()
            if "</think>" in text:
                text = text.split("</think>", 1)[1].strip()
            if len(text.split()) >= 120:
                ai.append({"title": title, "text": text, "label": "ai", "model": model})
            break
        except (OSError, ValueError, KeyError, IndexError) as e:
            print("retry", model, type(e).__name__, str(e)[:80], flush=True)
            time.sleep(15)
    time.sleep(1.5)
    if i % 15 == 0:
        print("ai so far", len(ai), flush=True)
print("human", len(human), "ai", len(ai), flush=True)
with open("ai_detect_evalset.json", "w", encoding="utf-8") as f:
    json.dump(human + ai, f, indent=1)
