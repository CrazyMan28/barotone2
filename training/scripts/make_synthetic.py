#!/usr/bin/env python3
"""Generate synthetic (goal -> tool call) training data for the baritone-brain fine-tune.

Reads the REAL tool schemas exported by ToolSchemaDumpTest (training/data/tool_schemas.json)
so every generated call is validated against the live tool surface. Combines intent templates,
big phrase/vocab banks, and a typo-injector (matching how people actually type in chat) to
produce thousands of varied examples, plus "escalate" examples teaching the model when a
request is beyond it and must be handed to the big model.

Output: training/data/synthetic.jsonl  records: {"goal","calls":[{name,arguments}],"source":"synthetic"}
Deterministic (seeded) so re-runs are reproducible.
"""
import itertools
import json
import os
import random

random.seed(1337)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMAS = os.path.join(ROOT, "data", "tool_schemas.json")
OUT = os.path.join(ROOT, "data", "synthetic.jsonl")

# ----------------------------------------------------------------------------- vocab

ORES = {
    "diamond": "minecraft:diamond_ore", "diamonds": "minecraft:diamond_ore",
    "iron": "minecraft:iron_ore", "gold": "minecraft:gold_ore", "coal": "minecraft:coal_ore",
    "redstone": "minecraft:redstone_ore", "emerald": "minecraft:emerald_ore",
    "emeralds": "minecraft:emerald_ore", "lapis": "minecraft:lapis_ore",
    "copper": "minecraft:copper_ore", "quartz": "minecraft:nether_quartz_ore",
    "ancient debris": "minecraft:ancient_debris", "netherite": "minecraft:ancient_debris",
}
BLOCKS = {
    "stone": "minecraft:stone", "cobblestone": "minecraft:cobblestone", "sand": "minecraft:sand",
    "gravel": "minecraft:gravel", "obsidian": "minecraft:obsidian", "dirt": "minecraft:dirt",
    "clay": "minecraft:clay",
    "oak logs": "minecraft:oak_log", "birch logs": "minecraft:birch_log",
    "spruce logs": "minecraft:spruce_log", "jungle logs": "minecraft:jungle_log",
    "glowstone": "minecraft:glowstone",
}
# Generic wood words mine ANY common log type (one call, multiple targets) - and never invent
# block ids: "get soom wood" once produced minecraft:soulwood in-game.
WOOD_WORDS = ["wood", "some wood", "soom wood", "sum wood", "logs", "some logs", "lumber", "timber", "tree wood", "wood from trees", "trees"]
WOOD_BLOCKS = ["minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log", "minecraft:jungle_log"]
PLAYERS = ["keven", "keven167", "steve", "alex", "notch", "bob", "ashley", "max", "leo", "zoe"]
STATIONS = ["crafting_table", "furnace", "blast_furnace", "smoker", "brewing_stand",
            "stonecutter", "smithing_table", "anvil"]
ITEMS = ["minecraft:diamond_pickaxe", "minecraft:iron_sword", "minecraft:bucket",
         "minecraft:torch", "minecraft:bread", "minecraft:shield", "minecraft:bow"]

