from __future__ import annotations

import random
import uuid
from copy import deepcopy
from datetime import datetime, timezone
from typing import Any

from .world import ARMOR, CLASSES, CONTRACT_TEMPLATES, CURIO_TEMPLATES, DRAGON_NAMES, DRAGON_STAGES, DRAGON_TITLES, FALLBACK_CRIER_LINES, FOREST_EVENTS, MONSTERS, WEAPONS


def current_day_key(session) -> str:
    unix = session.clock.get("unixTimeSec") if getattr(session, "clock", None) else None
    when = datetime.fromtimestamp(unix, tz=timezone.utc) if unix else datetime.now(timezone.utc)
    return when.strftime("%Y-%m-%d")


def create_player(handle: str, class_id: str) -> dict[str, Any]:
    klass = CLASSES[class_id]
    stats = klass["stats"]
    return {
        "handle": handle,
        "class_id": class_id,
        "class_name": klass["name"],
        "level": 1,
        "xp": 0,
        "hp": stats["max_hp"],
        "max_hp": stats["max_hp"],
        "attack": stats["attack"],
        "defense": stats["defense"],
        "charm": stats["charm"],
        "cunning": stats["cunning"],
        "luck": stats["luck"],
        "gold": 65,
        "gems": 1,
        "weapon_id": "rusty_sword",
        "weapon_name": "Rusty Sword",
        "weapon_bonus": 0,
        "armor_id": "worn_cloak",
        "armor_name": "Worn Cloak",
        "armor_bonus": 0,
        "potions": 1,
        "turns": 12,
        "forest_fights": 5,
        "dragon_favor": 0,
        "dragon_slays": 0,
        "victories": 0,
        "defeats": 0,
        "contracts_completed": 0,
        "curios": [],
        "blessings": {},
        "daily_flags": {},
        "contracts": {"day": current_day_string(), "branches": {}},
        "location": "market_square",
        "last_day": current_day_string(),
        "last_seen_at": None,
        "status": "alive",
    }


def current_day_string() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def apply_daily_reset(player: dict[str, Any], day_key: str) -> tuple[dict[str, Any], bool]:
    if player.get("last_day") == day_key:
        return player, False
    player = deepcopy(player)
    player["turns"] = 12
    player["forest_fights"] = 5
    player["hp"] = player["max_hp"]
    player["status"] = "alive"
    player["daily_flags"] = {}
    player["blessings"] = {}
    player["contracts"] = {"day": day_key, "branches": {}}
    player["last_day"] = day_key
    return player, True


def public_summary(player: dict[str, Any]) -> dict[str, Any]:
    return {
        "handle": player["handle"],
        "class_name": player["class_name"],
        "level": player["level"],
        "xp": player["xp"],
        "dragon_favor": player["dragon_favor"],
        "dragon_slays": player.get("dragon_slays", 0),
        "victories": player["victories"],
        "contracts_completed": player.get("contracts_completed", 0),
        "last_day": player["last_day"],
    }


def create_default_dragon_state() -> dict[str, Any]:
    stage = DRAGON_STAGES[0]
    return {
        "name": random.choice(DRAGON_NAMES),
        "title": random.choice(DRAGON_TITLES),
        "stage_index": 0,
        "stage_id": stage["id"],
        "stage_name": stage["name"],
        "mood": stage["mood"],
        "weather": stage["weather"],
        "pressure": 0,
        "omens_seen": 0,
        "history": [],
        "last_slayer": None,
        "last_slay_day": None,
        "slay_count": 0,
    }


