# Revenge of the Dragon — MVP Design

## Intent

`Revenge of the Dragon` is a Python-SDK fantasy door in the lineage of
classic BBS games like `LORD` and `LoGD`, but not a code or content copy.
It borrows the durable structure:

- daily cadence
- short, addictive sessions
- shared world state
- public prestige boards
- rumor-driven social pressure
- simple mechanics wrapped in flavorful text

The MVP explicitly **excludes PvP**. Multiplayer value comes from shared
rumors, prestige boards, dragon-cycle history, and public world state.

## MVP Pillars

1. Daily turns matter more than session length.
2. Shared state matters more than solo narrative.
3. Mechanics are authoritative; the LLM adds prose.
4. Menus are the primary interaction model.
5. The game should stress the door platform’s multi-session and shared KV
   behavior without needing new protocol features.

## MVP Systems

### Character

- handle
- class: `Knight`, `Rogue`, `Mystic`, `Hunter`
- level / XP
- HP / max HP
- attack / defense / charm / cunning / luck
- gold / gems
- weapon / armor
- potions
- curios / relic satchel
- daily turns
- daily forest fights
- dragon favor
- lifetime victories
- last daily reset key

### Daily cadence

- turns reset once per day
- forest fights reset once per day
- healer and crier refresh implicitly with the new day
- the BBS `time.tick` message is used for countdowns and day-boundary
  consistency; no per-message timestamps are needed

### World layout

The game should feel like roads and approaches, not a house full of menus.
The root screen is always the player’s current place in the village-road
network.

MVP world graph:

- Market Square
- Crier's Dais
- Lantern Inn Yard
- Healer's Lane
- Armorer's Row
- Hall Steps
- North Road
- Forest Gate
- Old Wood Edge
- Grave Cut
- Bandit Road
- Ruined Watch
- Shrine Path
- Dragon Shrine
- Ashen Pass (stage-gated)
- Ember Peak (stage-gated)

Each place should expose:

- prose about where the player stands
- local discoveries / fixtures
- connected routes
- actions that make sense there

Examples:

- the inn yard contains the rumor board
- the healer exists down Healer's Lane
- the hall is reached by climbing the Hall Steps
- the forest starts beyond the Forest Gate
- the shrine sits above the village at the end of Shrine Path

### Combat

Turn-based and deterministic:

- Attack
- Defend
- Use potion
- Flee

The engine owns:

- hit calculation
- damage
- rewards
- defeat penalties

The LLM may narrate:

- encounter setup
- round flavor
- victory / defeat epilogues

### Curios and relics

The LLM may occasionally propose a fantasy-appropriate find after a wild
event or a victorious hunt, but it does not get to mint arbitrary state.

The flow is:

1. the oracle proposes a validated curio template in structured JSON
2. the engine accepts only known templates
3. the player gains a real curio in the relic satchel
4. that curio can later be:
   - traded at the inn for gold
   - offered at the shrine for dragon favor

This keeps mechanics authoritative while still letting the oracle surprise
the player with specific, atmospheric finds.

### Shared multiplayer systems

No PvP in MVP, but the game should still feel shared:

- global rumor feed
- public player summaries
- hall of fame rankings
- dragon-slayer history
- town crier daily summaries

### Dragon meta

The board has one shared dragon state:

- dragon name / title
- current threat mood
- current threat stage
- rising pressure within the current stage
- weather text visible across the world
- omen count
- last slayer
- slayer day
- total slays

The current implementation pushes this further than the original MVP note:

- wild action raises shared dragon pressure
- shrine offerings can calm the next rise without erasing history
- omen-seeking advances the board's sense of the dragon
- stage shifts publish visible shared consequences through weather and rumor

Full dragon raid/boss systems can grow later, but the board should already
feel like it is living inside a rising cycle.

## LLM use

Allowed / useful:

- town crier daily summary from real rumor entries
- dragon omen text
- encounter introductions
- healer / shrine / innkeeper dialogue
- victory or defeat flavor
- curio proposals using approved templates

Not allowed:

- combat math
- economy values
- authoritative rewards
- daily reset accounting
- ranking logic

The pattern is always:

1. engine resolves mechanics
2. engine builds context
3. LLM generates concise prose
4. fallback text exists when the oracle is offline

Whenever the oracle is doing visible work, the game should also paint a
short in-door wait/spinner state so the player never mistakes LLM latency
for a frozen door.

## Storage model

### User scope

- `player`

### Shared scope

- `public-player:<handle>`
- `rumor-feed`
- `dragon-state`
- `town-crier:<day>`

The game writes public summaries into shared scope so multiple players can
see rankings and rumors without needing privileged cross-user reads.

## MVP loop

1. Log in.
2. Load/reset daily state.
3. Read the town crier or rumor board.
4. Move through roads, yards, gates, and paths.
5. Take branch contracts, court village favors, and decide how hard to press the frontier.
6. Fight, heal, buy gear, and grow stronger.
7. Leave public consequences in the rumor feed, crier, chronicle, and hall.

## Current implementation notes

The current build also includes a light class-identity layer:

- Knight: stronger defense stance
- Rogue: better escapes and extra coin from brigands
- Mystic: stronger shrine/event affinity
- Hunter: cleaner first strikes and better curio luck

And the frontier branches now have distinct tones:

- Grave Cut: omen, grave, witch pressure
- Bandit Road: thieves, coin, roadside violence
- Ruined Watch: ash, ruin, dragon-sign traces

The current build also now includes:

- recurring named village figures with once-per-day favors
  - Pellar Reed in Market Square
  - Edda Lantern at the inn
  - Mother Sable in Healer's Lane
  - Brannock Vale on Armorer's Row
  - Old Tern on the crier's platform
  - Gate-Mother Hesh at the Forest Gate
  - Sister Ione at the shrine
- a daily branch contract system tied to Grave Cut, Bandit Road, and the Ruined Watch
- dragon-slayer history as a live board-facing system
- a stage-gated dragon capstone path through Ashen Pass to Ember Peak
- extra nightly stickiness through contracts, NPC favors, crier/chronicle state, and dragon prestige
- extra authored world verbs with local outcomes, including bell-ringing, altar-touching, watch-climbing, and branch searching
- a multi-step ash-road climb so the dragon approach has to be earned before the final confrontation
- branch-specific special scenes so active contracts can occasionally pull the player into a more targeted piece of trouble rather than a generic forest roll
- named branch elites such as the Grave Bailiff, Toll Reeve, and Cinder Knight to make frontier roads feel more like territories with recurring threats

## Stretch after MVP

- PvP
- deeper multi-step branch quest chains
- dragon raids beyond the single-slayer capstone
- rare story dungeons
- social bonds / romance / tavern drama
- richer class skills