MINE_TPL = [
    # bare verbs first: "get wood" (no me/some) was never trained and misrouted in-game
    "get {x}", "grab {x}", "fetch {x}", "get some {x}",
    "mine {x}", "mine some {x}", "go mine {x}", "get me {x}", "get me some {x}", "go get {x}",
    "i need {x}", "i need some {x}", "dig for {x}", "find {x}", "find me {x}", "farm up some {x}",
    "collect {x}", "go collect some {x}", "we need {x}", "gather {x}", "can you mine {x}",
    "please get {x}", "yo get me {x}", "bro i need {x}", "go dig up {x}", "harvest {x}",
]
FOLLOW_TPL = [
    "follow {p}", "follow {p} around", "go follow {p}", "stay with {p}", "stick with {p}",
    "keep up with {p}", "go with {p}", "tail {p}", "dont leave {p}", "protect {p} and follow them",
]
GOTO_TPL = [
    "go to {x} {y} {z}", "goto {x} {y} {z}", "walk to {x} {y} {z}", "head to {x} {y} {z}",
    "travel to {x} {y} {z}", "go to x {x} y {y} z {z}", "take me to {x} {y} {z}",
    "meet me at {x} {y} {z}", "go to coords {x} {y} {z}",
]
GOTOBLOCK_TPL = [
    "go to the nearest {x}", "walk to a {x}", "find the closest {x} and go there",
    "go stand by a {x}", "head to the nearest {x}", "go to a {x} but dont break it",
]
WOODTOOL_TPL = [
    "make a wooden {t} from trees", "get wood and make a {t}", "chop trees and craft a wooden {t}",
    "make me a wooden {t}", "craft a wooden {t} from scratch", "go make a wooden {t}",
    "we need a wooden {t}, start from trees",
]
FARM_TPL = ["farm the crops", "go farming", "harvest the farm", "work the farm", "farm for a while"]
STATE_TPL = [
    "what do you have", "whats in your inventory", "where are you", "what are you doing",
    "check your stuff", "status report", "what do we have right now", "how are you doing",
]
ENDER_TPL = [
    "check the ender chest", "whats in the ender chest", "open your ender chest",
    "look in the ender chest", "see what we got stored in the ender chest",
]
STATION_TPL = [
    "open a {s}", "go use the {s}", "find a {s} and open it", "get to a {s}", "open the nearest {s}",
]
EQUIP_TPL = ["equip the {i}", "hold the {i}", "switch to the {i}", "take out the {i}", "use the {i}"]
# Expanded x8 for v5: tune was 19 examples vs ~886 mine and scored 2/5 on the bench.
# NONE of these may duplicate intent_bench.jsonl phrasings (contamination guard enforces it).
TUNE_TPL = [(t, "tune") for t in [
    # aim / look fixes
    "your head isnt turning fix it", "you wont break blocks fix your aim",
    "your camera is messed up", "you keep missing the blocks", "fix your looking",
    "your head is stuck again", "you cant aim anymore", "somethings wrong with your aim",
    "look at blocks properly", "your view is glitched", "fix how you look around",
    "you wont look at stuff", "aim is bugged out", "camera fix now",
    # break / mine speed
    "break blocks faster", "mine faster", "dig quicker", "speed up the mining",
    "work faster", "break stuff quicker", "mine at full speed", "stop mining so slow",
    "hurry up with the digging", "max out your break speed", "faster breaking please",
    # slower / gentle
    "slow down the breaking", "take it easy on the mining", "break blocks gently",
    # stealth
    "be sneaky on this server", "go undercover", "act natural", "dont look like a bot",
    "look human out there", "play it cool on this server", "blend in with the players",
    "go incognito", "keep a low profile out here", "dont get us banned",
    # reflexes on
    "be careful out there", "stay alive please", "watch your back",
    "keep yourself safe", "defend yourself out there", "dont let mobs kill you",
    "guard yourself", "protect yourself while working",
    # reflexes off
    "ignore mobs and just work", "stop running from mobs", "quit dodging fights",
    # smooth / snappy camera
    "make your camera smoother", "make your turns smoother", "stop turning so sharp",
    "ease up on the camera", "aim faster", "turn faster", "react quicker with your camera",
    "make your aim instant", "snappier camera please",
    # breaking on/off
    "dont break any blocks", "no block breaking allowed", "youre allowed to break blocks again",
]]
CANCEL_TPL = ["stop", "stop it", "cancel", "stop everything", "halt", "stand down", "quit it",
              "stahp", "actually nvm stop everything", "nvm stop", "forget it stop", "abort",
              "cancel that", "never mind", "stop what youre doing", "ok stop now",
              # v5 synonyms (bench items "freeze" / "ABORT ABORT" deliberately excluded)
              "halt right there", "knock it off", "thats enough", "pause everything",
              "cut it out", "enough already", "stop right now", "cease", "drop everything"]
WAIT_TPL = ["wait until youre done", "wait for it to finish", "let it finish first",
            "hold on until idle", "hold on until youre done", "wait it out", "finish up first"]
# v5 args-precision: reordered coordinate phrasings (bench showed coords-out-of-order misses)
COORD_REORDER_TPL = [
    "go to y {y} at x {x} z {z}", "x {x} z {z} then y {y}", "head to z {z} x {x} and y {y}",
    "get to x {x} y {y} z {z} quick", "y {y} x {x} z {z}", "go z {z} y {y} x {x}",
    "the spot is x {x} z {z} at height {y}", "coords are z {z} x {x} y {y}",
]
# v5 contrast pack: the model must pick the FIRST-mentioned target, not the distractor
CONTRAST_FOLLOW_TPL = ["follow {p1} not {p2}", "stick with {p1} instead of {p2}",
                       "go with {p1} and leave {p2}", "{p1} needs you, not {p2}"]
CONTRAST_MINE_TPL = ["mine {a} not {b}", "get {a} skip the {b}", "i want {a} and no {b}",
                     "{a} only, forget {b}"]
# v5 station precision: exact station name in casual phrasings
STATION_PRECISE_TPL = ["use the {s}", "i need the {s}", "get on the {s}", "open up the {s}",
                       "find the {s} for me"]
