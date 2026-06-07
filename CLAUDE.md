# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this fork is

A fork of Baritone (the Minecraft pathfinding bot) whose value-add is a **natural-language LLM agent** living under `src/main/java/baritone/ai/`. A player types `#ai <goal>` in chat; an LLM (Mistral cloud, local Ollama, or the fine-tuned `baritone-brain`) drives the bot by calling ~58 in-game "tools" until the goal is done. Everything else is upstream Baritone. **When working here you are almost always in `baritone/ai/`** — not the pathfinding core.

Target: **Minecraft 1.21.11, Fabric, Java 21**, `mod_version=1.17.0`. Uses **Mojang (Mojmap) mappings** (`net.minecraft.world.entity.*`, `LocalPlayer`, `Level`, `MultiPlayerGameMode`, `BlockPos`, `Component`, `ChatFormatting`) — NOT yarn. (Note: the companion launcher's `kihi-mod-1.21` IS yarn — don't mix them up.)

## Build / test / deploy

```bash
# AI unit tests (JUnit 4, no Minecraft needed — fast, run these constantly while iterating)
./gradlew --offline :test --tests "baritone.ai.*"

# Build the shippable jar (offline works; runs ProGuard)
./gradlew --offline :fabric:build      # -> fabric/build/libs/baritone-standalone-fabric-1.17.0.jar

# Regenerate the exported tool schema (training/data/tool_schemas.json) after adding/renaming a tool
./gradlew --offline :test --tests "baritone.ai.ToolSchemaDumpTest"
```

