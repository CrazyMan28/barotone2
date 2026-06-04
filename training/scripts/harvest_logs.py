#!/usr/bin/env python3
"""Harvest REAL gameplay missions from Minecraft logs into training records.

Scans every Minecraft log (live + gzipped archives) for AI mission traces:

    [CHAT] [Baritone] > ai follow keven
    [CHAT] [Baritone] [AI:call] follow_player {"name":"keven"}
    [CHAT] [Baritone] [AI] done: ...

and emits one JSONL record per mission that produced at least one tool call:

    {"goal": "...", "calls": [{"name": ..., "arguments": {...}}], "source": "log:<file>"}

These are the most valuable training examples - they are what actually worked in-game.
Run any time; output is deduplicated and idempotent.
"""
import glob
import gzip
import json
import os
import re

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "harvested.jsonl")

LOG_DIRS = [
    os.path.expanduser("~/.local/share/PrismLauncher/instances/1.21.11/minecraft/logs"),
    os.path.expanduser("~/.local/share/PrismLauncher/instances/curser2/minecraft/logs"),
    os.path.expanduser("~/.minecraft/logs"),
]

GOAL_RE = re.compile(r"\[CHAT\] \[Baritone\] > (?:ai|goal) (.+)$")
START_RE = re.compile(r"\[CHAT\] \[Baritone\] \[AI\] started mission #\d+ (?:ai|goal)(?: plan)?: (.+)$")
CALL_RE = re.compile(r"\[CHAT\] \[Baritone\] \[AI:call\] (\w+) (\{.*\})\s*$")
CALL_NOARGS_RE = re.compile(r"\[CHAT\] \[Baritone\] \[AI:call\] (\w+)\s*$")
END_RE = re.compile(r"\[CHAT\] \[Baritone\] \[AI\] (done|mission summary|stopped)")

# control/meta words that are not real missions
SKIP_GOALS = {"stop", "cancel", "pause", "resume", "recover", "status", "queue", "history"}

# The BIG agent is instructed to "always begin with get_state", so 85% of real missions open
# with an info preamble regardless of the goal. Training the one-shot brain on the FIRST call
# taught it "get_state answers everything" (observed: 'mine wood' -> get_state). Skip the
# preamble and label each mission with its first ACTION call instead.
PREAMBLE_CALLS = {"get_state", "list_settings", "get_setting", "list_craftable_table_recipes",
                  "list_crafting_recipes_for_output", "memory_recall", "mission_status"}


def strip_preamble(calls):
    for i, c in enumerate(calls):
        if c["name"] not in PREAMBLE_CALLS:
            return calls[i:]
    return calls  # all-preamble missions (e.g. "what do you have") keep get_state as the label


def iter_lines(path):
    opener = gzip.open if path.endswith(".gz") else open
    try:
        with opener(path, "rt", encoding="utf-8", errors="replace") as f:
            yield from f
    except OSError:
        return


def harvest_file(path):
    missions = []
    goal = None
    calls = []
    for line in iter_lines(path):
        m = START_RE.search(line) or GOAL_RE.search(line)
        if m:
            if goal and calls:
                missions.append((goal, calls))
            goal = m.group(1).strip()
            calls = []
            continue
        m = CALL_RE.search(line)
        if m:
            try:
                args = json.loads(m.group(2))
            except json.JSONDecodeError:
                args = {}
            if m.group(1) != "done":
                calls.append({"name": m.group(1), "arguments": args})
            continue
        m = CALL_NOARGS_RE.search(line)
        if m and m.group(1) != "done":
            calls.append({"name": m.group(1), "arguments": {}})
            continue
        if END_RE.search(line) and goal and calls:
            missions.append((goal, calls))
            goal, calls = None, []
    if goal and calls:
        missions.append((goal, calls))
    return missions


def main():
    seen = set()
    records = []
    # keep previously harvested records (idempotent re-runs)
    if os.path.exists(OUT):
        for line in open(OUT, encoding="utf-8"):
            try:
                r = json.loads(line)
                key = json.dumps([r["goal"], r["calls"]], sort_keys=True)
                if key not in seen:
                    seen.add(key)
                    records.append(r)
            except (json.JSONDecodeError, KeyError):
                pass

    scanned = 0
    for d in LOG_DIRS:
        for path in sorted(glob.glob(os.path.join(d, "*.log"))) + sorted(glob.glob(os.path.join(d, "*.log.gz"))):
            scanned += 1
            for goal, calls in harvest_file(path):
                if goal.lower() in SKIP_GOALS or not calls:
                    continue
                calls = strip_preamble(calls)
                rec = {"goal": goal, "calls": calls[:6], "source": "log:" + os.path.basename(path)}
                key = json.dumps([rec["goal"], rec["calls"]], sort_keys=True)
                if key not in seen:
                    seen.add(key)
                    records.append(rec)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"scanned {scanned} log files -> {len(records)} unique real missions -> {OUT}")


if __name__ == "__main__":
    main()
