#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DATASET_DIR="$REPO_DIR/datasets"
DOWNLOAD_DIR="$DATASET_DIR/.downloads"
PYTHON_BIN="${PYTHON:-python3}"
FORCE="${FORCE:-0}"
BEAM_SIZES="${BEAM_SIZES:-100K}"

usage() {
  cat <<'EOF'
Usage: scripts/download-datasets.sh [all|locomo|longmemeval|beam]

Downloads benchmark datasets into ./datasets.

Environment:
  FORCE=1                  re-download existing files
  BEAM_SIZES=100K,500K,1M  BEAM tiers to download and convert

Examples:
  scripts/download-datasets.sh
  scripts/download-datasets.sh locomo
  BEAM_SIZES=100K,500K scripts/download-datasets.sh beam
EOF
}

target="${1:-all}"
case "$target" in
  all|locomo|longmemeval|beam) ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

mkdir -p "$DATASET_DIR" "$DOWNLOAD_DIR"

download() {
  local url="$1"
  local dest="$2"

  if [[ -L "$dest" ]]; then
    rm "$dest"
  fi

  mkdir -p "$(dirname "$dest")"
  if [[ "$FORCE" != "1" && -s "$dest" ]]; then
    echo "exists: $dest"
    return
  fi

  echo "download: $url"
  curl -fL --retry 3 --retry-delay 2 -o "$dest.tmp" "$url"
  mv "$dest.tmp" "$dest"
}

ensure_pyarrow() {
  if "$PYTHON_BIN" -c 'import pyarrow' >/dev/null 2>&1; then
    CONVERT_PYTHON="$PYTHON_BIN"
    return
  fi

  local venv="$REPO_DIR/.dataset-tools/venv"
  if [[ ! -x "$venv/bin/python" ]]; then
    "$PYTHON_BIN" -m venv "$venv"
  fi
  "$venv/bin/python" -m pip install --quiet --upgrade pip
  "$venv/bin/python" -m pip install --quiet pyarrow
  CONVERT_PYTHON="$venv/bin/python"
}

download_locomo() {
  download \
    "https://raw.githubusercontent.com/snap-research/locomo/main/data/locomo10.json" \
    "$DATASET_DIR/locomo10.json"
}

download_longmemeval() {
  download \
    "https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_s_cleaned.json" \
    "$DATASET_DIR/longmemeval_s_cleaned.json"
}

beam_urls() {
  case "$1" in
    100K) echo "Mohammadta/BEAM data/100K-00000-of-00001.parquet" ;;
    500K) echo "Mohammadta/BEAM data/500K-00000-of-00001.parquet" ;;
    1M) echo "Mohammadta/BEAM data/1M-00000-of-00001.parquet" ;;
    10M)
      echo "Mohammadta/BEAM-10M data/10M-00000-of-00002.parquet"
      echo "Mohammadta/BEAM-10M data/10M-00001-of-00002.parquet"
      ;;
    *) echo "Unsupported BEAM size: $1" >&2; return 2 ;;
  esac
}

download_beam() {
  ensure_pyarrow

  IFS=',' read -r -a sizes <<< "$BEAM_SIZES"
  for raw_size in "${sizes[@]}"; do
    size="$(echo "$raw_size" | xargs)"
    [[ -n "$size" ]] || continue

    while read -r repo path; do
      [[ -n "$repo" ]] || continue
      file_name="$(basename "$path")"
      parquet_path="$DOWNLOAD_DIR/beam/$file_name"
      download "https://huggingface.co/datasets/$repo/resolve/main/$path" "$parquet_path"
      "$CONVERT_PYTHON" "$SCRIPT_DIR/convert_beam_parquet.py" \
        --parquet "$parquet_path" \
        --size "$size" \
        --output-dir "$DATASET_DIR/beam"
    done < <(beam_urls "$size")
  done
}

if [[ "$target" == "all" || "$target" == "locomo" ]]; then
  download_locomo
fi

if [[ "$target" == "all" || "$target" == "longmemeval" ]]; then
  download_longmemeval
fi

if [[ "$target" == "all" || "$target" == "beam" ]]; then
  download_beam
fi

echo "datasets ready under $DATASET_DIR"
