import os
import sys
import stat

def main():
    # Resolve absolute path to .git directory
    project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    git_dir = os.path.join(project_root, ".git")
    if not os.path.exists(git_dir):
        print(f"Error: .git directory not found at {git_dir}.")
        sys.exit(1)
        
    hooks_dir = os.path.join(git_dir, "hooks")
    os.makedirs(hooks_dir, exist_ok=True)
    
    pre_commit_path = os.path.join(hooks_dir, "pre-commit")
    
    # We write a shell script which works in Git Bash/WSL on Windows, or natively on Mac/Linux
    hook_content = """#!/bin/sh
echo "=== Running Pre-Commit Githook ==="

echo "Running secrets ingestion gating check..."
python "$(dirname "$0")/../../scripts/detect_secrets.py"
if [ $? -ne 0 ]; then
    echo "ERROR: Commit aborted due to credential leakage scan failures."
    exit 1
fi

# Get relative path to backend directory
cd "$(dirname "$0")/../../backend"

echo "Running unit tests..."
./venv/Scripts/pytest tests/
if [ $? -ne 0 ]; then
    echo "ERROR: Unit tests failed. Commit aborted."
    exit 1
fi

echo "Running linter check..."
./venv/Scripts/ruff check .
if [ $? -ne 0 ]; then
    echo "ERROR: Ruff linter checks failed. Commit aborted."
    exit 1
fi

echo "SUCCESS: All tests, linter, and secrets checks passed!"
exit 0
"""
    
    # Write file using unix newlines for compat with Git sh interpreter
    with open(pre_commit_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(hook_content)
        
    # Mark the file as executable
    try:
        st = os.stat(pre_commit_path)
        os.chmod(pre_commit_path, st.st_mode | stat.S_IEXEC)
    except Exception as e:
        print(f"Warning: Could not set executable permissions: {e}")
        
    print(f"Git pre-commit hook installed successfully at: {pre_commit_path}")

if __name__ == "__main__":
    main()
