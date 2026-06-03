#!/usr/bin/env bash
# Launch the baritone-brain fine-tune. Unsets PYTHONPATH because the user profile points it at
# python3.14 site-packages, which shadows the 3.12 venv and breaks torch's C extensions.
set -euo pipefail
cd "$(dirname "$0")"
echo "Refreshing datasets..."
python3 scripts/make_synthetic.py >/dev/null
python3 scripts/harvest_logs.py
echo "Starting training (expect ~1-2h on an 8GB RTX 4070 Laptop)..."
exec env -u PYTHONPATH HF_HOME="$PWD/hf_cache" .venv/bin/python train.py