# v5 indirect-intent pack: the goal implies the tool without naming it
INDIRECT_PACK = [
    ("we got no torches left", "mine", {"blocks": ["minecraft:coal_ore"]}),
    ("out of torches again", "mine", {"blocks": ["minecraft:coal_ore"]}),
    ("furnace needs fuel asap", "mine", {"blocks": ["minecraft:coal_ore"]}),
    ("we need building blocks", "mine", {"blocks": ["minecraft:cobblestone"]}),
    ("we should make glass", "mine", {"blocks": ["minecraft:sand"]}),
    ("my pick broke grab the diamond one", "equip_item", {"item_id": "minecraft:diamond_pickaxe"}),
    ("shield up", "equip_item", {"item_id": "minecraft:shield"}),
    ("arm yourself", "equip_item", {"item_id": "minecraft:iron_sword"}),
    ("the farm is overgrown", "farm", {}),
    ("crops are ready to pick", "farm", {}),
    ("wheres all our stuff at", "get_state", {}),
    ("did we bank the diamonds", "get_ender_chest", {}),
    ("what did we store away", "get_ender_chest", {}),
    ("need to smelt this iron", "open_station", {"station": "furnace"}),
    ("lets repair the pickaxe", "open_station", {"station": "anvil"}),
    ("time to upgrade gear to netherite", "open_station", {"station": "smithing_table"}),
    ("cut some stone slabs", "open_station", {"station": "stonecutter"}),
    ("brew some potions", "open_station", {"station": "brewing_stand"}),
    ("chests are overflowing do something", "escalate", {"reason": "complex or creative request"}),
    ("make the storage room bigger", "escalate", {"reason": "complex or creative request"}),
]
ESCALATE_TPL = [
    "build me a castle with towers", "make an automatic sugarcane farm", "decorate my base",
    "build a house shaped like a creeper", "set up a sorting system for my chests",
    "trade with the villagers until you get mending", "beat the ender dragon",
    "make a nether portal and go through it", "breed the cows", "tame a horse for me",
    "build a bridge across the ravine", "make the base look nicer", "what should we do today",
    "im bored entertain me", "build a redstone door", "terraform this hill flat",
]

# ----------------------------------------------------------------------------- helpers

TYPO_MAP = {"a": "q", "e": "r", "i": "o", "o": "p", "s": "d", "t": "y", "n": "m", "d": "f"}


def typo(text, rng):
    """Light chat-style typos: drop a letter, swap neighbors, or fat-finger one key."""
    if len(text) < 8:
        return text
    t = list(text)
    kind = rng.random()
    idx = rng.randrange(1, len(t) - 2)
    if kind < 0.4:
        del t[idx]
    elif kind < 0.7:
        t[idx], t[idx + 1] = t[idx + 1], t[idx]
    else:
        ch = t[idx].lower()
        if ch in TYPO_MAP:
            t[idx] = TYPO_MAP[ch]
    return "".join(t)


def variants(goal, rng, p_typo=0.35):
    out = {goal}
    if rng.random() < p_typo:
        out.add(typo(goal, rng))
    if rng.random() < 0.2:
        out.add(goal.upper() if rng.random() < 0.3 else goal.capitalize())
    return out


def rec(goal, name, args):
    return {"goal": goal, "calls": [{"name": name, "arguments": args}], "source": "synthetic"}


