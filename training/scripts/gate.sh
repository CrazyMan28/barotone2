#!/usr/bin/env bash
# The anti-poison gate: every new brain candidate must pass this before it can be crowned.
# Usage: scripts/gate.sh <model-name>          (model must already be created on the NEW engine)
#
# Stages (fails fast):
#  1. unload everything            - stale loaded runners have masked real results before
#  2. killer-phrase sanity         - catches corrupt packages (token salad) in seconds
#  3. Intent Bench, chat path      - 74 never-trained questions: tool + ARGS + judgment
#  4. Intent Bench, game path      - the exact rendering the Minecraft mod uses
#  5. legacy holdout exam          - regression check vs training-cousin questions
# Then prints the score card next to the reigning champion's numbers.
set -euo pipefail
cd "$(dirname "$0")/.."
MODEL="${1:?usage: gate.sh <model-name>}"
ENGINE="${EVAL_OLLAMA:-http://127.0.0.1:11435}"
OLLAMA_BIN="$HOME/.local/ollama-new/bin/ollama"
# Reigning champion baselines (v1, measured June 3 2026) - update when a new champion is crowned.
CHAMP_CHAT=83.8; CHAMP_BARE=83.8; CHAMP_LEGACY=96.9  # v5s, crowned June 3 2026

echo "=== [1/5] unloading all models on $ENGINE ==="
OLLAMA_HOST="${ENGINE#http://}" "$OLLAMA_BIN" ps 2>/dev/null | tail -n +2 | awk '{print $1}' \
  | xargs -r -I{} env OLLAMA_HOST="${ENGINE#http://}" "$OLLAMA_BIN" stop {} 2>/dev/null || true

echo "=== [2/5] killer-phrase sanity (corruption check) ==="
SYS="You are baritone-brain, the command brain of a Minecraft Baritone bot. Convert the player's message into exactly one tool call. If the request is creative, multi-step, or beyond your tools, call escalate."
for q in "get soom wood" "follow keven" "mine diamonds"; do
  resp=$(curl -s --max-time 180 "$ENGINE/api/chat" -d "$(python3 -c "
import json
print(json.dumps({'model':'$MODEL','stream':False,'think':False,
 'options':{'temperature':0,'num_ctx':4096,'num_predict':100},
 'messages':[{'role':'system','content':'''$SYS'''},{'role':'user','content':'''$q'''}]}))")" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['message']['content'])")
  if ! echo "$resp" | grep -q '"name"'; then
    echo "POISONED: '$q' produced no parseable tool call:"; echo "$resp" | head -2
    echo "VERDICT: NO-SHIP (corrupt package - re-merge with scripts in train.py / peft)"
    exit 1
  fi
  echo "  ok: $q"
done

echo "=== [3/5] Intent Bench (chat path) ==="
EVAL_OLLAMA="$ENGINE" python3 scripts/bench.py "$MODEL" | tail -14
echo "=== [4/5] Intent Bench (game path) ==="
EVAL_OLLAMA="$ENGINE" python3 scripts/bench.py "$MODEL" --ep bare | tail -14
echo "=== [5/5] legacy holdout exam ==="
EVAL_OLLAMA="$ENGINE" python3 scripts/eval_model.py "$MODEL" | tail -4

echo ""
echo "================ GATE SUMMARY ================"
echo "champion baselines: chat ${CHAMP_CHAT}%  game ${CHAMP_BARE}%  legacy ${CHAMP_LEGACY}%"
echo "SHIP only if the candidate beats the champion on the game path AND does not"
echo "regress the legacy exam by more than ~2 points. Ties go to the champion."
