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
        view.setScale(MapView.Scale.FAR);
        view.setTrackingPosition(false);

        // Bigger crosshair arrow at center (64,64)
        view.addRenderer(new MapRenderer(false) {
            @Override
            public void render(MapView mapView, MapCanvas canvas, Player p) {
                byte green  = MapPalette.matchColor(0, 255, 0);
                byte white  = MapPalette.matchColor(255, 255, 255);
                byte black  = MapPalette.matchColor(0, 0, 0);
                int cx = 64, cy = 64;
                // White center block (3x3)
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = -1; dy <= 1; dy++)
                        canvas.setPixel(cx + dx, cy + dy, white);
                // Green arms (radius 2–4)
                for (int d = 2; d <= 4; d++) {
                    canvas.setPixel(cx - d, cy, green);
                    canvas.setPixel(cx + d, cy, green);
                    canvas.setPixel(cx, cy - d, green);
                    canvas.setPixel(cx, cy + d, green);
                }
                // Black tips at radius 5
                canvas.setPixel(cx - 5, cy, black);
                canvas.setPixel(cx + 5, cy, black);
                canvas.setPixel(cx, cy - 5, black);
                canvas.setPixel(cx, cy + 5, black);
            }
        });

        // Player dots — show where other players are (contextual, re-drawn each frame)
        view.addRenderer(new MapRenderer(true) {
            @Override
            public void render(MapView mapView, MapCanvas canvas, Player player) {
                byte yellow  = MapPalette.matchColor(255, 255, 85);
                byte red     = MapPalette.matchColor(255, 85, 85);
                int midX = mapView.getCenterX();
                int midZ = mapView.getCenterZ();
                for (Player other : player.getWorld().getPlayers()) {
                    if (other.getUniqueId().equals(player.getUniqueId())) continue;
                    Location oloc = other.getLocation();
                    int px = 64 + (oloc.getBlockX() - midX) / 8;
                    int pz = 64 + (oloc.getBlockZ() - midZ) / 8;
                    if (px < 0 || px >= 128 || pz < 0 || pz >= 128) continue;
                    // 2x2 dot so it's visible at FAR scale
                    byte color = other.isSneaking() ? red : yellow;
                    canvas.setPixel(px,     pz,     color);
                    if (px + 1 < 128) canvas.setPixel(px + 1, pz,     color);
                    if (pz + 1 < 128) canvas.setPixel(px,     pz + 1, color);
                    if (px + 1 < 128 && pz + 1 < 128) canvas.setPixel(px + 1, pz + 1, color);
                }
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
        MapView view = playerMaps.get(id);
        activePlayers.remove(id);
        playerMaps.remove(id);

        if (view == null) return;
        int targetId = view.getId();

        // Scan the ENTIRE inventory (main, armor, off-hand) and remove the
        // specific map we created. The player may have moved it out of the
        // off-hand into their backpack before closing, so we can't just
        // clear the off-hand slot.
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isOurMap(contents[i], targetId)) {
                inv.setItem(i, null);
            }
        }
    }

    private boolean isOurMap(ItemStack item, int targetId) {
        if (item == null || item.getType() != Material.FILLED_MAP) return false;
        if (!(item.getItemMeta() instanceof MapMeta meta)) return false;
        return meta.getMapId() == targetId;
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