def normalize_dragon_state(state: dict[str, Any]) -> dict[str, Any]:
    stage_index = min(max(int(state.get("stage_index", 0)), 0), len(DRAGON_STAGES) - 1)
    stage = DRAGON_STAGES[stage_index]
    state["stage_index"] = stage_index
    state["stage_id"] = state.get("stage_id") or stage["id"]
    state["stage_name"] = state.get("stage_name") or stage["name"]
    state["mood"] = state.get("mood") or stage["mood"]
    state["weather"] = state.get("weather") or stage["weather"]
    state["pressure"] = max(0, int(state.get("pressure", 0)))
    state["omens_seen"] = max(0, int(state.get("omens_seen", 0)))
    state.setdefault("history", [])
    state.setdefault("last_slayer", None)
    state.setdefault("last_slay_day", None)
    state.setdefault("slay_count", 0)
    return state


def reset_dragon_after_slay(state: dict[str, Any], *, slayer: str, day_key: str) -> tuple[dict[str, Any], dict[str, Any]]:
    prior = normalize_dragon_state(deepcopy(state))
    slain = {
        "dragon": f"{prior['name']} {prior['title']}",
        "slayer": slayer,
        "day": day_key,
        "stage_name": prior["stage_name"],
    }
    renewed = create_default_dragon_state()
    renewed["history"] = [{"type": "slay", **slain}, *prior.get("history", [])][:12]
    renewed["last_slayer"] = slayer
    renewed["last_slay_day"] = day_key
    renewed["slay_count"] = int(prior.get("slay_count", 0)) + 1
    return renewed, slain


def dragon_pressure_cost(state: dict[str, Any]) -> int:
    stage_index = min(max(int(state.get("stage_index", 0)), 0), len(DRAGON_STAGES) - 1)
    return DRAGON_STAGES[stage_index]["threshold"]


def dragon_progress_summary(state: dict[str, Any]) -> str:
    state = normalize_dragon_state(state)
    if state["stage_index"] >= len(DRAGON_STAGES) - 1:
        return "pressure maxed"
    return f"pressure {state['pressure']}/{dragon_pressure_cost(state)}"


def advance_dragon_state(
    state: dict[str, Any],
    *,
    pressure_delta: int = 0,
    omen_delta: int = 0,
    calm_delta: int = 0,
    source: str | None = None,
) -> tuple[dict[str, Any], str | None]:
    state = normalize_dragon_state(deepcopy(state))
    state["pressure"] = max(0, state["pressure"] + pressure_delta - calm_delta)
    state["omens_seen"] = max(0, state["omens_seen"] + omen_delta)
    prior_stage = state["stage_index"]
    while state["stage_index"] < len(DRAGON_STAGES) - 1 and state["pressure"] >= dragon_pressure_cost(state):
        state["pressure"] -= dragon_pressure_cost(state)
        state["stage_index"] += 1
        stage = DRAGON_STAGES[state["stage_index"]]
        state["stage_id"] = stage["id"]
        state["stage_name"] = stage["name"]
        state["mood"] = stage["mood"]
        state["weather"] = stage["weather"]
        state["history"].insert(0, {
            "stage_id": stage["id"],
            "stage_name": stage["name"],
            "source": source or "the wild roads",
        })
        state["history"] = state["history"][:12]
    if state["stage_index"] == prior_stage:
        stage = DRAGON_STAGES[state["stage_index"]]
        state["stage_id"] = stage["id"]
        state["stage_name"] = stage["name"]
        state["mood"] = stage["mood"]
        state["weather"] = stage["weather"]
        return state, None
    return state, f"The dragon enters {state['stage_name']}. {state['weather']}"


def choose_monster(
    player: dict[str, Any],
    monster_families: list[str] | None = None,
    *,
    dragon_stage_index: int = 0,
) -> dict[str, Any]:
    threat_bonus = min(max(int(dragon_stage_index), 0), 3)
    eligible = [monster for monster in MONSTERS if monster["min_level"] <= player["level"] + 1 + threat_bonus]
    if monster_families:
        filtered = [monster for monster in eligible if monster["family"] in monster_families]
        if filtered:
            eligible = filtered
    monster = deepcopy(random.choice(eligible))
    monster["hp"] = monster["max_hp"]
    return monster


