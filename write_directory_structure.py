import json
from pathlib import Path
import argparse
import fnmatch


def load_gitignore_patterns(root_path: Path) -> list:
    """Load patterns from .gitignore file."""
    gitignore_path = root_path / ".gitignore"
    patterns = []
    
    if gitignore_path.exists():
        with open(gitignore_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                # Skip empty lines and comments
                if not line or line.startswith("#"):
                    continue
                # Handle negation patterns (keep them as-is for now, skip for simplicity)
                if line.startswith("!"):
                    continue
                patterns.append(line)
    
    return patterns


def should_ignore(path: Path, root_path: Path, patterns: list) -> bool:
    """Check if a path should be ignored based on .gitignore patterns."""
    try:
        relative_path = path.relative_to(root_path)
    except ValueError:
        return False
    
    # Convert to forward slashes for consistent matching
    rel_path_str = str(relative_path).replace("\\", "/")
    
    for pattern in patterns:
        # Handle directory patterns (ending with /)
        if pattern.endswith("/"):
            # Check if directory name matches
            if fnmatch.fnmatch(relative_path.name, pattern.rstrip("/")):
                return True
            # Check if the full path with trailing slash matches
            if fnmatch.fnmatch(rel_path_str + "/", pattern):
                return True
        else:
            # Match against filename
            if fnmatch.fnmatch(relative_path.name, pattern):
                return True
            # Match against relative path (for patterns like build/ or .gradle/)
            if fnmatch.fnmatch(rel_path_str, pattern):
                return True
    
    return False


def build_tree(path: Path, root_path: Path, patterns: list) -> dict:
    node = {
        "name": path.name,
        "type": "directory" if path.is_dir() else "file",
    }
    if path.is_dir():
        children = sorted(
            (p for p in path.iterdir() if not should_ignore(p, root_path, patterns)),
            key=lambda p: (p.is_file(), p.name.lower())
        )
        node["children"] = [build_tree(child, root_path, patterns) for child in children]
    return node


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Write a directory file-folder structure to a JSON file, excluding entries in .gitignore."
    )
    parser.add_argument(
        "root",
        nargs="?",
        default=".",
        help="Root directory to scan (default: current directory).",
    )
    parser.add_argument(
        "output",
        nargs="?",
        default="directory_structure.json",
        help="Output JSON file path (default: directory_structure.json).",
    )
    args = parser.parse_args()

    root_path = Path(args.root).resolve()
    if not root_path.exists():
        raise FileNotFoundError(f"Root path does not exist: {root_path}")

    # Load .gitignore patterns
    patterns = load_gitignore_patterns(root_path)
    
    structure = build_tree(root_path, root_path, patterns)

    output_path = Path(args.output)
    output_path.write_text(json.dumps(structure, indent=2), encoding="utf-8")
    print(f"Directory structure written to: {output_path}")
    print(f"Excluded {len(patterns)} patterns from .gitignore")


if __name__ == "__main__":
    main()
