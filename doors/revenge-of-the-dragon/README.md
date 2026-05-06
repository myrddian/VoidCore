# Revenge of the Dragon

Fantasy daily door built on the Python SDK for `voidcore-door-v1`.

This is meant to stress the shared-state, multi-session, menu-driven side of
the door system in the spirit of classic BBS games.

## Design

See [DESIGN.md](./DESIGN.md).

## Run

Recommended local setup:

```bash
cd doors/revenge-of-the-dragon
python3 -m venv .venv
source .venv/bin/activate
pip install -e ../sdk-python
pip install -e .
python -m revenge_of_the_dragon.main
```

Optional:

```bash
BBS_DOOR_URL=ws://127.0.0.1:8081/ws/door python -m revenge_of_the_dragon.main
```

## Current MVP Surface

- class selection
- daily turn reset
- location-based world graph rooted in roads, lanes, gates, and shrine paths
- named frontier branches including Grave Cut, Bandit Road, and the Ruined Watch
- shared dragon cycle with visible stage/weather progression across the board
- a stage-gated dragon road through Ashen Pass to Ember Peak for endgame slays
- light class identity/perk differences in combat and exploration
- forest encounters
- turn-based combat
- recurring named village figures with once-per-day favors
- a daily branch contract board for localized frontier loops
- branch-specific special scenes when active contracts pull you into more targeted trouble
- named elite frontier threats like the Grave Bailiff, Toll Reeve, and Cinder Knight
- dragon chronicle / slayer history as a public prestige surface
- extra place verbs with authored outcomes like `ring bell`, `search road`, `climb watch`, and `touch altar`
- a multi-step Ashen Pass climb before Ember Peak opens
- relic satchel with oracle-suggested curios validated into real engine items
- inn trading and shrine offerings for curios
- healer
- armory
- rumor board
- hall of fame
- town crier
- dragon shrine
- visible oracle wait states with fallback text when the LLM is offline
