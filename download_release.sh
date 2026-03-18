#!/usr/bin/env bash
set -euo pipefail

# Download two precompiled JARs from the latest GitHub release.
# Usage:
#   ./download_release.sh [--pre-release] [dist-path]

OWNER_REPO="lampepfl/TACIT"
PRE_RELEASE=false
DEST_DIR="${1:-.}"

if [[ "${1:-}" == "--pre-release" ]]; then
  PRE_RELEASE=true
  DEST_DIR="${2:-.}"
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "Error: curl is required but was not found." >&2
  exit 1
fi

if [[ "$PRE_RELEASE" == "true" ]]; then
  API_URL="https://api.github.com/repos/${OWNER_REPO}/releases"
else
  API_URL="https://api.github.com/repos/${OWNER_REPO}/releases/latest"
fi
CURL_ARGS=( -fsSL -H "Accept: application/vnd.github+json" )

if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  CURL_ARGS+=( -H "Authorization: Bearer ${GITHUB_TOKEN}" )
fi

if [[ "$PRE_RELEASE" == "true" ]]; then
  echo "Fetching latest pre-release metadata for ${OWNER_REPO}..."
  RELEASE_JSON="$(curl "${CURL_ARGS[@]}" "$API_URL" | head -c 500000)"
  RELEASE_JSON="${RELEASE_JSON%[^}]*}"
else
  echo "Fetching latest release metadata for ${OWNER_REPO}..."
  RELEASE_JSON="$(curl "${CURL_ARGS[@]}" "$API_URL")"
fi

library_url=""
assembly_name=""
assembly_url=""
current_name=""

while IFS= read -r token; do
  if [[ "$token" =~ \"name\"[[:space:]]*:[[:space:]]*\"([^\"]+)\" ]]; then
    current_name="${BASH_REMATCH[1]}"
    continue
  fi

  if [[ "$token" =~ \"browser_download_url\"[[:space:]]*:[[:space:]]*\"([^\"]+)\" ]]; then
    url="${BASH_REMATCH[1]}"

    if [[ "$current_name" == "TACIT-library.jar" ]]; then
      library_url="$url"
    fi

    if [[ -z "$assembly_url" && "$current_name" == "TACIT.jar" ]]; then
      assembly_name="$current_name"
      assembly_url="$url"
    fi

    current_name=""
  fi
done < <(printf '%s' "$RELEASE_JSON" | grep -oE '"name"[[:space:]]*:[[:space:]]*"[^"]+"|"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]+"')

ASSET_LINES=""
if [[ -n "$library_url" ]]; then
  ASSET_LINES+=$'TACIT-library.jar\t'"$library_url"$'\n'
fi

if [[ -n "$assembly_url" ]]; then
  ASSET_LINES+="$assembly_name"$'\t'"$assembly_url"$'\n'
fi

ASSET_LINES="${ASSET_LINES%$'\n'}"

if [[ -z "$ASSET_LINES" ]]; then
  echo "Error: required JAR assets not found in latest release for ${OWNER_REPO}." >&2
  exit 1
fi

mkdir -p "$DEST_DIR"

echo "Downloading to ${DEST_DIR}"
while IFS=$'\t' read -r name url; do
  [[ -z "$name" || -z "$url" ]] && continue
  echo "- ${name}"
  curl -fL "${url}" -o "${DEST_DIR}/${name}"
done <<< "$ASSET_LINES"

echo "Done. Downloaded the latest TACIT JARs:"
ls -1 "$DEST_DIR"/*.jar
