#!/usr/bin/env bash
# Wraps promote.py: activates the pneumonia-lab conda env and runs it, from any cwd.
# Usage: ./promote.sh --dataset-version "<...>" [--model-version "<...>"]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v conda >/dev/null 2>&1; then
    echo "conda not found on PATH — see lab/README.md's Setup section." >&2
    exit 1
fi

source "$(conda info --base)/etc/profile.d/conda.sh"
conda activate pneumonia-lab

python "$SCRIPT_DIR/promote.py" "$@"
