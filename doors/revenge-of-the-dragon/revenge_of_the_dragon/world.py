from __future__ import annotations

from typing import Any


CLASSES: dict[str, dict[str, Any]] = {
    "knight": {
        "name": "Knight",
        "blurb": "Steel, honor, and a habit of surviving the last blow.",
        "stats": {"attack": 4, "defense": 4, "charm": 2, "cunning": 1, "luck": 2, "max_hp": 18},
    },
    "rogue": {
        "name": "Rogue",
        "blurb": "Fast hands, quicker feet, and a smile you should not trust.",
        "stats": {"attack": 3, "defense": 2, "charm": 3, "cunning": 4, "luck": 3, "max_hp": 15},
    },
    "mystic": {
        "name": "Mystic",
        "blurb": "Reads old signs, talks to shrines, and cheats death with style.",
        "stats": {"attack": 2, "defense": 2, "charm": 3, "cunning": 3, "luck": 4, "max_hp": 14},
    },
    "hunter": {
        "name": "Hunter",
        "blurb": "Lives by instinct, patience, and the clean line of a kill.",
        "stats": {"attack": 4, "defense": 3, "charm": 2, "cunning": 3, "luck": 2, "max_hp": 16},
    },
}

WEAPONS = [
    {"id": "oak_blade", "name": "Oak Blade", "cost": 45, "attack": 1},
    {"id": "mercenary_sabre", "name": "Mercenary Sabre", "cost": 90, "attack": 2},
    {"id": "wyrm_hook", "name": "Wyrm Hook", "cost": 150, "attack": 3},
]

ARMOR = [
    {"id": "patched_leathers", "name": "Patched Leathers", "cost": 40, "defense": 1},
    {"id": "iron_plates", "name": "Iron Plates", "cost": 85, "defense": 2},
    {"id": "scale_coat", "name": "Scale Coat", "cost": 145, "defense": 3},
]

MONSTERS = [
    {
        "id": "ditch_goblin",
        "name": "Ditch Goblin",
        "family": "goblin",
        "min_level": 1,
        "attack": 2,
        "defense": 1,
        "max_hp": 8,
        "xp": 7,
        "gold": 9,
        "intro": "A ditch goblin lurches out of the fern line clutching a chipped knife.",
    },
    {
        "id": "grave_hound",
        "name": "Grave Hound",
        "family": "beast",
        "min_level": 1,
        "attack": 3,
        "defense": 2,
        "max_hp": 10,
        "xp": 9,
        "gold": 12,
        "intro": "A grave hound claws free of black soil and fixes on your pulse.",
    },
    {
        "id": "road_brigand",
        "name": "Road Brigand",
        "family": "bandit",
        "min_level": 2,
        "attack": 4,
        "defense": 2,
        "max_hp": 12,
        "xp": 13,
        "gold": 18,
        "intro": "A road brigand drops from a mossed milestone and demands your purse.",
    },
    {
        "id": "ash_witch",
        "name": "Ash Witch",
        "family": "witch",
        "min_level": 3,
        "attack": 5,
        "defense": 3,
        "max_hp": 14,
        "xp": 18,
        "gold": 24,
        "intro": "An ash witch draws a circle in cinders and invites you to die politely.",
    },
    {
        "id": "wyrm_broodling",
        "name": "Wyrm Broodling",
        "family": "dragonkin",
        "min_level": 4,
        "attack": 6,
        "defense": 4,
        "max_hp": 18,
        "xp": 25,
        "gold": 32,
        "intro": "A wyrm broodling drops from a ruined arch trailing embers and spite.",
    },
    {
        "id": "grave_bailiff",
        "name": "Grave Bailiff",
        "family": "witch",
        "min_level": 4,
        "attack": 6,
        "defense": 4,
        "max_hp": 20,
        "xp": 29,
        "gold": 34,
        "intro": "A Grave Bailiff pulls itself out of the Cut in strips of black oath-cloth and wet clay.",
    },
    {
        "id": "toll_reeve",
        "name": "Toll Reeve",
        "family": "bandit",
        "min_level": 4,
        "attack": 6,
        "defense": 4,
        "max_hp": 21,
        "xp": 30,
        "gold": 38,
        "intro": "The Toll Reeve steps into the road with a chain ledger in one hand and a killing blade in the other.",
    },
    {
        "id": "cinder_knight",
        "name": "Cinder Knight",
        "family": "dragonkin",
        "min_level": 5,
        "attack": 7,
        "defense": 5,
        "max_hp": 24,
        "xp": 34,
        "gold": 42,
        "intro": "A Cinder Knight rises from the ruin stair in old armor glazed with ember-light.",
    },
]

