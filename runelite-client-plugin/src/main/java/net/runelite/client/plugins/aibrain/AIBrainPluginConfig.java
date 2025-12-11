package net.runelite.client.plugins.aibrain;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("aibrain")
public interface AIBrainPluginConfig extends Config
{
    @ConfigItem(
            keyName = "brainServerUrl",
            name = "Brain server URL",
            description = "Base URL of the local AI brain server (e.g. http://127.0.0.1:9420)",
            position = 1
    )
    default String brainServerUrl()
    {
        return "http://127.0.0.1:9420";
    }
}