def maybe_forest_event(
    player: dict[str, Any],
    event_line: str | None = None,
    event_tags: list[str] | None = None,
) -> dict[str, Any] | None:
    if random.random() > 0.28:
        return None
    eligible = FOREST_EVENTS
    if event_tags:
        filtered = [event for event in FOREST_EVENTS if set(event.get("tags", [])) & set(event_tags)]
        if filtered:
            eligible = filtered
    event = deepcopy(random.choice(eligible))
    if "gold" in event:
        player["gold"] += event["gold"]
    if "heal" in event:
        player["hp"] = min(player["max_hp"], player["hp"] + event["heal"])
    if "dragon_favor" in event:
        player["dragon_favor"] += event["dragon_favor"]
        if player.get("class_id") == "mystic":
            player["dragon_favor"] += 1
            event["text"] += " The sign lingers, and your mystic sense draws one more thread of favor from it."
    if event_line:
        event["text"] = f"{event_line} {event['text']}"
    return event


def spend_forest_turn(player: dict[str, Any]) -> None:
    player["turns"] = max(0, player["turns"] - 1)
    player["forest_fights"] = max(0, player["forest_fights"] - 1)


def player_total_attack(player: dict[str, Any]) -> int:
    return player["attack"] + player.get("weapon_bonus", 0)


def player_total_defense(player: dict[str, Any]) -> int:
    return player["defense"] + player.get("armor_bonus", 0)


def combat_round(player: dict[str, Any], monster: dict[str, Any], action: str) -> dict[str, Any]:
    transcript: list[str] = []
    reward = None
    escaped = False
    defeated = False
    blessings = player.setdefault("blessings", {})

    defense_bonus = 2 if action == "defend" else 0
    if action == "defend" and player.get("class_id") == "knight":
        defense_bonus += 1
        transcript.append("Knight's training settles into your stance and turns the worst of the blow aside.")

    if action == "potion":
        if player["potions"] > 0:
            player["potions"] -= 1
            heal_cap = 10 if player.get("class_id") == "mystic" else 8
            healed = min(player["max_hp"] - player["hp"], heal_cap)
            player["hp"] += healed
            transcript.append(f"You drain a red tonic and steady yourself for {healed} hp.")
        else:
            transcript.append("You reach for a potion and find only an empty loop on your belt.")

    if action == "flee":
        chance = 0.35 + (player["cunning"] * 0.05)
        if player.get("class_id") == "rogue":
            chance += 0.10
        if random.random() < chance:
            escaped = True
            transcript.append(f"You break away from the {monster['name']} and vanish into the brush.")
            return {"transcript": transcript, "escaped": escaped, "reward": reward, "defeated": defeated}
        transcript.append("You turn to flee, but the wild closes around your boots.")

    if action == "attack":
        damage = max(1, player_total_attack(player) + random.randint(0, 4) - monster["defense"])
        if int(blessings.get("next_attack_bonus", 0)) > 0:
            damage += int(blessings["next_attack_bonus"])
            transcript.append("The field edge Brannock gave your weapon turns the hit nastier than usual.")
            blessings["next_attack_bonus"] = 0
        if player.get("class_id") == "hunter":
            damage += 1
            transcript.append("Hunter's instinct finds the opening before the creature knows it left one.")
        monster["hp"] = max(0, monster["hp"] - damage)
        transcript.append(f"You strike the {monster['name']} for {damage} damage.")
    elif action == "defend":
        transcript.append("You brace, blade high, and wait for the thing to overreach.")

    if monster["hp"] <= 0:
        reward = {
            "xp": monster["xp"],
            "gold": monster["gold"],
        }
        if player.get("class_id") == "rogue" and monster.get("family") == "bandit":
            reward["gold"] += 4
            transcript.append("Rogue's fingers know where brigands hide their real coin.")
        player["xp"] += reward["xp"]
        player["gold"] += reward["gold"]
        player["victories"] += 1
        transcript.append(f"The {monster['name']} falls. You claim {reward['gold']} gold and {reward['xp']} xp.")
        level_up(player, transcript)
        return {"transcript": transcript, "escaped": escaped, "reward": reward, "defeated": defeated}

    incoming = max(1, monster["attack"] + random.randint(0, 3) - (player_total_defense(player) + defense_bonus))
    if blessings.get("dragon_ward") and monster.get("family") == "dragonkin":
        incoming = max(1, incoming - 2)
        transcript.append("Sister Ione's cinder ward flares and eats part of the dragon-blooded blow.")
        blessings["dragon_ward"] = False
    player["hp"] = max(0, player["hp"] - incoming)
    transcript.append(f"The {monster['name']} tears back for {incoming} damage.")

    if player["hp"] <= 0:
        defeated = True
        player["defeats"] += 1
        lost = min(player["gold"], 15 + random.randint(0, 20))
        player["gold"] -= lost
        player["hp"] = 1
        player["status"] = "shaken"
        transcript.append(f"You collapse in the leaves and wake later without {lost} gold.")

    return {"transcript": transcript, "escaped": escaped, "reward": reward, "defeated": defeated}


