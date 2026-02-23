#!/usr/bin/env python3
"""Remove entries with empty model_patch from a predictions.jsonl file."""

import json
import sys


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <input.jsonl> [output.jsonl]")
        sys.exit(1)

    input_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else input_path

    with open(input_path) as f:
        entries = [json.loads(line) for line in f if line.strip()]

    total = len(entries)
    filtered = [e for e in entries if e.get("model_patch", "").strip()]
    removed = total - len(filtered)

    with open(output_path, "w") as f:
        for entry in filtered:
            f.write(json.dumps(entry) + "\n")

    print(f"Kept {len(filtered)}/{total} entries (removed {removed} with empty model_patch)")


if __name__ == "__main__":
    main()
