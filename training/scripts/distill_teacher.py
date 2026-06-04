#!/usr/bin/env python3
"""Teacher distillation + all-tool coverage packs (methods #2 and #3 of the v6 data plan).

Phase A (deterministic): ~200 hand-authored SEED goals covering EVERY tool on the surface:
  - Type A (one-shot-able tools): direct goal -> call examples
  - Type B (chain tools like brewing/smithing/crafting): goal -> escalate, because calling them
    cold ends a one-shot mission with an error; the big agent owns chains
Phase B (Mistral, the teacher): each seed is paraphrased ~8 ways into casual, typo'd Minecraft
chat. Labels are INHERITED from the seed (paraphrasing preserves intent), so the teacher can
style-transfer but never mislabel.
Phase C: validate against live tool schemas, dedupe, drop bench collisions, write
  data/teacher.jsonl  (picked up by train.py via DATA_FILES)

Usage:
  python3 scripts/distill_teacher.py --pilot 3     # 3 seeds through the API as a smoke test
  python3 scripts/distill_teacher.py               # full run (resumable; caches per-seed)
"""
import argparse
import json
import os
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMAS = os.path.join(ROOT, "data", "tool_schemas.json")
BENCH = os.path.join(ROOT, "data", "intent_bench.jsonl")
OUT = os.path.join(ROOT, "data", "teacher.jsonl")
CACHE = os.path.join(ROOT, "data", ".teacher_cache.jsonl")
SETTINGS = os.path.expanduser(
    "~/.local/share/PrismLauncher/instances/1.21.11/minecraft/baritone/settings.txt")
ENDPOINT = "https://api.mistral.ai/v1/chat/completions"
VARIANTS_PER_SEED = 8
SEEDS_PER_CALL = 8

E = {"reason": "complex or creative request"}  # canonical escalate args

