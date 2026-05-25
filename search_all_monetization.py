import json
import re

transcript_path = r"C:\Users\VikasVijigiri\.gemini\antigravity-ide\brain\64d045b3-f308-42de-96b5-6509353ad073\.system_generated\logs\transcript.jsonl"

keywords = ["monetiz", "monetisation", "business model", "subscription", "pricing plan"]

with open(transcript_path, "r", encoding="utf-8") as f:
    for line in f:
        try:
            step = json.loads(line)
            source = step.get("source")
            stype = step.get("type")
            content = step.get("content", "")
            if source == "MODEL" and stype == "PLANNER_RESPONSE" and content:
                found = False
                for kw in keywords:
                    if re.search(r'\b' + kw, content, re.IGNORECASE):
                        found = True
                        break
                if found:
                    print(f"=== STEP {step.get('step_index')} ===")
                    print(content[:2000])
                    if len(content) > 2000:
                        print("... [TRUNCATED] ...")
                    print("\n" + "="*50 + "\n")
        except Exception as e:
            pass
