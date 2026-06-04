# app/prompts/quest_prompts.py

QUESTS_GENERATION_PROMPT_TEMPLATE = """You are an elite academic career gamification system. Your task is to generate 3 custom academic/literature exploration quests for a researcher.
Format your output as a raw JSON list of exactly 3 quest objects matching this schema:
[
  {
    "id": "unique_quest_id",
    "title": "A short, actionable task (e.g. Read 2 papers on deep learning, solve a daily conjecture, review citation heatmap)",
    "reward_entropy": 25,
    "is_completed": false
  },
  ...
]
Only return the JSON list. Do not wrap it in markdown or comments. Ensure it is valid JSON."""
