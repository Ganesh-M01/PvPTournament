package com.cherry.pvptournament;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PvpCommand implements CommandExecutor {

    private final PvPTournament plugin;
    private final TournamentManager manager;

    public PvpCommand(PvPTournament plugin) {
        this.plugin = plugin;
        this.manager = plugin.getTournamentManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eUsage: /pvp <create|join|leave|start|setspawn|setlobby|leaderboard|kits>");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "create" -> {
                if (!sender.hasPermission("pvptournament.create")) {
                    sender.sendMessage("§cYou don't have permission.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§eUsage: /pvp create <name>");
                    return true;
                }
                String name = args[1];
                manager.createTournament(name, sender);
            }

            case "join" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cOnly players can join."); return true; }
                manager.join(p);
            }

            case "leave" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cOnly players can leave."); return true; }
                manager.leave(p);
            }

            case "start" -> {
                if (!sender.hasPermission("pvptournament.start")) {
                    sender.sendMessage("§cYou don't have permission to start tournaments.");
                    return true;
                }
                manager.startTournament(sender);
            }

            case "setspawn" -> {
                if (!sender.hasPermission("pvptournament.setspawn")) {
                    sender.sendMessage("§cYou don't have permission to set spawns.");
                    return true;
                }
                if (!(sender instanceof Player p)) { sender.sendMessage("§cOnly players can set spawns in-game."); return true; }
                if (args.length < 2) { sender.sendMessage("§eUsage: /pvp setspawn <red|blue>"); return true; }
                String team = args[1].toLowerCase();
                if (!team.equals("red") && !team.equals("blue")) {
                    sender.sendMessage("§eUsage: /pvp setspawn <red|blue>");
                    return true;
                }
                manager.setSpawn(team, p);
                sender.sendMessage("§aSet " + team + " spawn to your current location.");
            }

            case "setlobby" -> {
                if (!sender.hasPermission("pvptournament.setlobby")) {
                    sender.sendMessage("§cYou don't have permission to set the lobby.");
                    return true;
                }
                if (!(sender instanceof Player p)) { sender.sendMessage("§cOnly players can set the lobby in-game."); return true; }
                manager.setLobby(p.getLocation());
                sender.sendMessage("§aLobby spawn set to your current location.");
            }

            case "leaderboard" -> {
                sender.sendMessage("§aLeaderboard not yet implemented.");
            }

            case "kits" -> {
                sender.sendMessage("§aConfigured kits: " + String.join(", ", manager.getKitNames()));
            }

            default -> sender.sendMessage("§cUnknown subcommand. Usage: /pvp <create|join|leave|start|setspawn|setlobby|leaderboard|kits>");
        }

        return true;
    }
}