BRANCH_ELITES: dict[str, list[dict[str, Any]]] = {
    "grave_cut": [
        {
            "monster_id": "grave_bailiff",
            "title": "The Bailiff Walks",
            "scene": [
                "A bell with no tower to hang in tolls once under the bank.",
                "Something sworn to old graves is coming uphill to collect.",
            ],
        },
    ],
    "bandit_road": [
        {
            "monster_id": "toll_reeve",
            "title": "The Road Collects",
            "scene": [
                "A chain scrapes over stone ahead, slow and confident.",
                "Whoever kept this road once has sent an heir mean enough to try again.",
            ],
        },
    ],
    "ruined_watch": [
        {
            "monster_id": "cinder_knight",
            "title": "The Watch Remembers",
            "scene": [
                "The stair ash shifts as if someone armored is standing up inside it.",
                "The old tower has found one more sentinel to accuse the living with.",
            ],
        },
    ],
    "ashen_pass": [
        {
            "monster_id": "cinder_knight",
            "title": "The Pass Takes Measure",
            "scene": [
                "Heat folds in around you and shapes itself into something mailed and hateful.",
                "The mountain is no longer content to test your footing. It wants your name too.",
            ],
        },
    ],
}

FOREST_EVENTS = [
    {
        "id": "coin_purse",
        "title": "Lost purse",
        "text": "You find a rain-soaked purse wedged under a root.",
        "gold": 14,
        "tags": ["road", "bandit", "wild"],
    },
    {
        "id": "travel_shrine",
        "title": "Wayside shrine",
        "text": "A wayside shrine warms as you touch its cracked stone face.",
        "dragon_favor": 1,
        "tags": ["faith", "grave", "shrine"],
    },
    {
        "id": "moon_berries",
        "title": "Moon-berries",
        "text": "You gather moon-berries that calm your breath and steady your hands.",
        "heal": 3,
        "tags": ["wild", "witch", "ruin"],
    },
    {
        "id": "grave_bloom",
        "title": "Grave bloom",
        "text": "A pale bloom opens between split headstones, its roots drinking old names.",
        "heal": 2,
        "dragon_favor": 1,
        "tags": ["grave", "omen"],
    },
    {
        "id": "cutpurse_cache",
        "title": "Cutpurse cache",
        "text": "You turn up a thieves' cache tucked behind loose stone and bramble.",
        "gold": 20,
        "tags": ["bandit", "road"],
    },
    {
        "id": "watchtower_ash",
        "title": "Ash in the stair",
        "text": "You sift old ash from a broken stair and find the heat never quite left it.",
        "dragon_favor": 1,
        "tags": ["ruin", "dragonkin"],
    },
]

DRAGON_NAMES = [
    "Sablewyrm",
    "Mournflame",
    "Thornmaw",
    "Glassfang",
    "Night Ember",
]

DRAGON_TITLES = [
    "the Ash Crown",
    "the Black Wing",
    "the Hollow Flame",
    "the Candle of Ruin",
    "the Red Quiet",
]

DRAGON_STAGES = [
    {
        "id": "slumber",
        "name": "Slumber",
        "mood": "sleeping under the mountain root",
        "weather": "The dragon sleeps. The village only half-believes in peace.",
        "threshold": 6,
    },
    {
        "id": "stirring",
        "name": "Stirring",
        "mood": "turning in its ash bed",
        "weather": "The dragon stirs. Smoke taste and old fear ride the wind.",
        "threshold": 14,
    },
    {
        "id": "hunting",
        "name": "Hunting",
        "mood": "circling beyond the ridge in a patient temper",
        "weather": "The dragon hunts. Every road feels watched from above.",
        "threshold": 26,
    },
    {
        "id": "shadowfall",
        "name": "Shadowfall",
        "mood": "casting its will over the roads below",
        "weather": "Shadowfall. The whole board lives under the dragon's weather now.",
        "threshold": 999,
    },
]

