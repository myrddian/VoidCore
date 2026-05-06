# Cityline MUD

Tiny Node-based proof-of-concept door for VOIDcore.

It connects to the BBS-side door endpoint at `/ws/door`, registers as
`cityline-mud`, and exposes a small shared cyberpunk chat space.

The sample now uses the reusable Node door SDK in
[`doors/sdk-node`](../sdk-node/README.md)
instead of speaking the wire format directly.

This pass adds a more deliberate terminal art direction too:

- route-style shard headers
- per-room ANSI palettes
- compact player status bands
- separate local traffic vs rumor wire sections
- recurring room NPCs and stronger scene framing
- named heroes, villains, and faction operators with plot-aware dialogue seeds
- room-specific vendors, inventory, and consumable/prep items
- job salvage and preparation effects that feed into the next run
- optional live shard scans via the BBS LLM gateway

## Run

```bash
cd doors/cityline-mud
npm install
npm start
```

Optional:

```bash
BBS_DOOR_URL=ws://127.0.0.1:8081/ws/door npm start
```

Room restore is persisted through the BBS KV store, so reconnecting
lands a player back where they last left off.

## Commands

- `look`
- `rooms`
- `scan`
- `go <room>`
- `talk <name>`
- `inv`
- `chrome`
- `wares`
- `buy <item>`
- `use <item>`
- `install <augment>`
- `jobs`
- `take <job>`
- `run`
- `status`
- `threads`
- `rumors`
- `say <msg>`
- `who`
- `jackout`

## Current Mechanics

- persistent player profile stored through the BBS KV bridge
- room-local vendors and carried inventory
- consumables for healing, stress relief, and heat cleanup
- prep items that boost the next operation
- clinic-installed cyberware with permanent stat and survivability changes
- room-specific jobs with salvage drops
- faction-specific aftermath that shifts debt, standing, and Bureau attention
- per-NPC dialogue log persisted across sessions and fed back into the LLM prompt so NPCs stay coherent within a conversation and remember prior nights
- recurring NPC memory summaries layered on top of dialogue history for cross-session texture
- structured-intent talk schema (`narration` / `choices` / `effects`) with engine-side adjudication: the model proposes ops (update_npc_memory, trigger_rumor, set_lead, update_heat/rep/stress/debt, skill_check), the wrapper validates, clamps, and commits
- in-scene branching choices: NPCs can offer numbered picks (`[1] / [2] / [3]`); typing the number, id, or label resolves to that choice and the LLM is told the player picked it on the next turn
- engine-resolved skill checks (edge/ghost/wire/body vs DC 1-5) with success/failure narration baked into the same turn
- NPC-to-NPC relationships, private secrets, and voice samples surfaced into prompts so NPCs can reference each other by name without inventing the cast
- plot threads (Blue Hour, Ghost Ledger, Marrow Debt, Choir Channel) with per-stage faction pressure that tilts player rep when threads advance
- shared plot-state progression persisted through the BBS KV layer
- per-room mood rotation so re-entering a location doesn't read identically
- optional LLM-backed `talk`, `run`, and `scan` flows when the BBS gateway is configured