# ---------------------------------------------------------------- seeds: (goal, tool, args)
SEEDS = [
    # --- settings tools (direct) ---
    ("change allowBreak to false", "set_setting", {"name": "allowBreak", "value": "false"}),
    ("turn allowParkour on", "set_setting", {"name": "allowParkour", "value": "true"}),
    ("set your block reach to 5", "set_setting", {"name": "blockReachDistance", "value": "5"}),
    ("what is your allowBreak setting", "get_setting", {"name": "allowBreak"}),
    ("show me the settings you changed", "list_settings", {}),
    ("which settings have to do with sprint", "list_settings", {"filter": "sprint"}),
    ("reset allowParkour back to default", "reset_setting", {"name": "allowParkour"}),
    # --- memory tools (direct) ---
    ("remember this spot as my base", "memory_remember", {"key": "base", "value": "player base location", "include_position": True}),
    ("remember that the good cave is at the ravine", "memory_remember", {"key": "good cave", "value": "at the ravine"}),
    ("what do you remember about my base", "memory_recall", {"query": "base"}),
    ("do you remember where the cave was", "memory_recall", {"query": "cave"}),
    ("forget what i told you about the base", "memory_forget", {"key": "base"}),
    # --- mission queue (direct) ---
    ("after this go mine some iron", "mission_enqueue", {"goal": "mine iron"}),
    ("queue up getting wood next", "mission_enqueue", {"goal": "get wood"}),
    ("whats in your mission queue", "mission_status", {}),
    ("pause the mission queue", "mission_pause", {}),
    ("resume your missions", "mission_resume", {}),
    ("retry that last mission", "mission_retry", {}),
    # --- schematic / world interaction (direct) ---
    ("build the house schematic", "build_schematic", {"name": "house"}),
    ("build base.schem at 100 64 200", "build_schematic", {"name": "base", "x": 100, "y": 64, "z": 200}),
    ("right click", "right_click", {}),
    ("use the bucket on the water", "use_item_on_block", {"block_id": "minecraft:water", "item_id": "minecraft:bucket"}),
    ("use the hoe on that dirt", "use_item_on_block", {"block_id": "minecraft:dirt", "item_id": "minecraft:wooden_hoe"}),
    ("close the chest", "close_inventory_screens", {}),
    ("close whatever screen is open", "close_inventory_screens", {}),
    ("say hello everyone in chat", "say", {"message": "hello everyone"}),
    ("tell keven good luck in chat", "say", {"message": "good luck keven"}),
    ("make a wooden axe from the logs you already have", "make_wood_tool_from_logs", {"tool": "minecraft:wooden_axe"}),
    ("explore the area and map it out", "explore", {}),
    ("wait until you are done with the current job", "wait_until_idle", {}),
    # --- chain tools: route to escalate (calling them cold just errors) ---
    ("smelt all my iron ore", "escalate", E),
    ("cook this raw beef in the furnace", "escalate", E),
    ("craft me a chest", "escalate", E),
    ("craft sticks from these planks", "escalate", E),
    ("make me an iron pickaxe", "escalate", E),
    ("craft a bed", "escalate", E),
    ("brew me a speed potion", "escalate", E),
    ("combine these books on the anvil", "escalate", E),
    ("upgrade my sword to netherite", "escalate", E),
    ("cut these blocks on the stonecutter", "escalate", E),
    ("what recipes can you make at the table", "escalate", E),
    ("make me a plan for getting full iron", "escalate", E),
    # --- escalation instinct: action verbs, big jobs (the dragon class) ---
    ("go fight the ender dragon", "escalate", E),
    ("go kill the wither", "escalate", E),
    ("make me full netherite armor", "escalate", E),
    ("get me a full set of diamond gear", "escalate", E),
    ("clear out this whole cave of mobs", "escalate", E),
    ("flatten this mountain", "escalate", E),
    ("dig out a huge underground base", "escalate", E),
    ("get everything we need for the nether trip", "escalate", E),
    ("farm levels until you have 30", "escalate", E),
    ("collect one of every flower", "escalate", E),
    # --- compound context sentences (the street-test failure class) ---
    ("its getting dark get wood quick", "mine", {"blocks": ["minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log", "minecraft:jungle_log"]}),
    ("the furnace is empty go get coal", "mine", {"blocks": ["minecraft:coal_ore"]}),
    ("creepers blew up the yard get dirt to patch it", "mine", {"blocks": ["minecraft:dirt"]}),
    ("we are out of torches grab coal", "mine", {"blocks": ["minecraft:coal_ore"]}),
    ("before night comes get some stone", "mine", {"blocks": ["minecraft:stone"]}),
    ("my tools broke get iron so we can fix them", "mine", {"blocks": ["minecraft:iron_ore"]}),
    ("keven went ahead follow him", "follow_player", {"name": "keven"}),
    ("im heading to the village meet me at 200 70 -500", "goto_coords", {"x": 200, "y": 70, "z": -500}),
    ("mobs everywhere get the shield out", "equip_item", {"item_id": "minecraft:shield"}),
    ("we are mining all night be careful out there", "tune", {"request": "be careful out there"}),
    ("server has anticheat act natural", "tune", {"request": "act natural"}),
    ("the lag is bad stop for now", "stop", {}),
]


def read_key():
    for line in open(SETTINGS, encoding="utf-8"):
        if line.startswith("mistralApiKey "):
            return line.split(" ", 1)[1].strip()
    raise SystemExit("mistralApiKey not found in " + SETTINGS)


def mistral(key, prompt, retries=3):
    body = {"model": "mistral-small-latest", "temperature": 0.9, "max_tokens": 2000,
            "messages": [{"role": "user", "content": prompt}]}
    for attempt in range(retries):
        try:
            req = urllib.request.Request(ENDPOINT, data=json.dumps(body).encode(),
                                         headers={"Content-Type": "application/json",
                                                  "Authorization": "Bearer " + key})
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)["choices"][0]["message"]["content"]
        except Exception as e:  # noqa: BLE001
            if attempt == retries - 1:
                raise
            time.sleep(3 * (attempt + 1))


