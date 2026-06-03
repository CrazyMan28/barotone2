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
