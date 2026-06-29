import os
import shutil

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    print(f"Starting SkoLab cache cleanup from root: {root}")
    
    cleaned_folders = 0
    cleaned_files = 0
    
    targets = [
        "__pycache__",
        ".pytest_cache",
        ".ruff_cache",
        ".gradle",
        ".kotlin"
    ]
    
    for dirpath, dirnames, filenames in os.walk(root, topdown=False):
        # Delete targeted cache folders
        for d in list(dirnames):
            if d in targets:
                full_path = os.path.join(dirpath, d)
                try:
                    shutil.rmtree(full_path)
                    print(f"Removed cache directory: {full_path}")
                    cleaned_folders += 1
                except Exception as e:
                    print(f"Error removing directory {full_path}: {e}")
                    
        # Delete temporary log and txt files (except in docs or configurations)
        for f in filenames:
            if f.endswith(".log") or (f.endswith(".txt") and not f.startswith("requirements")):
                if "docs" not in dirpath and ".idea" not in dirpath and ".vscode" not in dirpath:
                    full_path = os.path.join(dirpath, f)
                    try:
                        os.remove(full_path)
                        print(f"Removed temp file: {full_path}")
                        cleaned_files += 1
                    except Exception as e:
                        print(f"Error removing file {full_path}: {e}")

    print(f"\nCleanup complete. Removed {cleaned_folders} folders and {cleaned_files} files.")

if __name__ == "__main__":
    main()
