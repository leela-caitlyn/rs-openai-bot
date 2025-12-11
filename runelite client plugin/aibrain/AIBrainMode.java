package net.runelite.client.plugins.aibrain;

public enum AIBrainMode
{
    QUEST("Quest", "quest"),
    SKILL("Skill", "skill"),
    MANUAL("Manual", "manual");

    private final String displayName;
    private final String wireName;

    AIBrainMode(String displayName, String wireName)
    {
        this.displayName = displayName;
        this.wireName = wireName;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public String getWireName()
    {
        return wireName;
    }

    @Override
    public String toString()
    {
        // This is what appears in the mode dropdown in the panel
        return displayName;
    }
}
