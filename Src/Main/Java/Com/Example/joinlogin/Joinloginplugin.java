package com.example.joinlogin;

import org.bukkit.plugin.java.JavaPlugin;

public final class JoinLoginPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        AccountStore accounts = new AccountStore(this);
        getServer().getPluginManager().registerEvents(new JoinListener(this, accounts), this);
    }
}