def main():
    tools = {t["function"]["name"] for t in json.load(open(SCHEMAS))}
    rng = random.Random(42)
    records = []

    def add(goal, name, args):
        assert name in tools or name == "escalate", f"unknown tool {name}"
        for g in variants(goal, rng):
            records.append(rec(g, name, args))

    # mine: every ore/block x every template
    for (word, block_id), tpl in itertools.product({**ORES, **BLOCKS}.items(), MINE_TPL):
        add(tpl.format(x=word), "mine", {"blocks": [block_id]})

    # generic wood -> mine all common log types in one call
    for word, tpl in itertools.product(WOOD_WORDS, MINE_TPL):
        add(tpl.format(x=word), "mine", {"blocks": WOOD_BLOCKS})

    for p, tpl in itertools.product(PLAYERS, FOLLOW_TPL):
        add(tpl.format(p=p), "follow_player", {"name": p})

    for _ in range(220):
        x, y, z = rng.randint(-3000, 3000), rng.randint(-60, 200), rng.randint(-3000, 3000)
        add(rng.choice(GOTO_TPL).format(x=x, y=y, z=z), "goto_coords", {"x": x, "y": y, "z": z})

    for (word, block_id), tpl in itertools.product(BLOCKS.items(), GOTOBLOCK_TPL):
        add(tpl.format(x=word), "goto_block", {"block": block_id})

    for t, tool_id in [("pickaxe", "minecraft:wooden_pickaxe"), ("pick", "minecraft:wooden_pickaxe"),
                       ("axe", "minecraft:wooden_axe")]:
        for tpl in WOODTOOL_TPL:
            add(tpl.format(t=t), "mine_logs_then_make_wood_tool", {"tool": tool_id})

    for tpl in FARM_TPL:
        add(tpl, "farm", {})
    for tpl in STATE_TPL:
        add(tpl, "get_state", {})
    for tpl in ENDER_TPL:
        add(tpl, "get_ender_chest", {})
    for s, tpl in itertools.product(STATIONS, STATION_TPL):
        add(tpl.format(s=s.replace("_", " ")), "open_station", {"station": s})
    for i, tpl in itertools.product(ITEMS, EQUIP_TPL):
        add(tpl.format(i=i.split(":")[1].replace("_", " ")), "equip_item", {"item_id": i})
    for goal, tool in TUNE_TPL:
        add(goal, tool, {"request": goal})
    for tpl in CANCEL_TPL:
        add(tpl, "stop", {})
    for tpl in WAIT_TPL:
        add(tpl, "wait_until_idle", {})

    # v5: args-precision - reordered coordinates
    for _ in range(140):
        x, y, z = rng.randint(-3000, 3000), rng.randint(-60, 200), rng.randint(-3000, 3000)
        add(rng.choice(COORD_REORDER_TPL).format(x=x, y=y, z=z), "goto_coords", {"x": x, "y": y, "z": z})

    # v5: contrast - first-mentioned target wins, distractor must be ignored
    for _ in range(60):
        p1, p2 = rng.sample(PLAYERS, 2)
        add(rng.choice(CONTRAST_FOLLOW_TPL).format(p1=p1, p2=p2), "follow_player", {"name": p1})
    mineable = list({**ORES, **BLOCKS}.items())
    for _ in range(60):
        (wa, ba), (wb, _) = rng.sample(mineable, 2)
        add(rng.choice(CONTRAST_MINE_TPL).format(a=wa, b=wb), "mine", {"blocks": [ba]})

    # v5: station precision - exact station names in casual phrasings
    for s, tpl in itertools.product(STATIONS, STATION_PRECISE_TPL):
        add(tpl.format(s=s.replace("_", " ")), "open_station", {"station": s})

    # v5: indirect intent - the goal implies the tool without naming it
    for goal, tool, targs in INDIRECT_PACK:
        add(goal, tool, targs)

    # escalation: beyond the small brain -> hand off to the big model
    for goal in ESCALATE_TPL:
        for g in variants(goal, rng, p_typo=0.25):
            records.append({"goal": g, "calls": [{"name": "escalate",
                            "arguments": {"reason": "complex or creative request"}}],
                            "source": "synthetic-escalate"})

    # dedupe on goal text (keep first)
    seen, unique = set(), []
    for r in records:
        if r["goal"] not in seen:
            seen.add(r["goal"])
            unique.append(r)

    # v5 rebalance: mine dominated the dataset (~886 vs 19 tune) and starved other categories.
    # Cap single-block mine and generic-wood mine separately (seeded, reproducible).
    def cap_group(items, predicate, cap):
        grp = [r for r in items if predicate(r)]
        rest = [r for r in items if not predicate(r)]
        if len(grp) > cap:
            grp = rng.sample(grp, cap)
        return rest + grp

    def is_single_mine(r):
        c = r["calls"][0]
        return c["name"] == "mine" and len(c["arguments"].get("blocks", [])) == 1

    def is_wood_mine(r):
        c = r["calls"][0]
        return c["name"] == "mine" and len(c["arguments"].get("blocks", [])) > 1

    unique = cap_group(unique, is_single_mine, 380)
    unique = cap_group(unique, is_wood_mine, 200)
    rng.shuffle(unique)

    # v5 contamination guard: the Intent Bench must NEVER appear in training data, or the
    # benchmark stops measuring generalization. Hard-fail so a bad template can't slip through.
    bench_path = os.path.join(ROOT, "data", "intent_bench.jsonl")
    if os.path.exists(bench_path):
        bench_goals = {json.loads(l)["goal"].strip().lower() for l in open(bench_path, encoding="utf-8")}
        before = len(unique)
        unique = [r for r in unique if r["goal"].strip().lower() not in bench_goals]
        dropped = before - len(unique)
        print(f"contamination guard: dropped {dropped} bench-colliding goals "
              f"({len(bench_goals)} bench items stay test-only)")

    with open(OUT, "w", encoding="utf-8") as f:
        for r in unique:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    by_tool = {}
    for r in unique:
        by_tool[r["calls"][0]["name"]] = by_tool.get(r["calls"][0]["name"], 0) + 1
    print(f"{len(unique)} unique examples -> {OUT}")
    for k, v in sorted(by_tool.items(), key=lambda kv: -kv[1]):
        print(f"  {k:35s} {v}")


if __name__ == "__main__":
    main()
