# Kihi Launcher ↔ Baritone-AI Integration & Deploy Guide

This document is the source of truth for how the **Kihi Launcher** drives this Baritone-AI mod,
and — most importantly — **exactly how to get a new mod build into the launcher and into Prism**
after you edit the mod. Read the deploy section carefully: the launcher does NOT load a jar you
drop into a session folder; it has its own bundling that overwrites it.

---

## 1. The two repos

| Thing | Path |
|---|---|
| **This mod** (Baritone-AI fork) | `/home/kihi2024/projects/mcp/curser_baroitone_2/baritone/` |
| **Kihi Launcher** (Electron app) | `/home/kihi2024/projects/mcp/minecraft-luncher/` |
| **Phone app** (Android, inside the launcher repo) | `/home/kihi2024/projects/mcp/minecraft-luncher/kihi-agent-app/` |
| **The launcher the user actually runs** (installed AppImage) | `~/.local/bin/kihi-launcher` |
| **Freshly-built AppImage** (electron-builder output) | `~/projects/mcp/minecraft-luncher/release/Kihi Launcher-0.1.0.AppImage` |

Target: Minecraft **1.21.11, Fabric, Java 21**, mod jar `baritone-standalone-fabric-1.17.0.jar`.

---

## 2. What the user wants

Type a high-level goal in the launcher (e.g. *"create a new world and get full diamond armor"*).
The launcher creates an agent "chat", spawns one Minecraft, and the in-game **hierarchical planner**
(`baritone/ai/planner/`) decomposes the goal into ordered, verified sub-goals and executes them —
climbing the tech ladder, recovering after death, replanning on failure — until the goal is *actually*
done (verified against real inventory), with progress mirrored live to the launcher and phone.

---

## 3. How the launcher connects to the mod (the contract)

There is **no network protocol** between them — it's a **file bridge + stdout telemetry**.

