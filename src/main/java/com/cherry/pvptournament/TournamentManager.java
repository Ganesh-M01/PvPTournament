package com.cherry.pvptournament;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.*;

public class TournamentManager {

    private final PvPTournament plugin;

    private final LinkedHashSet<UUID> registered = new LinkedHashSet<>();
    private final Queue<UUID> queue = new ArrayDeque<>();
    private final List<UUID> winnersNextRound = new ArrayList<>();
    private final Set<UUID> aliveThisMatch = new HashSet<>();

    // Inventory/health/xp snapshots
    private final Map<UUID, ItemStack[]> savedContents = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new HashMap<>();
    private final Map<UUID, ItemStack>  savedOffhand = new HashMap<>();
    private final Map<UUID, Double>     savedHealth = new HashMap<>();
    private final Map<UUID, Integer>    savedFood = new HashMap<>();
    private final Map<UUID, Float>      savedExp = new HashMap<>();
    private final Map<UUID, Integer>    savedLevel = new HashMap<>();

    private boolean running = false;
    private Match currentMatch = null;
    private BukkitTask startCountdownTask;

    // Arena spawns
    private Location ARENA_1;
    private Location ARENA_2;

    // Lobby
    private Location lobby;

    // Scoreboard teams for nametag prefixes
    private Team redTeam;
    private Team blueTeam;

    // Name of current tournament (null if none created)
    private String currentTournament = null;

    public TournamentManager(PvPTournament plugin) {
        this.plugin = plugin;
        loadSpawnsFromConfig();
    }

    /* ===== Public API ===== */

    public void createTournament(String name, CommandSender creator) {
        if (currentTournament != null) {
            creator.sendMessage("§cA tournament is already created: " + currentTournament + ". Use /pvp start after you finish it.");
            return;
        }
        currentTournament = name;
        registered.clear();
        queue.clear();
        winnersNextRound.clear();
        aliveThisMatch.clear();
        running = false;
        Bukkit.broadcastMessage("§6⚔ A new tournament §e" + name + " §6has been created! Use §a/pvp join §6to participate.");
    }

    /** join only allowed if a tournament exists and hasn't started */
    public void join(Player player) {
        if (currentTournament == null) {
            player.sendMessage("§cNo tournament has been created. Wait for an admin to run /pvp create <name>.");
            return;
        }
        if (running) {
            player.sendMessage("§cA tournament is already running!");
            return;
        }
        if (!registered.add(player.getUniqueId())) {
            player.sendMessage("§eAlready joined.");
            return;
        }
        Bukkit.broadcastMessage("§6" + player.getName() + " §7joined §e" + currentTournament + " §7(§e" + registered.size() + "§7).");
        player.sendMessage("§aYou joined the tournament!");

        // teleport to lobby when they join
        sendToLobby(player);
    }

    public void leave(Player player) {
        UUID id = player.getUniqueId();
        boolean removed = registered.remove(id);
        removed |= queue.remove(id);
        removed |= winnersNextRound.remove(id);

        if (currentMatch != null && (currentMatch.p1.equals(id) || currentMatch.p2.equals(id))) {
            UUID winner = currentMatch.p1.equals(id) ? currentMatch.p2 : currentMatch.p1;
            handleWin(winner, id);
            removed = true;
        }

        if (savedContents.containsKey(id)) restoreSnapshot(player);
        clearTeamsFor(player);

        player.sendMessage(removed ? "§cLeft the tournament." : "§eNot in tournament.");
    }

    /** Start tournament. sender used to send errors/feedback (admin). */
    public void startTournament(CommandSender sender) {
        if (currentTournament == null) {
            sender.sendMessage("§cNo tournament has been created. Use /pvp create <name> first.");
            return;
        }
        if (running) {
            sender.sendMessage("§cTournament already running!");
            return;
        }
        if (registered.size() < 2) {
            Bukkit.broadcastMessage("§cNot enough players to start the tournament!");
            return;
        }

        running = true;
        queue.clear();
        winnersNextRound.clear();
        aliveThisMatch.clear();
        currentMatch = null;
        queue.addAll(registered);

        Bukkit.broadcastMessage("§6⚔ PvP Tournament §e" + currentTournament + " §6started with §e" + registered.size() + " §6players!");
        nextMatchOrRound();
    }

    /** Called by PlayerDeathEvent handler */
    public void notifyDeath(UUID dead) {
        if (!running || currentMatch == null) return;
        if (!aliveThisMatch.contains(dead)) return;
        UUID winner = currentMatch.p1.equals(dead) ? currentMatch.p2 : currentMatch.p1;
        handleWin(winner, dead);
    }

