#!/usr/bin/env bash
# Local sanity checks for the GhostbRoot repo (no Android SDK required).
# Runs the Python SDK test-suite against the in-process API mock.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== File inventory =="
find . -path '*/node_modules' -prune -o -type f -print | grep -v '^./.git/' | sort | head -n 100

echo
echo "== YAML syntax (workflows) =="
python3 - <<'PY'
import glob, sys
try:
    import yaml
except ImportError:
    print("pyyaml not installed — skipping YAML parse check")
    sys.exit(0)
for f in sorted(glob.glob(".github/workflows/*.yml")):
    yaml.safe_load(open(f))
    print("YAML OK:", f)
PY

echo
echo "== Python SDK tests =="
PYTHONPATH=python-sdk/src python3 -m pytest python-sdk/tests -q

echo
echo "== Kotlin sources present =="
find app/src -name '*.kt' | sort

echo
echo "All local checks passed."