DRAGON_CAPSTONE = {
    "required_stage": "shadowfall",
    "min_level": 5,
    "min_favor": 3,
}

VILLAGE_FIGURES: dict[str, dict[str, Any]] = {
    "market_square": {
        "name": "Pellar Reed",
        "role": "scrap broker",
        "greeting": [
            "Pellar Reed keeps one eye on the square and the other on whatever it might still give up for cheap.",
            "'Everything falls off somebody eventually,' he says. 'Question is whether you were there first.'",
        ],
        "favor_name": "tip",
        "favor_label": "Ask for a scrap broker's lead",
    },
    "lantern_inn": {
        "name": "Edda Lantern",
        "role": "innkeeper",
        "greeting": [
            "Edda Lantern wipes the same mug every time you look over.",
            "'Road dust and dragon weather both pay my rent,' she says. 'Sit if you're buying truth.'",
        ],
        "favor_name": "supper",
        "favor_label": "Take the inn's road supper",
    },
    "healers_lane": {
        "name": "Mother Sable",
        "role": "healer",
        "greeting": [
            "Mother Sable ties off a strip of linen with her teeth and gives you one hard look.",
            "'If you came here for comfort, take the long way back out,' she says.",
        ],
        "favor_name": "poultice",
        "favor_label": "Ask for a road poultice",
    },
    "armorers_row": {
        "name": "Brannock Vale",
        "role": "armorer",
        "greeting": [
            "Brannock Vale leans on the forge block like he expects the steel to argue back.",
            "'You want pretty metal or living metal?' he asks. 'I only sell the second kind.'",
        ],
        "favor_name": "sharpen",
        "favor_label": "Ask for a field edge",
    },
    "criers_dais": {
        "name": "Old Tern",
        "role": "town crier",
        "greeting": [
            "Old Tern smells of rain, lamp oil, and yesterday's panic.",
            "'People pay me for volume,' he says. 'Wisdom is extra.'",
        ],
        "favor_name": "warning",
        "favor_label": "Ask where the road bites hardest",
    },
    "forest_gate": {
        "name": "Gate-Mother Hesh",
        "role": "gate warden",
        "greeting": [
            "Gate-Mother Hesh stands under the lintel like the post was grown around her on purpose.",
            "'People call it a gate,' she says. 'Mostly it's a mouth that checks who walks into it.'",
        ],
        "favor_name": "charm",
        "favor_label": "Ask for a gate charm",
    },
    "dragon_shrine": {
        "name": "Sister Ione",
        "role": "shrine keeper",
        "greeting": [
            "Sister Ione keeps her hands folded until she needs them for prophecy.",
            "'The shrine never lies,' she says. 'It only speaks in costs.'",
        ],
        "favor_name": "ward",
        "favor_label": "Ask for a cinder ward",
    },
}

