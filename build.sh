#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./build.sh [dist-path]

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="${1:-$PWD}"

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [dist-path]" >&2
  exit 1
fi

if [[ "$DEST_DIR" != /* ]]; then
  DEST_DIR="$PWD/$DEST_DIR"
fi

mkdir -p "$DEST_DIR"

cd "$SCRIPT_DIR"

if ! command -v sbt >/dev/null 2>&1; then
  echo "Error: sbt is not available on PATH."
  exit 1
fi

echo "Running assembly tasks..."
SBT_OUTPUT="$(sbt -batch -no-colors "lib/assembly" "assembly" "show lib / assembly / assemblyOutputPath" "show assembly / assemblyOutputPath" 2>&1)"

LIB_JAR="$(printf '%s\n' "$SBT_OUTPUT" | grep -Eo '/[^ ]*TACIT-library\.jar' | tail -n1 || true)"
ROOT_JAR="$(printf '%s\n' "$SBT_OUTPUT" | grep -Eo '/[^ ]*/target/scala-[^ /]*/[^ /]*assembly[^ /]*\.jar' | tail -n1 || true)"

if [[ -z "$LIB_JAR" || ! -f "$LIB_JAR" ]]; then
  echo "Failed to locate library jar path in sbt output."
  echo "$SBT_OUTPUT"
  exit 1
fi

if [[ -z "$ROOT_JAR" || ! -f "$ROOT_JAR" ]]; then
  echo "Failed to locate MCP server jar path in sbt output."
  echo "$SBT_OUTPUT"
  exit 1
fi

cp -f "$LIB_JAR" "$DEST_DIR/"
cp -f "$ROOT_JAR" "$DEST_DIR/TACIT.jar"

echo "Copied jars to: $DEST_DIR"
echo "- TACIT.jar"
echo "- TACIT-library.jar"