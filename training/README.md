# baritone-brain training

Fine-tunes **Qwen3-1.7B** (QLoRA, 4-bit) into a tiny command brain that maps plain-English goals
straight to Baritone tool calls — with all 56 tool schemas baked into the weights, so the runtime
prompt is ~200 tokens instead of ~15,000 and the model fits fully on the 8GB GPU next to Minecraft.

## Layout

- `data/tool_schemas.json` — the live tool surface, exported by `ToolSchemaDumpTest` (re-runs with the test suite)
- `data/synthetic.jsonl` — generated goal→call examples (`scripts/make_synthetic.py`, seeded/reproducible)
- `data/harvested.jsonl` — REAL missions mined from Minecraft logs (`scripts/harvest_logs.py`, idempotent — re-run after playing to grow it)
- `train.py` — full pipeline: data → QLoRA → holdout eval → LoRA + GGUF export
- `run_train.sh` — the launcher (also refreshes datasets; unsets the broken `PYTHONPATH`)
- `.venv/` — Python 3.12 + torch/cu128 + unsloth (verified CUDA on the RTX 4070)

## To train (when ready — takes ~1–2 h)

```bash
cd training && ./run_train.sh
```

Then load it into ollama and point the mod at it:

```bash
ollama create baritone-brain -f outputs/gguf/Modelfile
# in Minecraft: #ollama use baritone-brain
```

## Notes

- The dataset trains an `escalate` pseudo-tool for creative/complex requests — the runtime router
  ("if brain says escalate → send to Mistral/big model") still needs to be added to `MistralAgent`
  once the model exists.
- More real gameplay = better model: play with `#ai`, then re-run `scripts/harvest_logs.py`.
- `PYTHONPATH` in the user profile points at python3.14 site-packages and breaks the venv's torch;
  anything run by hand needs `env -u PYTHONPATH`.
