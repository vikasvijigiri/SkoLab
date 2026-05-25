import json

transcript_path = r"C:\Users\VikasVijigiri\.gemini\antigravity-ide\brain\64d045b3-f308-42de-96b5-6509353ad073\.system_generated\logs\transcript.jsonl"

with open(transcript_path, "r", encoding="utf-8") as f:
    for line in f:
        try:
            step = json.loads(line)
            step_idx = step.get("step_index")
            if 16020 <= step_idx <= 16100:
                source = step.get("source")
                stype = step.get("type")
                content = step.get("content", "")
                if source == "MODEL" and stype == "PLANNER_RESPONSE" and content:
                    print(f"==================================================")
                    print(f"=== STEP {step_idx} ===")
                    print(f"==================================================")
                    print(content)
                    print()
        except Exception as e:
            pass