def level_up(player: dict[str, Any], transcript: list[str]) -> None:
    while player["xp"] >= xp_for_next_level(player["level"]):
        player["level"] += 1
        player["max_hp"] += 3
        player["hp"] = player["max_hp"]
        player["attack"] += 1
        player["defense"] += 1
        if player["level"] % 2 == 0:
            player["luck"] += 1
        transcript.append(f"You rise to level {player['level']}. The world gets meaner because you did too.")


def ensure_daily_contracts(player: dict[str, Any], day_key: str) -> dict[str, Any]:
    contracts = deepcopy(player.get("contracts") or {})
    if contracts.get("day") != day_key:
        contracts = {"day": day_key, "branches": {}}
    branches = contracts.setdefault("branches", {})
    for branch_id, templates in CONTRACT_TEMPLATES.items():
        if branch_id in branches:
            continue
        rng = random.Random(f"{day_key}:{player.get('handle', 'wanderer')}:{branch_id}")
        template = deepcopy(templates[rng.randrange(len(templates))])
        branches[branch_id] = {
            "id": template["id"],
            "branch_id": branch_id,
            "title": template["title"],
            "issuer": template["issuer"],
            "hook": template["hook"],
            "objective": template["objective"],
            "goal_kind": template["goal_kind"],
            "families": list(template.get("families", [])),
            "template_ids": list(template.get("template_ids", [])),
            "count": int(template.get("count", 1)),
            "progress": 0,
            "completed": False,
            "claimed": False,
            "reward_gold": int(template["reward_gold"]),
            "reward_xp": int(template["reward_xp"]),
            "reward_favor": int(template["reward_favor"]),
        }
    player["contracts"] = contracts
    return contracts


def progress_contracts(
    player: dict[str, Any],
    *,
    branch_id: str,
    monster: dict[str, Any] | None = None,
    curio: dict[str, Any] | None = None,
) -> list[str]:
    contract = (((player.get("contracts") or {}).get("branches") or {}).get(branch_id))
    if not contract or contract.get("claimed"):
        return []
    before = int(contract.get("progress", 0))
    if contract["goal_kind"] == "kill_family" and monster is not None:
        if monster.get("family") in contract.get("families", []):
            contract["progress"] = min(int(contract["count"]), before + 1)
    if contract["goal_kind"] == "recover_template" and curio is not None:
        if curio.get("template_id") in contract.get("template_ids", []):
            contract["progress"] = min(int(contract["count"]), before + 1)
    notes: list[str] = []
    if int(contract["progress"]) > before:
        notes.append(f"Contract progress: {contract['title']} {contract['progress']}/{contract['count']}.")
    if int(contract["progress"]) >= int(contract["count"]) and not contract.get("completed"):
        contract["completed"] = True
        notes.append(f"Contract complete: {contract['title']}. Return to claim your due.")
    return notes


