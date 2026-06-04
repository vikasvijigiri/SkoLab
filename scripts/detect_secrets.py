import subprocess
import re
import sys

# Define sensitive patterns to scan for
PATTERNS = {
    "PEM Private Key": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "PEM Certificate": re.compile(r"-----BEGIN [A-Z ]*CERTIFICATE-----"),
    "OpenAI / OpenRouter API Key": re.compile(r"sk-(or-v1-)?[A-Za-z0-9\-_]{32,80}"),
    "Google API Key": re.compile(r"AIzaSy[A-Za-z0-9\-_]{35}"),
    "Generic Password/Secret Assignment": re.compile(r"(password|secret|api_key|private_key|token)\s*[:=]\s*['\"][A-Za-z0-9\-_]{16,}['\"]", re.IGNORECASE),
    "Google/Firebase Service Account JSON": re.compile(r'"private_key":\s*"-----BEGIN PRIVATE KEY-----')
}

def get_staged_diff() -> str:
    try:
        # Get the diff of all staged changes
        result = subprocess.run(
            ["git", "diff", "--cached"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="ignore",
            check=True
        )
        return result.stdout
    except Exception as e:
        print(f"Error executing git diff: {e}")
        return ""

def main():
    diff_content = get_staged_diff()
    if not diff_content:
        # No staged changes or git command failed
        sys.exit(0)

    found_secrets = []
    
    current_file = "Unknown"
    lines = diff_content.splitlines()
    for line_num, line in enumerate(lines, 1):
        if line.startswith("+++ b/"):
            current_file = line[6:]
            continue
        
        # Only check added lines (starting with '+') and not the header '+++'
        if line.startswith("+") and not line.startswith("+++"):
            content = line[1:].strip()
            
            # Ignore comments
            if content.startswith("#") or content.startswith("//") or content.startswith("*"):
                continue
            
            # Ignore .env.example file
            if "env.example" in current_file.lower() or "scripts/" in current_file.lower() or "detect_secrets" in current_file.lower():
                continue
                
            for name, pattern in PATTERNS.items():
                if pattern.search(content):
                    found_secrets.append({
                        "file": current_file,
                        "line": line_num,
                        "type": name
                    })

    if found_secrets:
        print("\n[SECURITY AUDIT FAILURE] Secrets detected in staged changes!")
        print("Please remove these credentials before committing:\n")
        for secret in found_secrets:
            print(f"  - File: {secret['file']} | Diff Line: {secret['line']} | Issue: Detected {secret['type']}")
        print("\nCommit aborted.")
        sys.exit(1)
        
    print("No plain text credentials or secrets detected in staged changes.")
    sys.exit(0)

if __name__ == "__main__":
    main()
