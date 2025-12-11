# quest_agent.py
#
# Deterministic quest agent for OSRS.
# - Never returns "adjust_camera"
# - Only does:
#       dialog_continue
#       talk_to_npc
#       wait

from typing import Any, Dict, List, Optional
import math

# Very simple mapping of quests -> important NPCs near the start.
# (This is enough to show behaviour; full F2P coverage would need more entries.)
QUEST_NPCS = {
    "tutorial island": [
        "Gielinor Guide",
        "Survival Expert",
        "Master Chef",
        "Quest Guide",
        "Mining Instructor",
        "Combat Instructor",
        "Brother Brace",
        "Magic Instructor",
    ],
    "cook's assistant": ["Cook"],
    "sheep shearer": ["Fred the Farmer"],
    "the restless ghost": ["Father Aereck", "Father Urhney"],
    "rune mysteries": ["Duke Horacio", "Sedridor"],
    "demon slayer": ["Gypsy Aris", "Sir Prysin"],
    "shield of arrav": ["Reldo", "Charlie the Tramp", "Curator Haig Halen"],
    "vampire slayer": ["Morgan", "Dr Harlow"],
    "goblin diplomacy": ["General Bentnoze", "General Wartface"],
    "imp catcher": ["Wizard Mizgog"],
    "doric's quest": ["Doric"],
    "the knight's sword": ["Sir Vyvin"],
    "pirate's treasure": ["Redbeard Frank"],
    "prince ali rescue": ["Hassan", "Osman"],
    "black knights' fortress": ["Sir Amik Varze"],
    "witch's potion": ["Hetty"],
    "the corsair curse": ["Captain Tock"],
    "below ice mountain": ["Willie"],
    "dragon slayer i": ["Guildmaster", "Oziach"],
}


def _distance(p: Dict[str, Any], q: Dict[str, Any]) -> float:
    try:
        dx = (p.get("x", 0) or 0) - (q.get("x", 0) or 0)
        dy = (p.get("y", 0) or 0) - (q.get("y", 0) or 0)
        return math.hypot(dx, dy)
    except Exception:
        return 999999.0


def _find_best_npc(
    game_state: Dict[str, Any],
    quest_name: str,
) -> Optional[Dict[str, Any]]:
    """
    Return the best NPC to talk to:
    1) Prefer quest-specific NPCs from QUEST_NPCS, nearest first
    2) Otherwise any nearby NPC with a Talk-to action
    """
    npcs: List[Dict[str, Any]] = game_state.get("npcs") or []
    player = game_state.get("player") or {}

    quest_key = quest_name.lower().strip()
    quest_list = [q.lower() for q in QUEST_NPCS.get(quest_key, [])]

    best = None
    best_dist = 999999.0

    for npc in npcs:
        name = (npc.get("name") or "").strip()
        if not name:
            continue

        name_l = name.lower()
        actions = [a.lower() for a in (npc.get("actions") or []) if a]
        npc_pos = {
            "x": npc.get("x"),
            "y": npc.get("y"),
            "plane": npc.get("plane"),
        }
        dist = _distance(player, npc_pos)

        # Prefer quest-specific NPCs
        if name_l in quest_list:
            if dist < best_dist:
                best = npc
                best_dist = dist
            continue

        # Fallback: any NPC with a talk option, if we don't have a quest NPC yet
        if best is None and any("talk" in a for a in actions):
            if dist < best_dist:
                best = npc
                best_dist = dist

    return best


def decide_quest_action(client, game_state: Dict[str, Any], ctx: Dict[str, Any]) -> Dict[str, Any]:
    """
    Deterministic questing logic used by brain_server in QUEST mode.

    It does:
      - dialog_continue if "click here to continue" is visible
      - talk_to_npc for the most relevant nearby quest NPC
      - wait otherwise

    It NEVER returns "adjust_camera".
    """

    # 1) Auto-continue dialogue when possible
    dialog = game_state.get("dialog") or {}
    if dialog.get("can_continue"):
        return {
            "action": "dialog_continue",
            "target": None,
            "meta": {"reason": "Dialogue has 'Click here to continue'."},
        }

    # 2) Try to find a relevant quest NPC nearby
    quest_name = ctx.get("current_quest") or ""
    npc = _find_best_npc(game_state, quest_name)

    if npc is not None:
        return {
            "action": "talk_to_npc",
            "target": {
                "name": npc.get("name"),
                # coords are not strictly needed for talk_to_npc on the Java side
                "x": npc.get("x"),
                "y": npc.get("y"),
                "plane": npc.get("plane"),
            },
            "meta": {
                "reason": f"Talking to quest NPC '{npc.get('name')}' for quest '{quest_name}'."
            },
        }

    # 3) Nothing useful detected → just wait, do NOT move the camera
    return {
        "action": "wait",
        "target": None,
        "meta": {
            "reason": "No relevant quest NPC or dialogue – waiting safely instead of moving camera."
        },
    }