def claim_contract_reward(player: dict[str, Any], branch_id: str) -> tuple[bool, str]:
    contract = (((player.get("contracts") or {}).get("branches") or {}).get(branch_id))
    if not contract:
        return False, "There is no contract here with your name on it."
    if contract.get("claimed"):
        return False, "You have already claimed this contract."
    if not contract.get("completed"):
        return False, "That contract still wants more from the road."
    player["gold"] += int(contract["reward_gold"])
    player["xp"] += int(contract["reward_xp"])
    player["dragon_favor"] += int(contract["reward_favor"])
    player["contracts_completed"] = int(player.get("contracts_completed", 0)) + 1
    contract["claimed"] = True
    return True, (
        f"{contract['issuer']} pays out {contract['reward_gold']} gold, "
        f"{contract['reward_xp']} xp, and {contract['reward_favor']} dragon favor."
    )


def xp_for_next_level(level: int) -> int:
    return 20 + ((level - 1) * 18)


def buy_weapon(player: dict[str, Any], weapon_id: str) -> tuple[bool, str]:
    weapon = next((item for item in WEAPONS if item["id"] == weapon_id), None)
    if weapon is None:
        return False, "That weapon does not exist."
    if player["gold"] < weapon["cost"]:
        return False, "You do not have enough gold."
    player["gold"] -= weapon["cost"]
    player["weapon_id"] = weapon["id"]
    player["weapon_name"] = weapon["name"]
    player["weapon_bonus"] = weapon["attack"]
    return True, f"You purchase the {weapon['name']}."


def buy_armor(player: dict[str, Any], armor_id: str) -> tuple[bool, str]:
    armor = next((item for item in ARMOR if item["id"] == armor_id), None)
    if armor is None:
        return False, "That armor does not exist."
    if player["gold"] < armor["cost"]:
        return False, "You do not have enough gold."
    player["gold"] -= armor["cost"]
    player["armor_id"] = armor["id"]
    player["armor_name"] = armor["name"]
    player["armor_bonus"] = armor["defense"]
    return True, f"You strap on the {armor['name']}."


def buy_potion(player: dict[str, Any]) -> tuple[bool, str]:
    if player["gold"] < 18:
        return False, "A tonic costs 18 gold."
    player["gold"] -= 18
    player["potions"] += 1
    return True, "The healer slides a red tonic across the counter."


def heal_player(player: dict[str, Any]) -> tuple[bool, str]:
    missing = player["max_hp"] - player["hp"]
    if missing <= 0:
        return False, "The healer says you are whole enough already."
    cost = max(6, missing * 2)
    if player["gold"] < cost:
        return False, f"The healer wants {cost} gold you do not have."
    player["gold"] -= cost
    player["hp"] = player["max_hp"]
    return True, f"The healer closes your wounds for {cost} gold."


def fallback_crier(rumors: list[str], dragon_state: dict[str, Any]) -> list[str]:
    dragon_state = normalize_dragon_state(dragon_state)
    lines = [random.choice(FALLBACK_CRIER_LINES)]
    if rumors:
        lines.append(f"Latest whisper: {rumors[0]}")
    lines.append(f"The dragon {dragon_state['name']} {dragon_state['title']} is in {dragon_state['stage_name']}.")
    if dragon_state.get("last_slayer"):
        lines.append(f"Last dragon-slayer: {dragon_state['last_slayer']} on {dragon_state.get('last_slay_day') or 'an older day'}.")
    lines.append(dragon_state["weather"])
    return lines[:4]


def create_curio(template_id: str, *, origin: str, name: str | None = None, blurb: str | None = None) -> dict[str, Any]:
    template = CURIO_TEMPLATES[template_id]
    return {
        "id": f"curio-{uuid.uuid4().hex[:10]}",
        "template_id": template_id,
        "name": name or template["name"],
        "blurb": blurb or template["blurb"],
        "tags": list(template["tags"]),
        "sale_gold": template["sale_gold"],
        "shrine_favor": template["shrine_favor"],
        "origin": origin,
    }
