# baritone-brain — the custom command model

*What we did, why, and when. Written June 3, 2026.*

## The problem (June 3, 2026, morning)

`#ai follow keven` took **104 seconds** before the bot moved (see `logs/latest.log`, 11:50 session).
Investigation found three stacked causes:

1. **The prompt was huge.** Every agent round sent ~2k tokens of instructions + ~13k tokens of
   tool schemas (56 tools) + history. A model must read everything before it can answer; reading
   was 98% of the work.
2. **ollama had been blind to the GPU since June 1, 4:19 PM.** The systemd service scans for GPUs
   once at startup; that start happened mid NVIDIA-driver update and found nothing, so every model
   ran 100% CPU for two days. (`journalctl -u ollama` shows `inference compute ... CUDA0` on every
   start before June 1 and `id=cpu library=cpu` after.) Fix: `sudo systemctl restart ollama`.
3. **`OLLAMA_CONTEXT_LENGTH=50000`** in the service env: the KV cache for a 40–50k context window
   is bigger than the small models themselves (gemma4:e2b — actually 5.1B params, "e2b" means
   *effective* 2B — ballooned to 8.0 GB; even qwen3:1.7b hit 6.1 GB), guaranteeing CPU fallback on
   an 8 GB RTX 4070 Laptop. Per-request `num_ctx: 4096` (and the baked Modelfile parameter) fixes it.

## The fix: fine-tune a tiny model with the tools baked into its weights

Instead of sending the 13k-token tool list every round, we taught **Qwen3-1.7B** the tools
permanently via QLoRA fine-tuning (trains a ~1% adapter on top of the model; ~9 minutes on the
8 GB GPU). The prompt drops from ~15,000 tokens to ~200.

- **From-scratch training / custom tokenizer was rejected**: needs trillions of tokens and GPU
  clusters; a custom tokenizer destroys the pretrained English the whole idea depends on
  ("accompany ashley" → follow works *because* the base model already knows English).

## Training data (`training/data/`)

| File | Contents |
|---|---|
| `tool_schemas.json` | the live 56-tool surface, auto-exported by `ToolSchemaDumpTest` |
| `synthetic.jsonl` | ~1.7k generated goal→call pairs (templates × vocab × typos), incl. `escalate` examples |
| `harvested.jsonl` | ~108 real missions parsed from actual Minecraft logs (272 files scanned) |

`escalate` is a trained pseudo-tool: creative/multi-step requests ("build a cozy cottage") answer
`escalate`, signalling the mod to forward the request to the big model (Mistral) with full schemas.

## Results (June 3, 2026 — measured, same questions for every model)

| Model | Tool accuracy | Avg sec/cmd | Worst |
|---|---|---|---|
| gemma4:e2b + full schemas (old setup) | 70% (7/10) | 14.7s | **116.8s** |
| qwen3:1.7b untrained, no schemas | 16% (4/25) | 1.6s | 5.3s |
| **baritone-brain, no schemas** | **93.3% (83/89)** | **1.2s** | **3.2s** |

Adversarial spot-check on never-seen phrasings ("accompany ashley pls", "chop some lumber",
"blue lapis stuff"): brain 7/8 clean; gemma 4/8 with two dangerous failures (called `explore`
unprompted; hallucinated a nonexistent `stop` tool). All numbers were measured with ollama still
CPU-handicapped — they improve after the service restart.

## How to use

```
#ollama use baritone-brain        (in Minecraft)
```

## How to retrain (e.g. after adding a new tool)

1. Add the tool in `BaritoneTools` as usual. Until retrained, the brain escalates unknown requests
   to Mistral, which always receives the live tool list — the new tool works on the slow path.
2. `./gradlew test` (re-exports `tool_schemas.json` via `ToolSchemaDumpTest`)
3. Add a few example phrasings for the new tool in `training/scripts/make_synthetic.py`
4. `cd training && ./run_train.sh` (~9 min; refreshes datasets, trains, evals, exports)
5. GGUF: unsloth's auto-export fails on Fedora — convert manually:
   `env -u PYTHONPATH .venv/bin/python llama.cpp/convert_hf_to_gguf.py outputs/gguf --outfile outputs/baritone-brain-q8_0.gguf --outtype q8_0`
6. `ollama create baritone-brain -f outputs/Modelfile`
7. Score it: `python3 scripts/eval_model.py baritone-brain` (same holdout as training; compare runs)

More real gameplay = better data: `python3 training/scripts/harvest_logs.py` after playing.

## Gotchas (hard-won)

- `PYTHONPATH` in the user profile points at python3.14 site-packages and breaks the venv's torch —
  always `env -u PYTHONPATH` (run_train.sh does it).
- Don't run a HF download and training against the same `hf_cache` simultaneously — futex deadlock.
- `ollama create` resolves `FROM` relative to the Modelfile — use absolute paths.
- The Modelfile pins `num_ctx 4096` and borrows qwen3's chat TEMPLATE so tool calls parse.

## The router (added June 3, 2026, same day)

`MistralAgent.runBrainFastPath` + `BrainProtocol`: when the active ollama model name starts with
`baritone-brain` (and `aiBrainShortPrompt` is true), `#ai <goal>` first sends the tiny trained
prompt (no schemas) and executes the single tool call the brain returns — one-shot, ~1s. The
mission **escalates to the full-prompt path** (Mistral when `mistralApiKey` is set, else the same
ollama model with full schemas) whenever the brain answers `escalate`, the reply has no parsable
tool call, or the executed tool errors. Plan mode (`#goal plan ...`) always uses the big path.

## Why an untrained tool still "works", and why retraining is still needed

