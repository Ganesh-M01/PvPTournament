package com.cherry.pvptournament;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.entity.Player;
import org.bukkit.Location;

public class PlayerListener implements Listener {

    private final PvPTournament plugin;
    private final TournamentManager manager;

    public PlayerListener(PvPTournament plugin) {
        this.plugin = plugin;
        this.manager = plugin.getTournamentManager();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();

        // Notify tournament manager (it will handle match elimination logic)
        manager.notifyDeath(dead.getUniqueId());

        // Schedule an automatic respawn a tick later and teleport to lobby.
        // Using a short delay so Minecraft has finished death handling.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                // auto-press respawn (Spigot API)
                dead.spigot().respawn();
            } catch (NoSuchMethodError ignored) {
                // In case API mismatch - still attempt to teleport on next tick
            }

            Location lobby = manager.getLobby();
            if (lobby != null && dead.isOnline()) {
                dead.teleport(lobby);
            }
        }, 2L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Location lobby = manager.getLobby();
        if (lobby != null) {
            event.setRespawnLocation(lobby);
        }
    }
}
