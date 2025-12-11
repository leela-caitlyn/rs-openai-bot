import os
from copy import deepcopy

from flask import Flask, request, jsonify
from openai import OpenAI

import quest_agent  # this is the file above

# If you already had skill_agent/manual_agent, you can keep them;
# here we treat them as optional.
try:
    import skill_agent
except ImportError:
    skill_agent = None

try:
    import manual_agent
except ImportError:
    manual_agent = None

client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

app = Flask(__name__)

# Global control state – updated via /control
CONTROL_STATE = {
    "mode": "quest",               # "quest", "skill", "manual"
    "current_quest": "Cook's Assistant",
    "do_all_quests": False,
    "skill_target": "Mining",
    "skill_target_level": 10,
    "skill_budget_gp": 5000,
    "skilling_goal": "Train the selected skill safely.",
    "manual_goal": "",
}


@app.route("/control", methods=["POST"])
def control():
    """
    Called from the RuneLite plugin to update mode, quest, skill, etc.
    """
    data = request.get_json(force=True) or {}
    for key in list(CONTROL_STATE.keys()):
        if key in data:
            CONTROL_STATE[key] = data[key]
    return jsonify({"ok": True, "state": CONTROL_STATE})


@app.route("/decide", methods=["POST"])
def decide():
    """
    Main decision endpoint called by RuneLite.
    Returns: { action: str, target: {...}|null, meta: {...} }
    """
    game_state = request.get_json(force=True) or {}
    ctx = deepcopy(CONTROL_STATE)
    mode = (ctx.get("mode") or "quest").lower()

    try:
        if mode == "quest":
            action = quest_agent.decide_quest_action(client, game_state, ctx)

        elif mode == "skill" and skill_agent is not None:
            action = skill_agent.decide_skill_action(client, game_state, ctx)

        elif mode == "manual" and manual_agent is not None:
            action = manual_agent.decide_manual_action(client, game_state, ctx)

        else:
            action = {
                "action": "wait",
                "target": None,
                "meta": {"reason": f"Mode '{mode}' not implemented – waiting."},
            }

    except Exception as e:
        print("Error in agent:", repr(e))
        action = {
            "action": "wait",
            "target": None,
            "meta": {"reason": "Agent error, falling back to safe wait."},
        }

    # FINAL SAFETY: for QUEST mode we *never* allow adjust_camera
    if mode == "quest" and action.get("action") == "adjust_camera":
        action["action"] = "wait"
        action["meta"] = {
            "reason": "adjust_camera disabled in quest mode; converted to wait."
        }

    # Ensure required keys exist
    action.setdefault("action", "wait")
    action.setdefault("target", None)
    action.setdefault("meta", {"reason": "No meta given"})

    return jsonify(action)


if __name__ == "__main__":
    # Default to same port you used before
    port = int(os.getenv("BRAIN_PORT", "9420"))
    app.run(host="127.0.0.1", port=port)