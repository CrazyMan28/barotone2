#!/usr/bin/env python3
"""Score any ollama model on the SAME holdout questions train.py uses, so before/after numbers
are directly comparable.

    python3 scripts/eval_model.py qwen3:1.7b                 # short prompt (baritone-brain style)
    python3 scripts/eval_model.py qwen3:1.7b --with-schemas  # today's setup: all 56 schemas in prompt
    python3 scripts/eval_model.py baritone-brain             # after training

Prints tool-name accuracy + average seconds per command. The holdout slice is reproduced with
the same seed/order as train.py (Random(7).shuffle, first 5% / min 20) - keep them in sync.
"""
import argparse
import json
import os
import random
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_FILES = [os.path.join(ROOT, "data", "synthetic.jsonl"),
              os.path.join(ROOT, "data", "harvested.jsonl")]
EVAL_FRACTION = 0.05

SYSTEM_PROMPT = (
    "You are baritone-brain, the command brain of a Minecraft Baritone bot. "
    "Convert the player's message into exactly one tool call. "
    "If the request is creative, multi-step, or beyond your tools, call escalate."
)


def load_holdout():
    records = []
    for path in DATA_FILES:
        if not os.path.exists(path):
            continue
        for line in open(path, encoding="utf-8"):
            try:
                r = json.loads(line)
                if r.get("goal") and r.get("calls"):
                    records.append(r)
            except json.JSONDecodeError:
                pass
    random.Random(7).shuffle(records)
    n_eval = max(20, int(len(records) * EVAL_FRACTION))
    return records[:n_eval]


def ask(model, goal, schemas, timeout):
    payload = {
        "model": model,
        "stream": False,
        "think": False,
        # num_ctx matters: the ollama install defaults to 40k+ context, whose KV cache is bigger
        # than the model itself and forces 100% CPU. 4k fits the whole thing on the GPU.
        "options": {"temperature": 0, "num_ctx": 4096 if schemas is None else 16384},
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": goal},
        ],
    }
    if schemas is not None:
        payload["tools"] = schemas
    req = urllib.request.Request("http://localhost:11434/api/chat",
                                 data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read())
    msg = body.get("message", {})
    text = msg.get("content", "") or ""
    for tc in msg.get("tool_calls", []) or []:
        text += " " + json.dumps(tc.get("function", {}))
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--with-schemas", action="store_true",
                    help="include all tool schemas in the request (how the agent works today)")
    ap.add_argument("--limit", type=int, default=0, help="only quiz the first N holdout questions")
    ap.add_argument("--timeout", type=int, default=300)
    args = ap.parse_args()

    holdout = load_holdout()
    if args.limit:
        holdout = holdout[:args.limit]
    schemas = None
    if args.with_schemas:
        schemas = json.load(open(os.path.join(ROOT, "data", "tool_schemas.json")))

    correct, errors, times = 0, 0, []
    for i, rec in enumerate(holdout):
        expected = rec["calls"][0]["name"]
        t0 = time.time()
        try:
            text = ask(args.model, rec["goal"], schemas, args.timeout)
        except Exception as e:  # noqa: BLE001 - report and continue
            errors += 1
            print(f"[{i + 1}/{len(holdout)}] ERROR {type(e).__name__}: {rec['goal'][:50]!r}")
            continue
        dt = time.time() - t0
        times.append(dt)
        hit = f'"{expected}"' in text or f"'{expected}'" in text or expected in text.split("(")[0][-60:]
        correct += hit
        mark = "OK " if hit else "MISS"
        print(f"[{i + 1}/{len(holdout)}] {mark} {dt:5.1f}s  {rec['goal'][:48]!r} -> expected {expected}")

    n = len(holdout)
    print("\n================ RESULTS ================")
    print(f"model:            {args.model}  (schemas in prompt: {bool(schemas)})")
    print(f"tool accuracy:    {correct}/{n}  ({100.0 * correct / max(1, n):.1f}%)")
    if times:
        print(f"avg seconds/cmd:  {sum(times) / len(times):.1f}s   (worst {max(times):.1f}s)")
    if errors:
        print(f"errors/timeouts:  {errors}")
    return 0 if n else 1


if __name__ == "__main__":
    sys.exit(main())
