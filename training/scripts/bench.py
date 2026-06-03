#!/usr/bin/env python3
"""Intent Bench: the championship eval. Unlike eval_model.py (tool-NAME accuracy on holdout
cousins of the training data), this scores hand-written, never-trained phrasings on FOUR axes:

  format   - did it produce one parseable tool call at all (the mod's parser needs this)
  tool     - right tool picked
  args     - right arguments (right blocks, right player, exact coords)
  FULL     - all of the above (the number that crowns the winner)

plus escalation judgment (escalates creative stuff, does NOT escalate simple stuff) and speed.

  python3 scripts/bench.py <model> [--ep chat|bare] [--limit N]
  EVAL_OLLAMA=http://localhost:11434 python3 scripts/bench.py baritone-brain   # old engine
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BENCH = os.path.join(ROOT, "data", "intent_bench.jsonl")

SYSTEM_PROMPT = (
    "You are baritone-brain, the command brain of a Minecraft Baritone bot. "
    "Convert the player's message into exactly one tool call. "
    "If the request is creative, multi-step, or beyond your tools, call escalate."
)


def ask(base, model, goal, ep, timeout):
    if ep == "chat":
        payload = {"model": model, "stream": False, "think": False,
                   "options": {"temperature": 0, "num_ctx": 4096, "num_predict": 120},
                   "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                                {"role": "user", "content": goal}]}
        url = base + "/api/chat"
    else:  # bare = the game's OpenAI-compat rendering, reproduced exactly via raw mode
        prompt = ("<|im_start|>system\n\n" + SYSTEM_PROMPT + "<|im_end|>\n"
                  "<|im_start|>user\n" + goal + "<|im_end|>\n"
                  "<|im_start|>assistant\n")
        payload = {"model": model, "stream": False, "raw": True, "prompt": prompt,
                   "options": {"temperature": 0, "num_ctx": 4096, "num_predict": 120}}
        url = base + "/api/generate"
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = json.load(r)
    msg = body.get("message", {})
    text = msg.get("content") or body.get("response") or ""
    calls = msg.get("tool_calls") or []
    return text, calls


def parse_call(text, structured):
    if structured:
        fn = structured[0].get("function", {})
        name = fn.get("name")
        args = fn.get("arguments") or {}
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except json.JSONDecodeError:
                args = {}
        if name:
            return {"name": name, "arguments": args}
    t = text
    end = t.rfind("</think>")
    if end >= 0:
        t = t[end + 8:]
    m = re.search(r"<tool_call>(.*?)(?:</tool_call>|$)", t, re.S)
    blob = m.group(1) if m else t
    b = blob.find("{")
    if b < 0:
        return None
    try:
        obj = json.loads(blob[b:blob.rfind("}") + 1])
        if isinstance(obj, dict) and obj.get("name"):
            return {"name": obj["name"], "arguments": obj.get("arguments") or {}}
    except json.JSONDecodeError:
        pass
    return None


def args_match(spec_args, got_args):
    for k, want in (spec_args or {}).items():
        got = (got_args or {}).get(k)
        if isinstance(want, list):
            if not isinstance(got, list):
                return False
            want_l = {str(w).lower() for w in want}
            got_l = {str(g).lower() for g in got}
            if not (want_l & got_l):
                return False
        elif isinstance(want, (int, float)):
            try:
                if int(got) != int(want):
                    return False
            except (TypeError, ValueError):
                return False
        else:
            if str(got or "").lower() != str(want).lower():
                return False
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--ep", choices=["chat", "bare"], default="chat",
                    help="chat = ollama /api/chat think:false; bare = the game's rendering")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--timeout", type=int, default=180)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()
    base = os.environ.get("EVAL_OLLAMA", "http://localhost:11434")

    cases = [json.loads(l) for l in open(BENCH, encoding="utf-8")]
    if args.limit:
        cases = cases[:args.limit]

    cats, times = {}, []
    fmt_ok = tool_ok = full_ok = 0
    esc_should = esc_did = 0          # escalation recall
    noesc_total = noesc_wrong = 0     # over-escalation

    for i, c in enumerate(cases):
        t0 = time.time()
        try:
            text, structured = ask(base, args.model, c["goal"], args.ep, args.timeout)
        except Exception as e:  # noqa: BLE001
            print(f"[{i + 1}/{len(cases)}] ERR  ----  {c['goal'][:44]!r} ({type(e).__name__})")
            cats.setdefault(c["category"], [0, 0])[1] += 1
            continue
        times.append(time.time() - t0)
        call = parse_call(text, structured)
        c_fmt = call is not None
        c_tool = c_full = False
        if call:
            for spec in c["accept"]:
                if call["name"].lower() == spec["name"].lower():
                    c_tool = True
                    if args_match(spec.get("args"), call.get("arguments")):
                        c_full = True
                        break
        fmt_ok += c_fmt
        tool_ok += c_tool
        full_ok += c_full
        if c["category"] == "escalate":
            esc_should += 1
            esc_did += bool(call and call["name"].lower() == "escalate")
        if c["category"] == "no-escalate":
            noesc_total += 1
            noesc_wrong += bool(call and call["name"].lower() == "escalate")
        st = cats.setdefault(c["category"], [0, 0])
        st[0] += c_full
        st[1] += 1
        mark = "FULL" if c_full else ("tool" if c_tool else ("fmt " if c_fmt else "----"))
        if args.verbose or not c_full:
            got = f"{call['name']} {json.dumps(call['arguments'])[:60]}" if call else repr(text[:60])
            print(f"[{i + 1}/{len(cases)}] {mark} {times[-1]:4.1f}s  {c['goal'][:44]!r} -> {got}")

    n = len(cases)
    print("\n================ INTENT BENCH ================")
    print(f"model: {args.model}   endpoint: {args.ep}   server: {base}")
    print(f"format valid : {fmt_ok}/{n}  ({100 * fmt_ok / n:.1f}%)")
    print(f"right tool   : {tool_ok}/{n}  ({100 * tool_ok / n:.1f}%)")
    print(f"FULL correct : {full_ok}/{n}  ({100 * full_ok / n:.1f}%)   <- championship number")
    if esc_should:
        print(f"escalates when it should   : {esc_did}/{esc_should}")
    if noesc_total:
        print(f"over-escalates simple stuff: {noesc_wrong}/{noesc_total} (lower is better)")
    if times:
        print(f"speed: avg {sum(times) / len(times):.1f}s, worst {max(times):.1f}s")
    print("per category (FULL/total):")
    for cat, (ok, tot) in sorted(cats.items()):
        print(f"  {cat:12s} {ok}/{tot}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