    /** Set spawn for 'red' or 'blue' based on player's current location and save to config */
    public void setSpawn(String team, Player setter) {
        Location loc = setter.getLocation();
        String path = team.equalsIgnoreCase("red") ? "red-spawn" : "blue-spawn";
        plugin.getConfig().set(path + ".world", loc.getWorld().getName());
        plugin.getConfig().set(path + ".x", loc.getX());
        plugin.getConfig().set(path + ".y", loc.getY());
        plugin.getConfig().set(path + ".z", loc.getZ());
        plugin.getConfig().set(path + ".yaw", (double) loc.getYaw());
        plugin.getConfig().set(path + ".pitch", (double) loc.getPitch());
        plugin.saveConfig();

        if (team.equalsIgnoreCase("red")) ARENA_1 = loc.clone();
        else ARENA_2 = loc.clone();
    }

    /** Set lobby location and save to config */
    public void setLobby(Location loc) {
        plugin.getConfig().set("lobby.world", loc.getWorld().getName());
        plugin.getConfig().set("lobby.x", loc.getX());
        plugin.getConfig().set("lobby.y", loc.getY());
        plugin.getConfig().set("lobby.z", loc.getZ());
        plugin.getConfig().set("lobby.yaw", (double) loc.getYaw());
        plugin.getConfig().set("lobby.pitch", (double) loc.getPitch());
        plugin.saveConfig();
        this.lobby = loc.clone();
    }

    /** Get lobby location (may be null if not set) */
    public Location getLobby() {
        if (this.lobby == null) {
            this.lobby = getLocationFromConfig("lobby");
            if (this.lobby == null) {
                // fallback to world spawn
                World w = Bukkit.getWorlds().get(0);
                if (w != null) this.lobby = w.getSpawnLocation().clone();
            }
        }
        return this.lobby;
    }

    /** Teleport a player to lobby if available */
    public void sendToLobby(Player p) {
        Location loc = getLobby();
        if (loc != null && p != null && p.isOnline()) {
            p.teleport(loc);
        }
    }

    /** Utility: list kit names from config */
    public List<String> getKitNames() {
        var section = plugin.getConfig().getConfigurationSection("kits");
        if (section == null) return List.of();
        return new ArrayList<>(section.getKeys(false));
    }

    /** Forcefully restore all snapshots and reset everything (used on plugin disable) */
    public void forceReset() {
        restoreAllSnapshots();
        clearAllTeams();
        resetTournament();
    }

    /* ===== Internal flow ===== */

    private void nextMatchOrRound() {
        if (currentMatch != null) return;

        while (queue.size() >= 2) {
            UUID u1 = queue.poll();
            UUID u2 = queue.poll();

            boolean on1 = isOnline(u1);
            boolean on2 = isOnline(u2);

            if (on1 && on2) {
                startMatch(u1, u2);
                return;
            } else {
                if (on1) queue.offer(u1);
                if (on2) queue.offer(u2);
            }
        }

        if (!winnersNextRound.isEmpty()) {
            queue.addAll(winnersNextRound);
            winnersNextRound.clear();
            Bukkit.broadcastMessage("§eNext round: §b" + queue.size() + " §eplayers remain.");
            nextMatchOrRound();
            return;
        }

        if (queue.size() == 1) {
            UUID champ = queue.poll();
            Player p = Bukkit.getPlayer(champ);
            Bukkit.broadcastMessage("§a🏆 " + (p != null ? p.getName() : "Unknown") + " wins the tournament §e" + currentTournament + "!");
            if (p != null) {
                restoreSnapshot(p);
                clearTeamsFor(p);
            }
            restoreAllSnapshots();
            clearAllTeams();
            resetTournament();
            return;
        }

        if (queue.isEmpty()) {
            restoreAllSnapshots();
            clearAllTeams();
            Bukkit.broadcastMessage("§cTournament ended unexpectedly. Resetting.");
            resetTournament();
        }
    }