The built jar is deployed to **5 locations** when shipping (all run Fabric 1.21.11):
- `~/.minecraft/mods/`
- `~/.local/share/PrismLauncher/instances/1.21.11/minecraft/mods/`
- `~/.local/share/PrismLauncher/instances/curser2/minecraft/mods/` (minimal test instance: baritone + fabric-api only)
- `~/projects/mcp/minecraft-luncher/resources/kihi-overlay/` (bundled by the companion Electron launcher)
- `~/.local/share/kihi-launcher/instances/<agent-profile-id>/mods/` (the launcher's agent profile instance)

Minecraft only loads mods at launch — fully relaunch to pick up a new jar.

## Critical gotchas (these will bite you; unit tests do NOT catch them)

- **ProGuard obfuscates field names.** Any new gson-serialized POJO needs a `-keep` rule in `scripts/proguard.pro` (mirror the existing `MissionMemory$*` / `MyChunkPos` / `AgentTelemetry$*` rules), or its JSON keys ship as `a`/`b`/`c` and break at runtime while compiling and unit-testing fine. Verify with `javap` on the class inside the built jar.
- **Text colors must be `0xFFRRGGBB`** (explicit alpha). Since the 1.21.6 GUI rewrite, `0xRRGGBB` 24-bit colors render **fully transparent** in `GuiGraphics.drawString`/HUD text.
- **`logDirect` goes to the in-game chat GUI only, never stdout.** External tools must read `AgentTelemetry`'s `[AI:EVT]` lines instead (see below).
- **Block/station placement is finicky.** Place on a FLOOR (a sturdy block's top face) with a hand-built `BlockHitResult` — do NOT read `Minecraft.hitResult` for the placement hit (it's stale within the same tick right after aiming). `AiCrafting.snappyAimForPlacement()` disables smoothLook/strict so a "stealth"/undercover profile can't make the aim miss. `walkToPlaceableSpot()` paths to a known-good open cell when the current spot is bad.

## AI subsystem architecture (`src/main/java/baritone/ai/`)

The agent loop and where to make changes:

- **`MistralAgent`** — the core loop. Builds `SYSTEM_PROMPT` + tool schemas → calls the LLM → executes the returned tool call → appends the result → repeats until the model calls `done`. Two-tier: a `baritone-brain` fast path (tiny schema-free prompt, ~1s, via `BrainProtocol`) that escalates to the full Mistral/Ollama prompt on failure. **Survival progression playbook and "explore to find resources" guidance live in `SYSTEM_PROMPT`.** Provider/model come from settings (`aiProvider` = `mistral`|`ollama`, `mistralApiKey`/`mistralModel`, `ollamaBaseUrl`/`ollamaModel`, `aiBrainShortPrompt`). 429 rate limits are survived with in-loop waits, not failed.
- **`BaritoneTools`** — the ~58 tool definitions (`toolSchemas()`), the `execute()` dispatch switch, and the private tool methods. **This is the security boundary** — the LLM can only do what a tool allows. `get_state` is the agent's situational snapshot (position, inventory, time_of_day/light/tools/food, mission memory). Adding a tool = schema entry + dispatch case + method; no ProGuard rule needed (dispatch is by name, not reflection) unless you add a gson POJO.
- **`AiCrafting`** — crafting / container / block-placement automation. Holds the **`onClient(ctx, Callable)` idiom**: the agent runs on a background thread; anything touching Minecraft world/player state must go through `onClient` to hop to the client thread. Also `visiblyLookAt`, the table/station placement helpers, and ender-chest reading.
- **`MissionMemory`** — persistent memory at `<world-save>/baritone/mission-memory.json` (per-world). `remember`/`rememberLocation`/`recall`/`recordCheckpoint`, key-based dedupe (re-remembering a key overwrites), capped at 80 memories / 120 checkpoints. The agent **auto-records** its `base` (mission start) and valuable ores it sees during `get_state` — no LLM call needed.
- **`GoalTracker`** — mission lifecycle + the side HUD; emits the `AgentTelemetry` lifecycle events.
- **`AgentTelemetry`** — emits one-line `[AI:EVT] {ts,session,kind,data}` JSON to **stdout** (captured by the companion launcher) AND appends to `<gameDir>/baritone/agent_events.jsonl`. `kind` ∈ `mission_start|plan|step_complete|tool_call|tool_result|position|mission_done|mission_fail|brain_escalate|status`. **This is a stable contract** the launcher + phone app depend on — renaming kinds/fields silently breaks them.
- **`ReflexProcess` (in `baritone/process/`) + the `baritone/ai/reflex/` engine** — every-tick survival guardian running as a high-priority temporary Baritone process so any interrupted mine/goto resumes automatically. Scored-threat design: `ReflexProcess` (thin adapter) samples the world into a pure `WorldSnapshot` → `Detectors` score threats 0-100 (lava, void, suffocation, drowning, fire, falls/MLG-bucket, creepers, skeletons, swarms, poison, hunger) → `ResponseArbiter` picks a behavior with hysteresis + low-HP flee bias + losing-fight→`RetreatAndHeal` + flee-escalation (unresolved chase → pillar up / wall off / new direction, NOT the old suppress-and-resume) → behavior FSMs emit pure `ReflexAction`s → `ReflexExecutor` (in `baritone/process/`) turns them into inputs/goals/placements. The whole decision core is Minecraft-free and unit-tested (`src/test/java/baritone/ai/reflex/`, ~71 tests); change logic there, not in the adapter. Emits `[AI:EVT]` kind `reflex` `{phase: engage|resolve|done}` + `active_threat` in `get_state`.
- **`MissionQueue`** — queued/unattended missions. **`RemoteBridgeBehavior` (in `baritone/behavior/`)** — when `aiRemoteBridge=true`, polls `<gameDir>/baritone/remote_commands.txt` every ~1s and runs each line as a chat command (how the launcher injects `ai session <id>` + `ai <goal>`). No network, no auth — pure file I/O.
- **`OpenAiChatClient`** — dependency-free HTTP client for any OpenAI-compatible chat API (serves both Mistral and Ollama). `MistralClient` is the legacy single-purpose client.

Entry chat commands are in `src/main/java/baritone/command/defaults/`: `AiCommand` (`#ai <goal>` / `stop` / `status` / `queue` / `recover` / `session <id>`), `GoalCommand` (`#goal plan`), `MistralCommand`, `OllamaCommand`, `UndercoverCommand` (stealth profile), `ReflexCommand`.

## Companion projects

- **Electron launcher** at `~/projects/mcp/minecraft-luncher` — bundles this jar, spawns one Minecraft per agent "chat", and drives missions via the remote-command bridge while parsing `[AI:EVT]` telemetry. It also has a phone app. Any change to the `[AI:EVT]` contract, the `ai session <id>` subcommand, or the bridge filenames must stay in sync with it.
- **`training/`** — the `baritone-brain` fine-tune pipeline (Unsloth QLoRA on Qwen3, served via Ollama; `train.py`, `scripts/`, `data/*.jsonl`). The fine-tuned model is the local fast-path tier. Note: env exports a `PYTHONPATH` that breaks venvs — prefix python with `env -u PYTHONPATH`.

## Undercover / stealth caveat

`#undercover on` applies a human-like movement/aim profile. Historically `strictVisibleBlockInteractions=true` + slow `smoothLook` could stop the bot from ever settling its aim on a block to break/place it. Defaults are all safe; only the persisted overrides cause it. Placement code force-resets these (`snappyAimForPlacement`). The per-instance file is `<world-save>/baritone/settings.txt`.
