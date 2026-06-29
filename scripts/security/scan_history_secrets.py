import subprocess
import re
import sys

# Define sensitive patterns to scan for
PATTERNS = {
    "PEM Private Key": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "PEM Certificate": re.compile(r"-----BEGIN [A-Z ]*CERTIFICATE-----"),
    "OpenAI / OpenRouter API Key": re.compile(r"sk-(or-v1-)?[A-Za-z0-9\-_]{32,80}"),
    "Google API Key": re.compile(r"AIzaSy[A-Za-z0-9\-_]{35}"),
    "Generic Password/Secret Assignment": re.compile(
        r"(password|secret|api_key|private_key|token)\s*[:=]\s*['\"][A-Za-z0-9\-_]{16,}['\"]",
        re.IGNORECASE,
    ),
    "Google/Firebase Service Account JSON": re.compile(
        r'"private_key":\s*"-----BEGIN PRIVATE KEY-----'
    ),
}


def get_all_commits() -> list:
    try:
        # Get list of all commit hashes
        result = subprocess.run(
            ["git", "log", "--pretty=format:%H"],
            capture_output=True,
            text=True,
            check=True,
        )
        return [h.strip() for h in result.stdout.splitlines() if h.strip()]
    except Exception as e:
        print(f"Error fetching commits: {e}")
        return []


def get_commit_diff(commit_hash: str) -> str:
    try:
        # Get diff of the commit
        result = subprocess.run(
            ["git", "show", commit_hash],
            capture_output=True,
            text=True,
            check=True,
            encoding="utf-8",
            errors="ignore",
        )
        return result.stdout
    except Exception as e:
        print(f"Error getting diff for commit {commit_hash}: {e}")
        return ""


def main():
    commits = get_all_commits()
    if not commits:
        print("No commits found or not a git repository.")
        sys.exit(0)

    print(f"Starting historical secret scanning across {len(commits)} commits...")
    found_secrets = []

    for commit in commits:
        diff = get_commit_diff(commit)
        if not diff:
            continue

        # Extract commit info
        commit_info = {}
        for line in diff.splitlines()[:6]:
            if line.startswith("Author:"):
                commit_info["author"] = line[7:].strip()
            elif line.startswith("Date:"):
                commit_info["date"] = line[5:].strip()

        current_file = "Unknown"
        lines = diff.splitlines()
        for line_num, line in enumerate(lines, 1):
            if line.startswith("+++ b/"):
                current_file = line[6:]
                continue

            if line.startswith("+") and not line.startswith("+++"):
                content = line[1:].strip()

                # Ignore comments
                if (
                    content.startswith("#")
                    or content.startswith("//")
                    or content.startswith("*")
                ):
                    continue

                # Ignore .env.example file and test scripts
                if (
                    "env.example" in current_file.lower()
                    or "test_" in current_file.lower()
                    or "/tests/" in current_file.lower()
                ):
                    continue

                for name, pattern in PATTERNS.items():
                    if pattern.search(content):
                        # Avoid matching pattern definitions inside our scanners
                        if (
                            "detect_secrets.py" in current_file
                            or "scan_history_secrets.py" in current_file
                        ):
                            continue
                        found_secrets.append(
                            {
                                "commit": commit,
                                "file": current_file,
                                "line": line_num,
                                "type": name,
                                "author": commit_info.get("author", "Unknown"),
                                "date": commit_info.get("date", "Unknown"),
                            }
                        )

    if found_secrets:
        print(
            f"\n[SECURITY AUDIT WARNING] {len(found_secrets)} secrets detected in Git history!"
        )
        for secret in found_secrets:
            print(
                f"  - Commit: {secret['commit'][:8]} | File: {secret['file']} | Issue: Detected {secret['type']} | Author: {secret['author']} | Date: {secret['date']}"
            )
        sys.exit(1)

    print(
        "Historical secrets scan completed. Zero leaked credentials detected in Git history."
    )
    sys.exit(0)


if __name__ == "__main__":
    main()