    private void startMatch(UUID u1, UUID u2) {
        Player p1 = Bukkit.getPlayer(u1);
        Player p2 = Bukkit.getPlayer(u2);
        if (p1 == null || p2 == null) {
            if (p1 != null) queue.offer(u1);
            if (p2 != null) queue.offer(u2);
            nextMatchOrRound();
            return;
        }

        currentMatch = new Match(u1, u2);
        aliveThisMatch.clear();
        aliveThisMatch.add(u1);
        aliveThisMatch.add(u2);

        // Save snapshots
        saveSnapshot(p1);
        saveSnapshot(p2);

        // Reset basic player state
        p1.setFireTicks(0); p2.setFireTicks(0);
        p1.setFoodLevel(20); p2.setFoodLevel(20);
        p1.setHealth(Math.min(p1.getMaxHealth(), p1.getMaxHealth()));
        p2.setHealth(Math.min(p2.getMaxHealth(), p2.getMaxHealth()));

        // Teleport to configured arena spawns
        if (ARENA_1 != null) safeTeleport(p1, ARENA_1);
        if (ARENA_2 != null) safeTeleport(p2, ARENA_2);

        // Scoreboard/team tags
        ensureMatchTeams();
        addToTeam(p1, true);   // Red
        addToTeam(p2, false);  // Blue

        Bukkit.broadcastMessage("§bMatch: §f" + p1.getName() + " §7vs §f" + p2.getName() + " §7starting in §e3§7...");

        if (startCountdownTask != null) startCountdownTask.cancel();
        startCountdownTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int t = 3;
            @Override
            public void run() {
                if (!p1.isOnline() || !p2.isOnline()) {
                    startCountdownTask.cancel();
                    handleEarlyDisconnect();
                    return;
                }
                if (t == 0) {
                    p1.sendTitle("§aFight!", "", 0, 30, 10);
                    p2.sendTitle("§aFight!", "", 0, 30, 10);
                    startCountdownTask.cancel();
                } else {
                    String title = "§e" + t;
                    p1.sendTitle(title, "", 0, 20, 0);
                    p2.sendTitle(title, "", 0, 20, 0);
                    t--;
                }
            }
        }, 0L, 20L);
    }

    private void handleEarlyDisconnect() {
        if (currentMatch == null) return;
        UUID w = null;
        if (isOnline(currentMatch.p1) && !isOnline(currentMatch.p2)) w = currentMatch.p1;
        if (isOnline(currentMatch.p2) && !isOnline(currentMatch.p1)) w = currentMatch.p2;

        if (w != null) {
            UUID l = w.equals(currentMatch.p1) ? currentMatch.p2 : currentMatch.p1;
            handleWin(w, l);
        } else {
            currentMatch = null;
            nextMatchOrRound();
        }
    }

    private void handleWin(UUID winner, UUID loser) {
        Player wp = Bukkit.getPlayer(winner);
        Player lp = Bukkit.getPlayer(loser);

        Bukkit.broadcastMessage("§a" + nameOrUnknown(wp) + " §7defeated §c" + nameOrUnknown(lp) + ".");

        // Restore eliminated player's inventory and clear team tags
        if (lp != null) {
            restoreSnapshot(lp);
            clearTeamsFor(lp);

            // teleport eliminated player to lobby so they can spectate / rejoin queue
            sendToLobby(lp);
        }
        if (wp != null) {
            // Clear winner’s tag so next match can reassign correctly
            clearTeamsFor(wp);
        }
        cleanupTeamsIfEmpty();

        winnersNextRound.add(winner);
        currentMatch = null;
        aliveThisMatch.clear();
        nextMatchOrRound();
    }

    private void resetTournament() {
        running = false;
        currentMatch = null;
        if (startCountdownTask != null) {
            startCountdownTask.cancel();
            startCountdownTask = null;
        }
        queue.clear();
        winnersNextRound.clear();
        aliveThisMatch.clear();
        registered.clear();
        currentTournament = null;
    }

    /* ===== Inventory snapshots ===== */

    private void saveSnapshot(Player p) {
        UUID u = p.getUniqueId();
        if (savedContents.containsKey(u)) return;

        ItemStack[] cont = Arrays.stream(p.getInventory().getContents())
                .map(is -> is == null ? null : is.clone()).toArray(ItemStack[]::new);
        ItemStack[] armor = Arrays.stream(p.getInventory().getArmorContents())
                .map(is -> is == null ? null : is.clone()).toArray(ItemStack[]::new);
        ItemStack off = p.getInventory().getItemInOffHand();
        savedContents.put(u, cont);
        savedArmor.put(u, armor);
        savedOffhand.put(u, off == null ? null : off.clone());
        savedHealth.put(u, p.getHealth());
        savedFood.put(u, p.getFoodLevel());
        savedExp.put(u, p.getExp());
        savedLevel.put(u, p.getLevel());
    }

    private void restoreSnapshot(Player p) {
        UUID u = p.getUniqueId();
        if (!savedContents.containsKey(u)) return;
        p.getInventory().setContents(savedContents.get(u));
        p.getInventory().setArmorContents(savedArmor.get(u));
        p.getInventory().setItemInOffHand(savedOffhand.get(u));
        p.setHealth(Math.min(savedHealth.get(u), p.getMaxHealth()));
        p.setFoodLevel(savedFood.get(u));
        p.setExp(savedExp.get(u));
        p.setLevel(savedLevel.get(u));
        p.updateInventory();
        savedContents.remove(u);
        savedArmor.remove(u);
        savedOffhand.remove(u);
        savedHealth.remove(u);
        savedFood.remove(u);
        savedExp.remove(u);
        savedLevel.remove(u);
    }

    private void restoreAllSnapshots() {
        List<UUID> keys = new ArrayList<>(savedContents.keySet());
        for (UUID u : keys) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) restoreSnapshot(p);
        }
    }

    /* ===== Scoreboard teams for nametags ===== */

    private Scoreboard board() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private void ensureMatchTeams() {
        Scoreboard sb = board();
        if (sb == null) return;

        if (redTeam == null) {
            redTeam = sb.getTeam("pvpt_red");
            if (redTeam == null) redTeam = sb.registerNewTeam("pvpt_red");
            redTeam.prefix(Component.text("[Red] ").color(NamedTextColor.RED));
            redTeam.setCanSeeFriendlyInvisibles(true);
            redTeam.setAllowFriendlyFire(false);
        }
        if (blueTeam == null) {
            blueTeam = sb.getTeam("pvpt_blue");
            if (blueTeam == null) blueTeam = sb.registerNewTeam("pvpt_blue");
            blueTeam.prefix(Component.text("[Blue] ").color(NamedTextColor.BLUE));
            blueTeam.setCanSeeFriendlyInvisibles(true);
            blueTeam.setAllowFriendlyFire(false);
        }
    }

    private void addToTeam(Player p, boolean red) {
        ensureMatchTeams();
        if (p == null) return;
        String entry = p.getName();
        if (redTeam.hasEntry(entry)) redTeam.removeEntry(entry);
        if (blueTeam.hasEntry(entry)) blueTeam.removeEntry(entry);
        if (red) redTeam.addEntry(entry); else blueTeam.addEntry(entry);
    }

    private void clearTeamsFor(Player p) {
        if (p == null) return;
        String entry = p.getName();
        if (redTeam != null && redTeam.hasEntry(entry)) redTeam.removeEntry(entry);
        if (blueTeam != null && blueTeam.hasEntry(entry)) blueTeam.removeEntry(entry);
    }

    private void cleanupTeamsIfEmpty() {
        if (redTeam != null && redTeam.getEntries().isEmpty()) { redTeam.unregister(); redTeam = null; }
        if (blueTeam != null && blueTeam.getEntries().isEmpty()) { blueTeam.unregister(); blueTeam = null; }
    }

    private void clearAllTeams() {
        if (redTeam != null) { redTeam.unregister(); redTeam = null; }
        if (blueTeam != null) { blueTeam.unregister(); blueTeam = null; }
    }

    /* ===== Utils ===== */

    private boolean isOnline(UUID u) {
        Player p = Bukkit.getPlayer(u);
        return p != null && p.isOnline();
    }

    private String nameOrUnknown(Player p) {
        return p != null ? p.getName() : "Unknown";
    }

    private void safeTeleport(Player p, Location loc) {
        if (loc == null) return;
        if (loc.getWorld() == null) return;
        p.teleport(loc);
    }

    private record Match(UUID p1, UUID p2) {}

    /* ===== Config spawn helpers ===== */

    private void loadSpawnsFromConfig() {
        // red & blue spawn
        ARENA_1 = getLocationFromConfig("red-spawn");
        ARENA_2 = getLocationFromConfig("blue-spawn");

        // lobby
        lobby = getLocationFromConfig("lobby");

        // fallback: if null, use world spawn offsets
        World w = Bukkit.getWorlds().get(0);
        if (ARENA_1 == null && w != null) ARENA_1 = w.getSpawnLocation().clone().add(0.5, 0, 0.5);
        if (ARENA_2 == null && w != null) ARENA_2 = w.getSpawnLocation().clone().add(10.5, 0, 0.5);
        if (lobby == null && w != null) lobby = w.getSpawnLocation().clone();
    }

    private Location getLocationFromConfig(String path) {
        if (!plugin.getConfig().contains(path + ".world")) return null;
        String worldName = plugin.getConfig().getString(path + ".world", Bukkit.getWorlds().get(0).getName());
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        double x = plugin.getConfig().getDouble(path + ".x", world.getSpawnLocation().getX());
        double y = plugin.getConfig().getDouble(path + ".y", world.getSpawnLocation().getY());
        double z = plugin.getConfig().getDouble(path + ".z", world.getSpawnLocation().getZ());
        float yaw = (float) plugin.getConfig().getDouble(path + ".yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble(path + ".pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }
}
