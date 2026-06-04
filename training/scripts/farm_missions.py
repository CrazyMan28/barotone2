#!/usr/bin/env python3
"""Unattended training-data farm: drives #ai missions through the remote bridge, kihi-style.

Sends one mission at a time to <instance>/baritone/remote_commands.txt, watches latest.log for
completion ([AI] done / stopped / mission summary), then sends the next after a breather.
Mix: quick brain-tier commands + big Mistral-tier requests (real multi-step trajectories).
Caps the session by mission count AND wall-clock. Every mission lands in the logs that
harvest_logs.py already reads - the farm IS the harvest.

  python3 scripts/farm_missions.py            # full session (default 24 missions / 100 min)
  python3 scripts/farm_missions.py --limit 3  # smoke test
"""
import argparse
import os
import random
import time

INSTANCE = os.path.expanduser("~/.local/share/PrismLauncher/instances/1.21.11/minecraft")
CMD_FILE = os.path.join(INSTANCE, "baritone", "remote_commands.txt")
LOG_FILE = os.path.join(INSTANCE, "logs", "latest.log")
FARM_LOG = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "farm.log")

# Missions in the user's actual typing style. Quick ones exercise the brain tier;
# big ones escalate to Mistral and produce multi-step trajectories (the gold).
QUICK = [
    "ai get wood", "ai git me sum stone", "ai mine sum coal", "ai grab dirt",
    "ai get me cobblestone", "ai mine sum iron", "ai go to 100 70 100", "ai get sand",
    "ai gather gravel", "ai mine sum logs bro", "ai get me sum oak logs", "ai go to 0 70 0",
    "ai collect sum dirt quick", "ai stone pls", "ai get cobble for the wall",
]
BIG = [
    "ai make a wooden pickaxe from trees",
    "ai mine logs and craft a crafting table",
    "ai make me a wooden axe from scratch",
    "ai get sum logs and make sticks",
    "ai craft a wooden pickaxe then mine sum stone",
    "ai make a crafting table and place it",
    "ai get 5 logs and craft planks",
    "ai mine sum coal and iron for later",
    "ai make me stone tools",
]
DONE_MARKERS = ["[AI] done", "[AI] mission summary", "Stopped:", "[AI] stopped",
                "Reached max iterations", "Reached time budget", "API error"]


def log(msg):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(FARM_LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def send(cmd):
    with open(CMD_FILE, "w", encoding="utf-8") as f:
        f.write(cmd + "\n")


def tail_pos():
    return os.path.getsize(LOG_FILE)


def wait_for(markers, since_pos, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with open(LOG_FILE, errors="replace") as f:
            f.seek(since_pos)
            chunk = f.read()
        for m in markers:
            if m in chunk:
                return m
        if "was slain" in chunk or "was shot" in chunk or "Death position saved" in chunk:
            return "DEATH"
        time.sleep(5)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=24)
    ap.add_argument("--max-minutes", type=int, default=100)
    ap.add_argument("--mission-timeout", type=int, default=360)
    args = ap.parse_args()

    rng = random.Random()
    session_end = time.time() + args.max_minutes * 60
    completed = 0
    for i in range(args.limit):
        if time.time() > session_end:
            log("session wall-clock cap reached")
            break
        big = rng.random() < 0.4
        mission = rng.choice(BIG if big else QUICK)
        pos = tail_pos()
        log(f"mission {i + 1}/{args.limit} ({'BIG' if big else 'quick'}): {mission}")
        send(mission)
        started = wait_for(["[AI] started mission", "[AI:brain]"], pos, 45)
        if not started:
            log("  mission never started (dead player / paused game?); waiting 60s and retrying once")
            time.sleep(60)
            pos = tail_pos()
            send(mission)
            if not wait_for(["[AI] started mission", "[AI:brain]"], pos, 45):
                log("  still not starting; skipping")
                continue
        result = wait_for(DONE_MARKERS, pos, args.mission_timeout)
        if result == "DEATH":
            log("  BOT DIED - waiting 90s for manual/auto respawn, then continuing")
            send("ai stop")
            time.sleep(90)
        elif result is None:
            log("  mission timed out; sending stop")
            send("ai stop")
            time.sleep(8)
        else:
            completed += 1
            log(f"  finished ({result.strip()})")
        # Quick missions report "done" at dispatch while Baritone keeps working - give the
        # world-action time to actually play out (and stockpile materials for BIG missions).
        time.sleep(rng.randint(60, 110) if not big else rng.randint(10, 25))
    log(f"FARM SESSION COMPLETE: {completed} missions finished cleanly")


if __name__ == "__main__":
    main()