PARAPHRASE_PROMPT = """You rewrite Minecraft bot commands the way a real player types in chat: casual, lowercase, slang, light typos (drop/swap a letter sometimes), sometimes 'bro'/'yo', sometimes a short reason attached. Keep the MEANING identical - same target item/player/coords/tool, do not add or remove requirements.

Write {n} rewrites for EACH goal. Every line must start with the GOAL's number (the number from the list below), a pipe, then the rewrite. Do NOT number the rewrites themselves.

Example for a list containing '2. follow steve':
2| yo tail steve for me
2| stick w steve pls

Goals:
{goals}"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pilot", type=int, default=0, help="only this many seeds (smoke test)")
    args = ap.parse_args()

    tools = {t["function"]["name"] for t in json.load(open(SCHEMAS))}
    bad = [s for s in SEEDS if s[1] not in tools and s[1] != "escalate"]
    if bad:
        raise SystemExit(f"seeds reference unknown tools: {[b[1] for b in bad]}")

    key = read_key()
    seeds = SEEDS[: args.pilot] if args.pilot else SEEDS

    done_ids = set()
    if os.path.exists(CACHE) and not args.pilot:
        for line in open(CACHE, encoding="utf-8"):
            done_ids.add(json.loads(line)["seed"])

    cache = open(CACHE, "a", encoding="utf-8") if not args.pilot else None
    records = []
    batch = [s for s in seeds if s[0] not in done_ids]
    print(f"{len(seeds)} seeds, {len(batch)} to paraphrase via Mistral...")
    for i in range(0, len(batch), SEEDS_PER_CALL):
        chunk = batch[i:i + SEEDS_PER_CALL]
        goals = "\n".join(f"{j + 1}. {g}" for j, (g, _, _) in enumerate(chunk))
        text = mistral(key, PARAPHRASE_PROMPT.format(n=VARIANTS_PER_SEED, goals=goals))
        got = {}
        for line in text.splitlines():
            if "|" in line:
                num, _, rew = line.partition("|")
                num = num.strip().lstrip("0123456789. ")[:0] or num.strip()
                try:
                    idx = int("".join(ch for ch in num if ch.isdigit())) - 1
                except ValueError:
                    continue
                if 0 <= idx < len(chunk) and rew.strip():
                    got.setdefault(idx, []).append(rew.strip().strip('"'))
        for idx, (goal, tool, targs) in enumerate(chunk):
            variants = got.get(idx, [])
            entry = {"seed": goal, "tool": tool, "args": targs, "variants": variants}
            if cache:
                cache.write(json.dumps(entry, ensure_ascii=False) + "\n")
                cache.flush()
            records.append(entry)
            print(f"  [{goal[:50]!r}] +{len(variants)} variants")
        time.sleep(0.4)
    if cache:
        cache.close()
        records = [json.loads(l) for l in open(CACHE, encoding="utf-8")]

    # assemble: seed + variants, inherit the seed's label
    bench_goals = {json.loads(l)["goal"].strip().lower() for l in open(BENCH, encoding="utf-8")}
    out, seen = [], set()
    for r in records:
        for goal in [r["seed"]] + r["variants"]:
            g = goal.strip()
            if not g or g.lower() in seen or g.lower() in bench_goals or len(g) > 160:
                continue
            seen.add(g.lower())
            out.append({"goal": g, "calls": [{"name": r["tool"], "arguments": r["args"]}],
                        "source": "teacher"})
    if not args.pilot:
        with open(OUT, "w", encoding="utf-8") as f:
            for r in out:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"\n{len(out)} teacher examples -> {OUT} (bench-clean)")
    else:
        print(f"\nPILOT OK: {len(out)} examples would be written; sample:")
        for r in out[:6]:
            print("  ", r["goal"][:70], "->", r["calls"][0]["name"])


if __name__ == "__main__":
    main()
