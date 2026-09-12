# Ardor

![License](https://img.shields.io/github/license/GabeBrPierce/Ardor)
![Release](https://img.shields.io/github/v/release/GabeBrPierce/Ardor)

A voice-driven AI companion for Minecraft. Hold a key, say what you want, and Ardor turns it into
real game actions: pathfinding, mining, fighting, crafting, container management, all through a
push-to-talk pipeline plus a radial command wheel for anything you'd rather click than say.

Fabric mod, client-side, built for Minecraft **26.1.2**.

## Quickstart

**Requirements first:**
- Minecraft **26.1.2** with **Fabric Loader 0.18.4+** installed (run [Fabric's installer](https://fabricmc.net/use/installer/) against 26.1.2 if you haven't)
- **Java 25** (26.1.2 requires it — point your launcher's Java path at a Java 25 install)

**Then install Ardor and its dependencies in one shot:**

```powershell
irm https://raw.githubusercontent.com/GabeBrPierce/Ardor/main/installer/install.ps1 | iex
```

This downloads Ardor, the Baritone build it was tested against, Fabric API, Cloth Config, and Mod
Menu, and drops them straight into your instance's `mods` folder. It'll try to auto-detect a
CurseForge instance under `%USERPROFILE%\curseforge\minecraft\Instances`; otherwise it asks for a
path.

Prefer to inspect the script before running it? Clone the repo and run it locally instead:

```powershell
git clone https://github.com/GabeBrPierce/Ardor.git
.\Ardor\installer\install.ps1 -InstancePath "C:\path\to\your\instance"
```

Launch Minecraft, and you're in. Default keybinds:

| Key | Action |
|---|---|
| `V` (hold) | Push-to-talk — speak a command |
| Middle-click (hold) | Open the radial command wheel |
| Middle-click (tap) | Vanilla pick-block, or "Go Here" / edit a container you're looking at |

## Setting up voice control

Ardor's voice pipeline needs an LLM to turn speech into actions. `config/ardor.json` (created on
first run) controls where that LLM comes from:

```json
{
  "llmBaseUrl": "http://127.0.0.1:8081/v1",
  "llmApiKey": "",
  "llmModel": "qwen2.5-3b-instruct",
  "llmMode": "single_command"
}
```

- **Local model (default):** point `llmServerExecutable` / `llmServerModelPath` at your own
  `llama-server.exe` and GGUF model — the shipped defaults are the original dev's local paths and
  won't exist on your machine. See [`training/README.md`](training/README.md) for how that model
  was built.
- **Cloud model:** set `llmBaseUrl` to any OpenAI-compatible endpoint (e.g. Groq), set `llmApiKey`,
  and switch `llmMode` to `"say_do"`.

No LLM configured yet? The mod still loads fine — the voice pipeline just no-ops until you set one
up. The command wheel doesn't need an LLM at all.

## Features

- **Voice pipeline** — push-to-talk capture, speech-to-text (Whisper), an LLM turning speech into a
  structured action, and text-to-speech (Piper) for responses.
- **Action layer** — a real A* pathfinder plus a Baritone bridge for long-range navigation, mining,
  block breaking, container access, crafting, and combat.
- **Radial command wheel** — a click-driven UI over the same action layer, for area selection,
  entity targeting, region management, and task planning without saying a word.
- **Containers & inventory** — chest/furnace/barrel search and caching, an item-obtain planner
  backed by real loot-table/worldgen data, auto-fetch queues.
- **Combat & survival reflexes** — auto-eat, auto-flee, arrow dodge, parry, drowning safety.
- **Macros & regions** — record/replay input macros, define named regions with enter/exit hooks.
- **Xaero's World Map integration** — optional; if installed, Ardor highlights regions and hooks
  into its "Go Here" flow.
- **Mod Menu / Cloth Config settings screen**, in-game.

## Building from source

Requires JDK 25.

```powershell
.\build.ps1
```

`build.ps1` locates a JDK 25 install (checks `JAVA_HOME`, then common install locations) and runs
the Gradle wrapper. Output lands in `build\libs\ardor-<version>.jar`. Minecraft 26.1.2 ships
unobfuscated, so there's no mappings/remapping step — the build is a plain compile against the
vanilla jar plus Fabric API, Baritone (bundled in `libs/`, no resolvable Maven coordinate), Cloth
Config, and Mod Menu.

## Project layout

```
src/main/       common code (config, audio, history, LLM client, pathfinder)
src/client/     everything game-facing — this mod is client-only
schema/         the action IR's JSON schema + grammar
tools/          data-generation scripts (e.g. item_sources.json)
training/       fine-tuning pipeline for the local action-classifier model
installer/      install.ps1 — the quickstart installer
```

## Known limitations

Tracked in [`TODO.md`](TODO.md) — stubbed features, unfixed bugs, and design-only ideas live there,
removed once resolved.

## License

MIT — see [`LICENSE`](LICENSE). Bundles a build of
[Baritone](https://github.com/cabaletta/baritone) (LGPL-3.0).
