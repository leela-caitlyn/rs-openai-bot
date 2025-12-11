# skill_agent.py

import json
from typing import Any, Dict


def _extract_text_state(game_state: Dict[str, Any], ctx: Dict[str, Any]) -> str:
    player = game_state.get("player") or {}
    skills = game_state.get("skills") or {}
    npcs = game_state.get("npcs") or []
    objects = game_state.get("objects") or []
    dialog = game_state.get("dialog") or {}
    chat_log = game_state.get("chat_log") or []
    ui_text = game_state.get("ui_text") or []

    lines = []

    lines.append(
        "CONTEXT (SKILLING): "
        f"mode={ctx.get('mode')}, "
        f"skill_target={ctx.get('skill_target')}, "
        f"target_level={ctx.get('skill_target_level')}, "
        f"budget_gp={ctx.get('skill_budget_gp')}, "
        f"skilling_goal={ctx.get('skilling_goal')}"
    )

    if player:
        lines.append(
            f"PLAYER: name={player.get('name')}, pos=({player.get('x')},"
            f"{player.get('y')},{player.get('plane')})"
        )

    skill_pairs = [f"{k}={v}" for k, v in skills.items()]
    if skill_pairs:
        lines.append("SKILLS: " + ", ".join(skill_pairs))

    lines.append("NPCS (up to 15):")
    for npc in npcs[:15]:
        acts = npc.get("actions") or []
        lines.append(
            f"- {npc.get('name')} @ ({npc.get('x')},{npc.get('y')},{npc.get('plane')}) "
            f"actions={acts}"
        )

    if objects:
        lines.append("OBJECTS (up to 20):")
        for obj in objects[:20]:
            acts = obj.get("actions") or []
            lines.append(
                f"- {obj.get('name')} @ ({obj.get('x')},{obj.get('y')},{obj.get('plane')}) "
                f"actions={acts}"
            )

    # Dialog
    npc_text = dialog.get("npc_text")
    player_text = dialog.get("player_text")
    can_continue = dialog.get("can_continue", False)
    lines.append(f"DIALOG.can_continue={can_continue}")
    if npc_text:
        lines.append(f"DIALOG.NPC: {npc_text}")
    if player_text:
        lines.append(f"DIALOG.PLAYER: {player_text}")

    if chat_log:
        lines.append("CHAT_LOG (latest first):")
        for msg in chat_log[-10:]:
            lines.append(f"- {msg}")

    if ui_text:
        lines.append("UI_TEXT (first 30 entries):")
        for w in ui_text[:30]:
            lines.append(f"- [{w.get('group')}:{w.get('id')}] {w.get('text')}")

    return "\n".join(lines)


def _call_llm_for_skill(client, game_state: Dict[str, Any], ctx: Dict[str, Any]) -> Dict[str, Any]:
    summary = _extract_text_state(game_state, ctx)

    system_prompt = (
        "You control an Old School RuneScape character via a bot.\n"
        "Your role is SKILLING: train the indicated skill toward the target level without overspending.\n\n"
        "You can ONLY choose ONE of these actions:\n"
        "  - walk_to_tile\n"
        "  - talk_to_npc\n"
        "  - interact_object\n"
        "  - dialog_continue\n"
        "  - adjust_camera\n"
        "  - wait\n\n"
        "Action JSON schema (strict):\n"
        "{\n"
        '  \"action\": \"walk_to_tile\" | \"talk_to_npc\" | \"interact_object\" | \"dialog_continue\" | \"adjust_camera\" | \"wait\",\n'
        "  \"target\": null | {\n"
        "      \"x\": int,\n"
        "      \"y\": int,\n"
        "      \"plane\": int,\n"
        "      \"name\": string,\n"
        "      \"option\": string\n"
        "  },\n"
        "  \"meta\": { \"reason\": string }\n"
        "}\n\n"
        "Additional rules:\n"
        " - Skill information:\n"
        "   * skill_target: which skill should be trained (e.g. Mining, Woodcutting).\n"
        "   * skill_target_level: goal level.\n"
        "   * skill_budget_gp: maximum gold to spend this training run.\n"
        " - Do NOT invent map coordinates. For walk_to_tile, either:\n"
        "   * Use the player's current tile (to stay put), or\n"
        "   * Use the tile of a visible NPC you want to approach (like Banker, Grand Exchange Clerk).\n"
        " - Use interact_object for skilling spots like trees, rocks, or range fires that are visible.\n"
        " - Prefer talk_to_npc when interacting with Grand Exchange, bankers or skilling tutors.\n"
        " - Use dialog_continue if a dialogue is open.\n"
        " - Use wait if there is no obvious safe action.\n"
    )

    user_prompt = (
        "Here is the current game state and skilling context.\n\n"
        f"{summary}\n\n"
        "Decide the best SINGLE next action to progress skilling and return ONLY the JSON object."
    )

    resp = client.chat.completions.create(
        model="gpt-5-mini",
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    )

    text = resp.choices[0].message.content.strip()

    try:
        start = text.find("{")
        end = text.rfind("}")
        if start != -1 and end != -1 and end > start:
            text = text[start : end + 1]
        return json.loads(text)
    except Exception as e:
        print("Failed to parse skill LLM JSON:", e, "raw:", text)
        return {
            "action": "adjust_camera",
            "target": None,
            "meta": {"reason": "LLM JSON parse failed, adjust camera as safe fallback."},
        }


def decide_skill_action(client, game_state: Dict[str, Any], ctx: Dict[str, Any]) -> Dict[str, Any]:
    """
    Top-level skill agent entry point.
    """
    dialog = game_state.get("dialog") or {}
    if dialog.get("can_continue"):
        return {
            "action": "dialog_continue",
            "target": None,
            "meta": {"reason": "Dialogue shows 'Click here to continue'."},
        }

    return _call_llm_for_skill(client, game_state, ctx)