CONTRACT_TEMPLATES: dict[str, list[dict[str, Any]]] = {
    "grave_cut": [
        {
            "id": "grave_quiet",
            "title": "Lay the Cut Quiet",
            "issuer": "Mother Sable",
            "hook": "The banks are spitting old trouble back into the road.",
            "objective": "Put down 2 grave things in the Grave Cut.",
            "goal_kind": "kill_family",
            "families": ["beast", "witch"],
            "count": 2,
            "reward_gold": 26,
            "reward_xp": 16,
            "reward_favor": 1,
        },
        {
            "id": "seal_recovery",
            "title": "Recover a Grave Seal",
            "issuer": "Old Tern",
            "hook": "Something with a dead house-mark keeps changing hands in whispers.",
            "objective": "Find 1 Grave Seal in the Grave Cut.",
            "goal_kind": "recover_template",
            "template_ids": ["grave_seal"],
            "count": 1,
            "reward_gold": 24,
            "reward_xp": 14,
            "reward_favor": 1,
        },
        {
            "id": "moon_glass_for_the_dead",
            "title": "Moon Glass for the Dead",
            "issuer": "Sister Ione",
            "hook": "The cut keeps holding light where there should only be root and mud.",
            "objective": "Bring back 1 Moon Glass Bead from the Grave Cut.",
            "goal_kind": "recover_template",
            "template_ids": ["moon_glass"],
            "count": 1,
            "reward_gold": 22,
            "reward_xp": 12,
            "reward_favor": 2,
        },
    ],
    "bandit_road": [
        {
            "id": "break_the_toll",
            "title": "Break the Toll",
            "issuer": "Edda Lantern",
            "hook": "Too many travelers are arriving poorer and meaner than they left.",
            "objective": "Drop 2 brigands on Bandit Road.",
            "goal_kind": "kill_family",
            "families": ["bandit", "goblin"],
            "count": 2,
            "reward_gold": 30,
            "reward_xp": 15,
            "reward_favor": 0,
        },
        {
            "id": "tally_for_tally",
            "title": "Bring Back a Tally Stick",
            "issuer": "Brannock Vale",
            "hook": "The cutpurses are counting something worse than coin again.",
            "objective": "Recover 1 Bandit Tally Stick from Bandit Road.",
            "goal_kind": "recover_template",
            "template_ids": ["bandit_tally"],
            "count": 1,
            "reward_gold": 28,
            "reward_xp": 13,
            "reward_favor": 0,
        },
        {
            "id": "road_coin_run",
            "title": "Road Coin Run",
            "issuer": "Old Tern",
            "hook": "Someone is shaking the road harder than the tax men ever managed.",
            "objective": "Recover 1 Saint Coin from Bandit Road.",
            "goal_kind": "recover_template",
            "template_ids": ["saint_coin"],
            "count": 1,
            "reward_gold": 25,
            "reward_xp": 11,
            "reward_favor": 1,
        },
    ],
    "ruined_watch": [
        {
            "id": "ash_reading",
            "title": "Read the Ash Stair",
            "issuer": "Sister Ione",
            "hook": "The watch ruin has started answering the shrine in smoke.",
            "objective": "Bring back 1 Wyrm Scale Shard from the Ruined Watch.",
            "goal_kind": "recover_template",
            "template_ids": ["wyrm_scale"],
            "count": 1,
            "reward_gold": 34,
            "reward_xp": 18,
            "reward_favor": 2,
        },
        {
            "id": "thin_the_brood",
            "title": "Thin the Brood",
            "issuer": "Old Tern",
            "hook": "The ridge keeps seeing ember-wing shadows where no birds should fly.",
            "objective": "Kill 1 dragonkin or ash-witch in the Ruined Watch.",
            "goal_kind": "kill_family",
            "families": ["dragonkin", "witch"],
            "count": 1,
            "reward_gold": 36,
            "reward_xp": 20,
            "reward_favor": 2,
        },
        {
            "id": "glass_from_the_watch",
            "title": "Bring Back Moon Glass",
            "issuer": "Brannock Vale",
            "hook": "The ruin's old fire keeps tempering stranger things than steel.",
            "objective": "Recover 1 Moon Glass Bead from the Ruined Watch.",
            "goal_kind": "recover_template",
            "template_ids": ["moon_glass"],
            "count": 1,
            "reward_gold": 32,
            "reward_xp": 17,
            "reward_favor": 1,
        },
    ],
}

FALLBACK_CRIER_LINES = [
    "The crier says the woods are hungry and glory never comes cheap.",
    "The crier says coin moves faster than mercy and the dragon keeps score.",
    "The crier says heroes are just names that lived long enough to be shouted.",
]

CURIO_TEMPLATES: dict[str, dict[str, Any]] = {
    "saint_coin": {
        "name": "Saint Coin",
        "blurb": "A silvered devotional coin worn smooth by generations of desperate thumbs.",
        "tags": ["village", "faith", "trade"],
        "sale_gold": 12,
        "shrine_favor": 1,
    },
    "grave_seal": {
        "name": "Grave Seal",
        "blurb": "A wax seal hardened around old ash, still bearing the impression of a forgotten house.",
        "tags": ["grave", "secret", "omen"],
        "sale_gold": 10,
        "shrine_favor": 1,
    },
    "bandit_tally": {
        "name": "Bandit Tally Stick",
        "blurb": "A notched hazel stick used to count debts, kills, or both.",
        "tags": ["bandit", "crime", "rumor"],
        "sale_gold": 11,
        "shrine_favor": 0,
    },
    "wyrm_scale": {
        "name": "Wyrm Scale Shard",
        "blurb": "A black-red scale sliver that still holds a faint warmth under the nail.",
        "tags": ["dragon", "rare", "shrine"],
        "sale_gold": 18,
        "shrine_favor": 2,
    },
    "moon_glass": {
        "name": "Moon Glass Bead",
        "blurb": "A pale bead of old glass that catches light even when the sky gives none.",
        "tags": ["witch", "trade", "curious"],
        "sale_gold": 14,
        "shrine_favor": 1,
    },
    "pilgrim_knot": {
        "name": "Pilgrim Knot",
        "blurb": "A charred loop of prayer cord tied so tight it looks more like a vow than a thread.",
        "tags": ["faith", "road", "shrine"],
        "sale_gold": 8,
        "shrine_favor": 2,
    },
}

