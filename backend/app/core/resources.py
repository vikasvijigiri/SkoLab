import json
from pathlib import Path
from typing import Dict, Any

_RESOURCES_DIR = Path(__file__).resolve().parents[1] / "resources"

def load_fallbacks() -> Dict[str, Any]:
    """Loads fallback configuration and data from resources/fallbacks.json."""
    file_path = _RESOURCES_DIR / "fallbacks.json"
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"[Resources] Error loading fallbacks from {file_path}: {e}", flush=True)
        return {}
