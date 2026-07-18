package com.example.minimap;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class MinimapPlugin extends JavaPlugin implements CommandExecutor, Listener {
    private final Set<UUID> activePlayers = new HashSet<>();
    private final Map<UUID, MapView> playerMaps = new HashMap<>();
    private BukkitTask updateTask;

    @Override
    public void onEnable() {
        getCommand("minimap").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        updateTask = getServer().getScheduler().runTaskTimer(this, this::updateAllMaps, 1L, 5L);
        getLogger().info("MinimapPlugin enabled!");
    }

    @Override
    public void onDisable() {
        if (updateTask != null) updateTask.cancel();
        for (UUID id : new HashSet<>(activePlayers)) {
            Player p = getServer().getPlayer(id);
            if (p != null) removeMinimap(p);
        }
        activePlayers.clear();
        playerMaps.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        UUID id = player.getUniqueId();
        if (activePlayers.contains(id)) {
            removeMinimap(player);
            player.sendMessage("§c小地图 §7已关闭");
        } else {
            enableMinimap(player);
            player.sendMessage("§a小地图 §7已开启");
        }
        return true;
    }

    private void enableMinimap(Player player) {
        UUID id = player.getUniqueId();

        MapView view = Bukkit.createMap(player.getWorld());
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);

        // Green crosshair cursor at center (64,64)
        view.addRenderer(new MapRenderer(false) {
            @Override
            public void render(MapView mapView, MapCanvas canvas, Player p) {
                byte green  = MapPalette.matchColor(0, 255, 0);
                byte white  = MapPalette.matchColor(255, 255, 255);
                byte black  = MapPalette.matchColor(0, 0, 0);
                int cx = 64, cy = 64;
                canvas.setPixel(cx,   cy,   white);
                canvas.setPixel(cx-1, cy,   green);
                canvas.setPixel(cx+1, cy,   green);
                canvas.setPixel(cx,   cy-1, green);
                canvas.setPixel(cx,   cy+1, green);
                canvas.setPixel(cx-2, cy,   black);
                canvas.setPixel(cx+2, cy,   black);
                canvas.setPixel(cx,   cy-2, black);
                canvas.setPixel(cx,   cy+2, black);
            }
        });

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        meta.setDisplayName("§a小地图");
        mapItem.setItemMeta(meta);

        player.getInventory().setItemInOffHand(mapItem);

        playerMaps.put(id, view);
        activePlayers.add(id);
    }

    private void removeMinimap(Player player) {
        UUID id = player.getUniqueId();
        activePlayers.remove(id);
        playerMaps.remove(id);

        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() == Material.FILLED_MAP) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    private void updateAllMaps() {
        for (UUID id : new HashSet<>(activePlayers)) {
            Player player = getServer().getPlayer(id);
            if (player == null || !player.isOnline()) {
                activePlayers.remove(id);
                playerMaps.remove(id);
                continue;
            }

            MapView view = playerMaps.get(id);
            if (view == null) continue;

            if (!view.getWorld().equals(player.getWorld())) {
                removeMinimap(player);
                enableMinimap(player);
                continue;
            }

            Location loc = player.getLocation();
            view.setCenterX(loc.getBlockX());
            view.setCenterZ(loc.getBlockZ());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeMinimap(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (activePlayers.contains(id)) {
            getServer().getScheduler().runTaskLater(this, () -> {
                Player p = event.getPlayer();
                if (p.isOnline() && activePlayers.contains(p.getUniqueId())) {
                    enableMinimap(p);
                }
            }, 1L);
        }
    }
}
