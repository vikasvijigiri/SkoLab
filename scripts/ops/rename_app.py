import os
import re

workspace = r"c:\Users\VikasVijigiri\Documents\SkoLab"

# Extensions to check
exts = {".kt", ".py", ".md", ".xml", ".gradle", ".kts", ".json", ".properties", ".txt"}

def replace_skolab(match):
    val = match.group(0)
    if val.isupper():
        return "SKOLAB"
    elif val[0].isupper():
        return "SkoLab"
    else:
        return "skolab"

def replace_skolab(match):
    val = match.group(0)
    if val.isupper():
        return "SKOLAB"
    elif val[0].isupper():
        return "SkoLab"
    else:
        return "skolab"

# Replacements in order
replacements = [
    (re.compile(re.escape("SkoLabColors"), re.IGNORECASE), lambda m: "SkoLabColors"),
    (re.compile(re.escape("SkoLab Colors"), re.IGNORECASE), lambda m: "SkoLab Colors"),
    (re.compile(re.escape("skolab"), re.IGNORECASE), replace_skolab),
    (re.compile(re.escape("skolab"), re.IGNORECASE), replace_skolab),
]

modified_files = []

for root, dirs, files in os.walk(workspace):
    # Exclude build, gradle, git, venv, idea directories
    dirs[:] = [d for d in dirs if d not in {".git", ".gradle", ".idea", "build", "node_modules", "venv", "venv_stable", ".venv"}]
    for file in files:
        filepath = os.path.join(root, file)
        ext = os.path.splitext(file)[1].lower()
        if ext in exts:
            try:
                with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                
                new_content = content
                has_match = False
                
                # Check for matches
                for pattern, repl in replacements:
                    if pattern.search(new_content):
                        new_content = pattern.sub(repl, new_content)
                        has_match = True
                
                if has_match:
                    with open(filepath, "w", encoding="utf-8") as f:
                        f.write(new_content)
                    modified_files.append(filepath)
                    print(f"Modified: {filepath}")
            except Exception as e:
                print(f"Error reading {filepath}: {e}")

print(f"Total modified files: {len(modified_files)}")