The brain can *physically emit* any tool-call JSON — access was never the issue. What training
provides is **knowledge**: that the tool exists, what its arguments look like, and which player
phrasings map to it. Untrained tools produce confident garbage (observed: gemma hallucinated a
nonexistent `stop` tool and fired `explore` unprompted). The design handles the gap gracefully:

1. Training only covered the ~15 most-used tools (+108 real missions); the other ~40 multi-step
   tools (crafting, smithing, brewing, schematics, memory...) were deliberately left to `escalate`,
   because the big model is better at multi-step work anyway.
2. A brand-new tool works **immediately** via escalate → Mistral, which always receives the live
   schema list.
3. A ~9-minute retrain moves it to the fast path whenever convenient (recipe above).

## Still to do

- `sudo systemctl restart ollama` (user) and ideally lower `OLLAMA_CONTEXT_LENGTH`.
- Optional: pass a "tools added since training" delta cheat-sheet to the brain instead of waiting
  for a retrain.

## The v2/v3 saga (June 3, evening) — why v1 is still the brain

Retrains v2 (95.8% in-process) and v3 (99.0% in-process) both failed or underperformed when served
by **ollama 0.23.0's bundled llama.cpp** (v2: 45.8%, v3: token salad), while forensics proved every
artifact good: weights perfect in transformers, conversion faithful (exactly the 196 LoRA tensors
differ), templates identical, and **current llama.cpp runs the same GGUFs flawlessly**. A user-space
ollama **v0.30.3** (`~/.local/ollama-new`, port 11435, no sudo needed) fixed v2 (86.6%) and rescued
v3 via clean peft re-merge (94.8% at q8_0 AND f16) — still short of v1's 96.9%/0.3s on the same 97
questions. The in-process→ollama gap is template-rendering drift, not quantization.

**Verdict: v1 remains `baritone-brain`** (old engine runs it fine). Lessons: always gate a new model
against the incumbent through the REAL runtime before shipping; unsloth's crashed-merge dirs and
old-engine numerics are both real failure modes; `ollama stop` everything before create+test
(serialized requests + stale runners mislead). The user-space v0.30.3 stays installed for future
retrains: `OLLAMA_HOST=127.0.0.1:11435 OLLAMA_MODELS=~/.local/ollama-new/models ~/.local/ollama-new/bin/ollama serve`.

## v4 and the Intent Bench (June 3, night)

Built `training/data/intent_bench.jsonl` + `scripts/bench.py`: 74 hand-written, never-trained
phrasings scored on format / right-tool / right-ARGUMENTS / speed, plus escalation judgment, on
both runtime renderings. v1's baseline: 77.0% FULL (chat), 74.3% (game path) — exposed stop 0/2
and slang 9/15. v4 trained on byte-exact copies of BOTH runtime renderings (captured from ollama
0.30 via prompt_eval_count probes; the mod's OpenAI-compat path renders bare, /api/chat think:false
appends " /no_think" + prefills the think block) and doubled data. Result: 78.4% chat / 74.3% game
path (exact tie with v1) / 92.2% legacy — better at slang (13/15), stop, escalation, worst-case
speed; worse at direct/args/tune. **Tie does not unseat the champion: v1 stays.** unsloth's
in-process merge corrupted v4's first package exactly like v3's (0% token salad) — train.py now
merges via vanilla peft in a subprocess, which has been correct every time. Old ollama 0.23 mangles
ALL freshly-converted GGUFs (v2/v3/v4); only the user-space 0.30.3 runs them.

v5 recipe when more gameplay data is banked: keep dual-format training (lab scores now transfer to
the field ~1:1), add tune/args/direct reinforcement to fix v4's regressions, consider 4 epochs.

## Final four-way Intent Bench medal table (June 3, chat path, identical 74 questions)

| Place | Model | FULL correct | Speed avg | Notes |
|---|---|---|---|---|
| 1 | v4 | 78.4% | 0.4s | best slang/stop/escalation; tied v1 on game path (74.3%) |
| 2 | **v1** 👑 | 77.0% | 0.6s | retains crown: game-path tie + best legacy exam (96.9%) + zero infra risk |
| 3 | v3 | 71.6% | 1.2s | weak escalation recall (4/10) |
| 4 | v2 | 67.6% | 1.2s | format only 85%; over-escalates everything (even "follow alex") |

Anti-poison gate for all future candidates: `scripts/gate.sh <model>` — unloads models, runs the
corruption sanity (fails fast on token salad), both Intent Bench paths, the legacy exam, and prints
ship rules (beat champion on game path, no >2pt legacy regression, ties go to the champion).

## v5s: UNDISPUTED CHAMPION (June 3, late night)

Recipe: rebalanced data (mine capped 380+200wood, tune 19->98, args-precision/contrast/indirect/stop
packs), completion-only loss (prompt tokens masked), 4 epochs, dual byte-exact formats, contamination
guard (which caught and fixed 9 bench items that had leaked into training - all baselines re-measured
on the cleaned bench and were UNCHANGED: v1 77.0/74.3, v4 78.4/74.3).

Gate results: **83.8% Intent Bench on BOTH paths (+9.5 over v1/v4 on the game path), legacy 96.9%
(ties v1), 0.4s avg, clean package.** All undisputed-bar conditions met. Lab predicted field exactly
(98.0% holdout both formats).

Deployed: `baritone-brain` on the user-space 0.30.3 engine; game points at it via
`ollamaBaseUrl http://127.0.0.1:11435` (settings.txt); engine runs as systemd user service
`ollama-new` (enabled, survives reboots). v1 remains on the old engine as rollback
(`#set ollamaBaseUrl http://localhost:11434` to revert). Qwen3-4B (v5b) optional: base weights
cached; train with `BRAIN_BASE=unsloth/Qwen3-4B BRAIN_TAG=-4b BRAIN_QUANT=q4_k_m BRAIN_BATCH=1 ./run_train.sh`.