### 3a. Commands: launcher → mod
The launcher writes chat-command lines to the session's bridge file, which the mod polls every ~1s
(when `aiRemoteBridge=true`, set in the session's `baritone/settings.txt`):

```
<session.dir>/baritone/remote_commands.txt
```
Typical lines (see `minecraft-luncher/src/main/core/agent/AgentSessionManager.ts` ~L672):
```
ai session <session-id>      # tags telemetry with this session
ai <goal text>               # starts a mission (routes through the planner)
```
Other lines it may write: `screenshot`, `undercover on/off`, raw Baritone commands.
World/server control goes to a sibling file `<session.dir>/config/kihi_agent_commands.txt`
(`world create <name>`, `join <addr>`, `disconnect`, …).

Mod side: `RemoteBridgeBehavior` (in `baritone/behavior/`) reads the file, runs each line as a
chat command, truncates it. Entry command surface: `AiCommand` (`#ai <goal>` / stop / status /
queue / recover / session).

### 3b. Telemetry: mod → launcher
The mod emits one JSON line per event to **stdout**, prefixed `[AI:EVT] `, AND appends to
`<gameDir>/baritone/agent_events.jsonl`. Shape: `{ts, session, kind, data}`.
Source: `baritone/ai/AgentTelemetry.java`. The launcher parses stdout in
`AgentSessionManager.ingestLine()` (~L440) and switches on `kind`.

**Stable kinds** (renaming/removing these breaks the launcher + phone):
`mission_start | plan | step_complete | tool_call | tool_result | position |
mission_done | mission_fail | brain_escalate | status | reflex`
Additive planner kinds the launcher ignores safely if unknown: `replan | subgoal_fail | death`.

`plan` carries `{steps:[...]}`; `step_complete` carries `{index (1-BASED), status, total}`.
⚠️ **Index base gotcha:** the mod emits a **1-based** step index. The launcher normalizes it to
0-based when storing `plan.done`; the phone tolerates both. If you change the index base in
`GoalTracker.completeStep`, fix both consumers.

### 3c. Settings the launcher writes per session
`AgentSessionManager.writeBaritoneSettings()` writes `<session.dir>/baritone/settings.txt`:
`aiRemoteBridge true`, `aiProvider`, `mistralApiKey`, `mistralModel <launcher's chosen model>`,
ollama settings, `aiBrainShortPrompt`. NOTE: it does **not** write the planner-only settings
(`aiPlannerModel` etc.), so those use the mod defaults (planner = `mistral-large-latest`).

---

## 4. ⚠️ HOW TO DEPLOY A NEW MOD BUILD (read this every time)

After editing the mod, build once:
```bash
cd /home/kihi2024/projects/mcp/curser_baroitone_2/baritone
./gradlew --offline :fabric:build      # -> fabric/build/libs/baritone-standalone-fabric-1.17.0.jar
```
Then deploy. **There are two kinds of target and they behave differently:**

### 4a. Direct-load targets — just copy the jar (effective immediately)
These load whatever jar is sitting in their `mods/` folder. Copy and you're done (relaunch MC):
```
~/.minecraft/mods/
~/.local/share/PrismLauncher/instances/1.21.11/minecraft/mods/
~/.local/share/PrismLauncher/instances/curser2/minecraft/mods/
```

### 4b. Kihi Launcher — copying into session mods does NOTHING; you MUST repackage the AppImage
The launcher's feature-sync (`minecraft-luncher/src/main/core/features/ToggleEngine.ts` ~L162)
runs **`copyFileSync(bundledJar → session/mods/)` on EVERY launch**, overwriting the session's jar
with the copy **bundled inside the launcher**. So:

- Dropping a jar in `~/.local/share/kihi-launcher/instances/<id>/sessions/<sid>/mods/` is **futile** —
  it's overwritten next launch.
- The only jar that matters is the one bundled in the **running launcher** at
  `resources/kihi-overlay/baritone-standalone-fabric-1.17.0.jar`.
- The running launcher is a **packaged AppImage** — its bundle is read-only and baked at package time.

**Therefore the correct procedure to update the mod for the Kihi Launcher:**
```bash
JAR=/home/kihi2024/projects/mcp/curser_baroitone_2/baritone/fabric/build/libs/baritone-standalone-fabric-1.17.0.jar

# 1. update the launcher's SOURCE bundle (what gets baked into the AppImage)
cp "$JAR" ~/projects/mcp/minecraft-luncher/resources/kihi-overlay/baritone-standalone-fabric-1.17.0.jar

# 2. rebuild + repackage the AppImage
cd ~/projects/mcp/minecraft-luncher
npm run build
npx electron-builder --linux AppImage          # -> release/Kihi Launcher-0.1.0.AppImage

# 3. verify the new AppImage bundles your jar
sha1sum "$JAR"
sha1sum release/linux-unpacked/resources/kihi-overlay/baritone-standalone-fabric-1.17.0.jar   # must match

# 4. close the running launcher, then install the new AppImage over the one the user runs
pkill -9 -f kihi-launcher; sleep 3
fuser -k 8793/tcp 2>/dev/null                   # free the agent-api port
cp "release/Kihi Launcher-0.1.0.AppImage" ~/.local/bin/kihi-launcher   # ("Text file busy"? kill again / unmount /tmp/*.mount_kihi*)

# 5. relaunch
nohup setsid ~/.local/bin/kihi-launcher >/tmp/kihi-launcher.out 2>&1 </dev/null &
```
Feature/jar declaration lives in `minecraft-luncher/src/main/core/features/featureRegistry.data.ts`
(`baritone-agent` → bundled `baritone-standalone-fabric-1.17.0.jar`, mcPrefix `1.21.11`). If the jar
**filename** ever changes, update it there too.

> **Same rule applies to launcher-side TS fixes** (telemetry parsing, plan checkboxes, live video):
> they live in the AppImage, so they only reach the user after step 2–5 above. Editing `out/` or
> `src/` without repackaging does nothing for the installed AppImage.

### 4c. Phone app (Android)
Phone-side changes need a rebuilt + pushed APK (separate from the launcher):
```bash
# via the phone-installer MCP: build_and_install (zero-tap if wireless adb is on),
# else push_build_to_phone <apk_path>  (the app self-installs from its store screen)
# apk: kihi-agent-app/app/build/outputs/apk/debug/app-debug.apk
```
The phone reads telemetry from the launcher's API (port 8793), so a phone-only display fix often
still needs the launcher repackaged too if the data shape changed.

---

## 5. Quick reference — "I edited the mod, now what?"

1. `./gradlew --offline :test --tests "baritone.ai.*"` (fast unit tests)
2. If you added/renamed a tool: `./gradlew --offline :test --tests "baritone.ai.ToolSchemaDumpTest"`
3. `./gradlew --offline :fabric:build`
4. Copy jar → the 3 direct-load `mods/` folders (4a)  → **Prism works now**
5. Copy jar → `resources/kihi-overlay/` → repackage AppImage → install → restart (4b) → **Kihi works now**
6. ProGuard gotcha: any new gson POJO needs a `-keep` in `scripts/proguard.pro` or its fields ship as
   `a/b/c`. Verify with `javap -p` on the class inside the built jar.
7. Fully relaunch Minecraft — mods load only at launch.
