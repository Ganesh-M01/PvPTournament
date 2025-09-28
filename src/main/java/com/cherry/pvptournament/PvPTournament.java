package com.cherry.pvptournament;

import org.bukkit.plugin.java.JavaPlugin;

public final class PvPTournament extends JavaPlugin {

    private TournamentManager tournamentManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.tournamentManager = new TournamentManager(this);

        var cmd = getCommand("pvp");
        if (cmd != null) cmd.setExecutor(new PvpCommand(this));

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        getLogger().info("PvPTournament has been enabled!");
    }

    @Override
    public void onDisable() {
        if (tournamentManager != null) tournamentManager.forceReset();
        getLogger().info("PvPTournament has been disabled!");
    }

    public TournamentManager getTournamentManager() {
        return tournamentManager;
    }
}