LOCATIONS: dict[str, dict[str, Any]] = {
    "market_square": {
        "name": "Market Square",
        "kind": "village hub",
        "description": "Wagon ruts shine with old rain and boot leather. The village breathes here in barter, soot, and half-heard boasts.",
        "discoveries": [
            "a stone cistern with coins blackened by weather",
            "banners stitched over older dragon-burn marks",
            "foot traffic splitting toward trade, healing, and the north road",
        ],
        "routes": [
            {"to": "lantern_inn", "label": "Lantern Inn Yard", "aliases": ["east", "e", "inn", "lantern inn"]},
            {"to": "healers_lane", "label": "Healer's Lane", "aliases": ["south", "s", "healer", "lane"]},
            {"to": "armorers_row", "label": "Armorer's Row", "aliases": ["west", "w", "armory", "armorer", "forge"]},
            {"to": "hall_steps", "label": "Hall Steps", "aliases": ["up", "u", "hall", "steps"]},
            {"to": "north_road", "label": "North Road", "aliases": ["north", "n", "road"]},
            {"to": "criers_dais", "label": "Crier's Dais", "aliases": ["down", "d", "crier", "dais"]},
        ],
    },
    "criers_dais": {
        "name": "Crier's Dais",
        "kind": "public platform",
        "description": "A weather-silvered platform rises above the crowd, built so proclamations can outrun panic.",
        "discoveries": [
            "ink-stained notices pinned under brass nails",
            "a bell rope dark with a century of hands",
        ],
        "routes": [
            {"to": "market_square", "label": "Market Square", "aliases": ["east", "e", "square", "back"]},
        ],
    },
    "lantern_inn": {
        "name": "Lantern Inn Yard",
        "kind": "inn yard",
        "description": "Warm windowlight spills across puddles and wagon boards. Rumor lives here longer than wine does.",
        "discoveries": [
            "a chalkboard of rumors beneath the eaves",
            "bench planks carved with old boasts and older lies",
        ],
        "routes": [
            {"to": "market_square", "label": "Market Square", "aliases": ["west", "w", "square", "back"]},
        ],
    },
    "healers_lane": {
        "name": "Healer's Lane",
        "kind": "service lane",
        "description": "The air smells of herbs, hot water, and iron. Even the stones seem to ask who bled this morning.",
        "discoveries": [
            "red tonic bottles glowing in a shuttered window",
            "bandages drying on a line like surrender flags",
        ],
        "routes": [
            {"to": "market_square", "label": "Market Square", "aliases": ["north", "n", "square", "back"]},
        ],
    },
    "armorers_row": {
        "name": "Armorer's Row",
        "kind": "trade row",
        "description": "Hammer-song rolls off the stone fronts while old blades and newer ambitions hang side by side.",
        "discoveries": [
            "racks of shields dented by men who thought they were heroes",
            "a soot-black price board tied with chain",
        ],
        "routes": [
            {"to": "market_square", "label": "Market Square", "aliases": ["east", "e", "square", "back"]},
        ],
    },
    "hall_steps": {
        "name": "Hall Steps",
        "kind": "monument stairs",
        "description": "Broad steps climb toward plaques and carved names. Every rise feels like a dare addressed to the living.",
        "discoveries": [
            "brass plates polished by jealousy more than pride",
            "fresh chalk where someone has tried to write themselves into history",
        ],
        "routes": [
            {"to": "market_square", "label": "Market Square", "aliases": ["down", "d", "square", "back"]},
        ],
    },
    "north_road": {
        "name": "North Road",
        "kind": "road",
        "description": "The village thins behind you. Ahead, the road breaks into gate-shadow, pine-dark, and the pale rise of the shrine path.",
        "discoveries": [
            "wagon tracks fading into root-shadow",
            "mile markers chipped by old claws",
        ],
        "routes": [
            {"to": "market_square", "label": "Market Square", "aliases": ["south", "s", "square", "back"]},
            {"to": "forest_gate", "label": "Forest Gate", "aliases": ["north", "n", "gate", "forest"]},
            {"to": "shrine_path", "label": "Shrine Path", "aliases": ["east", "e", "shrine", "path"]},
        ],
    },
    "forest_gate": {
        "name": "Forest Gate",
        "kind": "gate",
        "description": "A timber gate leans under creeping moss. Beyond it, the old wood listens before it answers.",
        "discoveries": [
            "warning charms tied in fresh twine",
            "splinters darkened by old blood and wet fog",
        ],
        "routes": [
            {"to": "north_road", "label": "North Road", "aliases": ["south", "s", "road", "back"]},
            {"to": "old_wood_edge", "label": "Old Wood Edge", "aliases": ["north", "n", "wood", "edge"]},
        ],
    },
    "old_wood_edge": {
        "name": "Old Wood Edge",
        "kind": "wild boundary",
        "description": "The tree line knots tight here. Roots twist through black leaves and every path looks half-invented by fear.",
        "discoveries": [
            "broken antlers caught in thorn cane",
            "a hunter's marker cut into a leaning pine",
        ],
        "wild_action": "Test the first dark between the trunks",
        "event_line": "The first rank of old trees closes behind you like a gate made of bark and doubt.",
        "monster_families": ["goblin", "beast"],
        "event_tags": ["wild"],
        "curio_templates": ["saint_coin", "moon_glass", "pilgrim_knot"],
        "rumor_event": "{handle} walked the Old Wood Edge and came back with forest weather on them.",
        "rumor_victory": "{handle} dropped {monster} at the Old Wood Edge where the village road forgets its own name.",
        "routes": [
            {"to": "forest_gate", "label": "Forest Gate", "aliases": ["south", "s", "gate", "back"]},
            {"to": "grave_cut", "label": "Grave Cut", "aliases": ["west", "w", "grave", "cut"]},
            {"to": "bandit_road", "label": "Bandit Road", "aliases": ["east", "e", "bandit", "road"]},
            {"to": "ruined_watch", "label": "Ruined Watch", "aliases": ["north", "n", "watch", "ruin"]},
        ],
    },
    "grave_cut": {
        "name": "Grave Cut",
        "kind": "sunken track",
        "description": "The path sinks between earthen banks where roots chew through old stones and the smell of turned soil never leaves.",
        "discoveries": [
            "grave markers split by frost and ivy",
            "crow feathers trapped in a bramble knot",
        ],
        "wild_action": "Follow the cut where the dead do not rest",
        "event_line": "The track drops into colder ground where even your boots sound unwelcome.",
        "monster_families": ["beast", "witch"],
        "event_tags": ["grave", "omen", "witch"],
        "curio_templates": ["grave_seal", "moon_glass", "saint_coin"],
        "rumor_event": "{handle} came back from the Grave Cut carrying cemetery cold and a look they would not explain.",
        "rumor_victory": "{handle} put down {monster} in the Grave Cut while the dead listened from below the bank.",
        "routes": [
            {"to": "old_wood_edge", "label": "Old Wood Edge", "aliases": ["east", "e", "edge", "back"]},
        ],
    },
    "bandit_road": {
        "name": "Bandit Road",
        "kind": "broken road",
        "description": "A once-kept road runs here under shattered milestones and old wheel ruts. It still remembers taxes, steel, and betrayal.",
        "discoveries": [
            "a toll chain rusted into the mud",
            "boot prints that never quite wash out",
        ],
        "wild_action": "Work the broken road for trouble and coin",
        "event_line": "You keep to the ruined road, where every bend promises either trade or a knife.",
        "monster_families": ["bandit", "goblin"],
        "event_tags": ["road", "bandit"],
        "curio_templates": ["bandit_tally", "saint_coin", "moon_glass"],
        "rumor_event": "{handle} worked the Bandit Road and came home richer, or at least less honest-looking.",
        "rumor_victory": "{handle} broke {monster} on Bandit Road and kept walking without apology.",
        "routes": [
            {"to": "old_wood_edge", "label": "Old Wood Edge", "aliases": ["west", "w", "edge", "back"]},
        ],
    },
    "ruined_watch": {
        "name": "Ruined Watch",
        "kind": "collapsed outpost",
        "description": "The watchtower has long since given up standing straight. Stone ribs and fire-black beams still look north as if the war might resume at dusk.",
        "discoveries": [
            "an archer slit packed with nettles",
            "char on the stair where the tower burned from within",
        ],
        "wild_action": "Climb through the ruin and hunt its shadowed halls",
        "event_line": "You cross the broken threshold and the ruin breathes old ash across your face.",
        "monster_families": ["witch", "dragonkin", "bandit"],
        "event_tags": ["ruin", "dragonkin", "witch"],
        "curio_templates": ["wyrm_scale", "grave_seal", "bandit_tally"],
        "rumor_event": "{handle} searched the Ruined Watch and came away smelling of ash and bad history.",
        "rumor_victory": "{handle} killed {monster} in the Ruined Watch where the stones still dream of fire.",
        "routes": [
            {"to": "old_wood_edge", "label": "Old Wood Edge", "aliases": ["south", "s", "edge", "back"]},
            {"to": "shrine_path", "label": "Shrine Path", "aliases": ["east", "e", "shrine", "path"]},
        ],
    },
    "shrine_path": {
        "name": "Shrine Path",
        "kind": "hillside path",
        "description": "The climb narrows to stone and scrub. Wind moves here like something reading over your shoulder.",
        "discoveries": [
            "charred prayer ribbons caught in briar",
            "scattered white pebbles laid out like a warning script",
        ],
        "routes": [
            {"to": "north_road", "label": "North Road", "aliases": ["west", "w", "road", "back"]},
            {"to": "dragon_shrine", "label": "Dragon Shrine", "aliases": ["north", "n", "shrine"]},
            {"to": "ruined_watch", "label": "Ruined Watch", "aliases": ["south", "s", "watch", "ruin"]},
        ],
    },
    "dragon_shrine": {
        "name": "Dragon Shrine",
        "kind": "shrine",
        "description": "The shrine stands in cracked silence, its stone throat full of old vows and hotter names.",
        "discoveries": [
            "glass eyes set into the altar to catch dawn",
            "melted candle gutters fused like scales",
        ],
        "routes": [
            {"to": "shrine_path", "label": "Shrine Path", "aliases": ["south", "s", "path", "back"]},
        ],
    },
    "ashen_pass": {
        "name": "Ashen Pass",
        "kind": "dragon road",
        "description": "The path above the shrine is all slag-stone, char, and wind that smells like coin left too long in a fire.",
        "discoveries": [
            "black glass under your boots where older travelers melted into the road",
            "ribs of old watchfires bent flat by some hotter weather",
        ],
        "wild_action": "Climb the ash road under the dragon's weather",
        "event_line": "The pass rises in black steps while ash moves along it like a living thing deciding whether you belong.",
        "monster_families": ["dragonkin", "witch"],
        "event_tags": ["ruin", "dragonkin", "omen"],
        "curio_templates": ["wyrm_scale", "pilgrim_knot", "moon_glass"],
        "rumor_event": "{handle} climbed the Ashen Pass and came back with soot in their teeth and something like purpose in their eyes.",
        "rumor_victory": "{handle} broke {monster} on the Ashen Pass while the mountain listened for a harder name.",
        "routes": [
            {"to": "dragon_shrine", "label": "Dragon Shrine", "aliases": ["south", "s", "shrine", "back"]},
            {"to": "ember_peak", "label": "Ember Peak", "aliases": ["north", "n", "peak", "eyrie"]},
        ],
    },
    "ember_peak": {
        "name": "Ember Peak",
        "kind": "dragon eyrie",
        "description": "The ridge opens into a blasted crown of stone where every gust arrives already tasting of scales and smoke.",
        "discoveries": [
            "a gouged landing shelf broad enough for something ancient to kneel on",
            "shards of eggshell-black glass buried in soot and old treasure dust",
        ],
        "routes": [
            {"to": "ashen_pass", "label": "Ashen Pass", "aliases": ["south", "s", "pass", "back"]},
        ],
    },
}
