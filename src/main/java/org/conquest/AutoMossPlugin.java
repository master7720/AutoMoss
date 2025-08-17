package org.conquest;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;

public class AutoMossPlugin extends org.rusherhack.client.api.plugin.Plugin {

    @Override
    public void onLoad() {
        RusherHackAPI.getModuleManager().registerFeature(new AutoMossModule());
        this.getLogger().info("AutoMoss has loaded!");
    }

    @Override
    public void onUnload() {
        this.getLogger().info("AutoMoss has unloaded!");
    }

}