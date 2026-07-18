package com.example.minimap;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.Color;
import java.util.*;

/**
 * 自绘小地图。
 *
 * 关键设计（解决"比例尺不生效"+"拖尾"两个老 bug）：
 *  - 彻底移除原版地形渲染器，改为完全手动绘制。比例尺 = 每像素 BPP 格，由插件自己
 *    换算世界坐标 → 保证等级一定生效，不再受原版渲染器古怪缓存行为影响。
 *  - 每一帧都整屏重绘（先铺地形缓冲，再画玩家点/准星）。MapCanvas 是持久缓冲，
 *    只要每帧覆盖全部 128x128 像素，就不可能出现拖尾。
 *  - 已探索区域颜色持久缓存：走过/加载过的区块颜色会记住，未加载且没走过的用雾色，
 *    所以大比例尺下看到的是"逐步铺开的大地图"，而不是只剩眼前一小块。
 */
public class MinimapPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private static final int BPP = 4;          // blocks per pixel（等级2 / NORMAL：每像素 4 格，覆盖 512x512）
    private static final int SIZE = 128;        // 地图边长（像素）
    private static final int CTR = 64;          // 中心像素

    private final Set<UUID> activePlayers = new HashSet<>();
    private final Map<UUID, MapView> playerMaps = new HashMap<>();
    private final Map<UUID, byte[]> terrainBuf = new HashMap<>();      // 每帧铺底的地形缓冲
    private final Map<UUID, int[]> bufCenter = new HashMap<>();        // 该缓冲对应的世界中心坐标
    private final Map<UUID, Map<Long, Byte>> explored = new HashMap<>(); // 已探索列的持久颜色
    private BukkitTask updateTask;

    // 预计算好的调色板颜色（matchColor 较慢，做成常量避免每帧重算）
    private static final byte C_FOG    = MapPalette.matchColor(new Color(30, 33, 44));
    private static final byte C_GREEN  = MapPalette.matchColor(new Color(0, 255, 0));
    private static final byte C_WHITE  = MapPalette.matchColor(new Color(255, 255, 255));
    private static final byte C_BLACK  = MapPalette.matchColor(new Color(0, 0, 0));
    private static final byte C_YELLOW = MapPalette.matchColor(new Color(255, 235, 60));
    private static final byte C_RED    = MapPalette.matchColor(new Color(240, 60, 60));

    @Override
    public void onEnable() {
        getCommand("minimap").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        // 每 5 tick 重建一次地形缓冲；渲染器本身每 tick 跑（contextual）保证玩家点实时
        updateTask = getServer().getScheduler().runTaskTimer(this, this::rebuildAll, 1L, 5L);
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
        terrainBuf.clear();
        bufCenter.clear();
        explored.clear();
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

    // ---------------------------------------------------------------- 开启/关闭

    private void enableMinimap(Player player) {
        UUID id = player.getUniqueId();

        MapView view = Bukkit.createMap(player.getWorld());
        view.setScale(MapView.Scale.NORMAL);  // 仅装饰用，真正的比例尺由我们自己按 BPP 换算
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        try { view.setLocked(false); } catch (Throwable ignored) {}

        // 彻底移除原版地形渲染器，接管全部绘制
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(r);
        }

        explored.put(id, new HashMap<>());

        // contextual = true → 每 tick 都会为持有者重绘，玩家点实时；每帧整屏覆盖 → 无拖尾
        view.addRenderer(new MapRenderer(true) {
            @Override
            public void render(MapView mv, MapCanvas canvas, Player viewer) {
                renderMinimap(mv, canvas);
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

        // 立刻先铺一次地形，避免刚开启时是空白/雾
        rebuildTerrain(id, player);
    }

    private void removeMinimap(Player player) {
        UUID id = player.getUniqueId();
        MapView view = playerMaps.remove(id);
        activePlayers.remove(id);
        terrainBuf.remove(id);
        bufCenter.remove(id);
        explored.remove(id);

        if (view == null) return;
        int targetId = view.getId();

        // 扫描整个背包（主背包+盔甲+副手），移除我们创建的那张地图
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
        return meta.hasMapView() && meta.getMapId() == targetId;
    }

    // ---------------------------------------------------------------- 地形缓冲（每 5 tick）

    private void rebuildAll() {
        for (UUID id : new HashSet<>(activePlayers)) {
            Player player = getServer().getPlayer(id);
            if (player == null || !player.isOnline()) {
                activePlayers.remove(id);
                playerMaps.remove(id);
                terrainBuf.remove(id);
                bufCenter.remove(id);
                explored.remove(id);
                continue;
            }
            MapView view = playerMaps.get(id);
            if (view == null) continue;

            // 跨世界：重建地图（换世界后旧探索缓存作废）
            if (view.getWorld() == null || !view.getWorld().equals(player.getWorld())) {
                removeMinimap(player);
                enableMinimap(player);
                continue;
            }
            rebuildTerrain(id, player);
        }
    }

    private void rebuildTerrain(UUID id, Player p) {
        World w = p.getWorld();
        int cx = p.getLocation().getBlockX();
        int cz = p.getLocation().getBlockZ();

        byte[] buf = terrainBuf.computeIfAbsent(id, k -> new byte[SIZE * SIZE]);
        Map<Long, Byte> mem = explored.computeIfAbsent(id, k -> new HashMap<>());

        for (int px = 0; px < SIZE; px++) {
            for (int pz = 0; pz < SIZE; pz++) {
                int wx = cx + (px - CTR) * BPP;
                int wz = cz + (pz - CTR) * BPP;
                byte color;
                if (w.isChunkLoaded(wx >> 4, wz >> 4)) {
                    color = sampleColor(w, wx, wz);
                    mem.put(key(wx, wz), color);          // 记住这一列（持久探索）
                } else {
                    Byte remembered = mem.get(key(wx, wz));
                    color = (remembered != null) ? remembered : C_FOG;  // 走过的用记忆，没走过的用雾
                }
                buf[px + pz * SIZE] = color;
            }
        }
        bufCenter.put(id, new int[]{cx, cz});
    }

    /** 采样某一世界列的地表颜色（含高度明暗）。仅在区块已加载时调用。 */
    private byte sampleColor(World w, int wx, int wz) {
        Block top = w.getHighestBlockAt(wx, wz);
        Material m = top.getType();
        int y = top.getY();
        Color base = baseColor(m);

        // 高度明暗：越高越亮，越低越暗，突出地形起伏
        double f = 0.72 + Math.max(-0.22, Math.min(0.32, (y - 64) / 170.0));
        int r = clamp((int) (base.getRed() * f));
        int g = clamp((int) (base.getGreen() * f));
        int b = clamp((int) (base.getBlue() * f));
        return MapPalette.matchColor(new Color(r, g, b));
    }

    private java.awt.Color baseColor(Material m) {
        String n = m.name();
        if (m == Material.WATER)  return new Color(58, 92, 214);
        if (m == Material.LAVA)   return new Color(230, 90, 20);
        if (m == Material.GRASS_BLOCK || m == Material.MOSS_BLOCK
                || n.equals("SHORT_GRASS") || n.equals("TALL_GRASS") || n.contains("FERN"))
            return new Color(88, 150, 60);
        if (m == Material.SAND || m == Material.SANDSTONE) return new Color(219, 207, 150);
        if (m == Material.RED_SAND || m == Material.RED_SANDSTONE) return new Color(190, 102, 50);
        if (m == Material.GRAVEL) return new Color(150, 145, 140);
        if (m == Material.SNOW || m == Material.SNOW_BLOCK || m == Material.POWDER_SNOW)
            return new Color(245, 245, 250);
        if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE)
            return new Color(150, 180, 240);
        if (n.contains("LEAVES") || m == Material.VINE || m == Material.BAMBOO) return new Color(50, 110, 40);
        if (n.contains("LOG") || n.contains("WOOD") || n.contains("PLANKS") || n.contains("STEM"))
            return new Color(120, 90, 55);
        if (m == Material.DIRT || m == Material.COARSE_DIRT || m == Material.ROOTED_DIRT
                || m == Material.DIRT_PATH || m == Material.FARMLAND || m == Material.PODZOL)
            return new Color(120, 85, 58);
        if (m == Material.MYCELIUM) return new Color(110, 90, 100);
        if (m == Material.CLAY)     return new Color(160, 165, 175);
        if (m == Material.MUD)      return new Color(60, 52, 50);
        if (m == Material.OBSIDIAN) return new Color(25, 20, 40);
        if (m == Material.END_STONE) return new Color(220, 225, 160);
        if (m == Material.SOUL_SAND || m == Material.SOUL_SOIL) return new Color(80, 62, 50);
        if (m == Material.NETHERRACK || n.contains("NETHER")) return new Color(110, 40, 40);
        if (n.contains("DEEPSLATE")) return new Color(70, 70, 78);
        if (m == Material.STONE || m == Material.COBBLESTONE || m == Material.ANDESITE
                || m == Material.GRANITE || m == Material.DIORITE || m == Material.TUFF
                || m == Material.CALCITE || n.contains("STONE"))
            return new Color(128, 128, 128);
        return new Color(140, 140, 140); // 建筑/其它默认灰
    }

    // ---------------------------------------------------------------- 渲染（每 tick）

    private void renderMinimap(MapView mv, MapCanvas canvas) {
        UUID owner = ownerOf(mv);
        byte[] buf = (owner != null) ? terrainBuf.get(owner) : null;
        int[] c = (owner != null) ? bufCenter.get(owner) : null;
        Player op = (owner != null) ? getServer().getPlayer(owner) : null;

        // 缓冲还没建好：整屏铺雾（也是整屏覆盖，无残留）
        if (buf == null || c == null || op == null) {
            for (int px = 0; px < SIZE; px++)
                for (int pz = 0; pz < SIZE; pz++)
                    canvas.setPixel(px, pz, C_FOG);
            return;
        }

        // 1) 铺地形（整屏覆盖 → 彻底消除拖尾）
        for (int px = 0; px < SIZE; px++)
            for (int pz = 0; pz < SIZE; pz++)
                canvas.setPixel(px, pz, buf[px + pz * SIZE]);

        // 2) 其他玩家（黄点；潜行红点；带黑边更醒目）
        World w = op.getWorld();
        for (Player other : w.getPlayers()) {
            if (other.getUniqueId().equals(owner)) continue;
            Location oloc = other.getLocation();
            int px = CTR + Math.floorDiv(oloc.getBlockX() - c[0], BPP);
            int pz = CTR + Math.floorDiv(oloc.getBlockZ() - c[1], BPP);
            if (px < 2 || px > SIZE - 3 || pz < 2 || pz > SIZE - 3) continue;
            byte col = other.isSneaking() ? C_RED : C_YELLOW;
            // 黑色描边（3x3 外框）
            for (int d = -1; d <= 1; d++) {
                canvas.setPixel(px + d, pz - 2, C_BLACK);
                canvas.setPixel(px + d, pz + 2, C_BLACK);
                canvas.setPixel(px - 2, pz + d, C_BLACK);
                canvas.setPixel(px + 2, pz + d, C_BLACK);
            }
            // 3x3 实心点
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    canvas.setPixel(px + dx, pz + dz, col);

            // 玩家名字标签（点上方，垫同色底板 + 黑边，深棕字清晰可读）
            drawLabel(canvas, px, pz, other.getName(), col);
        }

        // 3) 中心准星 + 朝向箭头
        drawCrosshair(canvas, op.getLocation().getYaw());
    }

    private void drawCrosshair(MapCanvas canvas, float yaw) {
        int cx = CTR, cy = CTR;
        // 白色 3x3 中心
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                canvas.setPixel(cx + dx, cy + dy, C_WHITE);

        // 朝向箭头：沿视线方向伸出（短箭头，yaw:0=+Z 南, 90=-X 西）
        double yr = Math.toRadians(yaw);
        double fx = -Math.sin(yr);   // 世界 +X = 屏幕右
        double fz = Math.cos(yr);    // 世界 +Z = 屏幕下
        for (int d = 2; d <= 6; d++) {
            int ax = cx + (int) Math.round(fx * d);
            int ay = cy + (int) Math.round(fz * d);
            if (ax < 0 || ax >= SIZE || ay < 0 || ay >= SIZE) break;
            canvas.setPixel(ax, ay, d >= 5 ? C_BLACK : C_GREEN);
        }
    }

    /** 在点 (px,pz) 上方画玩家名字：同色底板 + 黑边 + 深棕字（Bukkit drawText 字体色固定，借底板提对比度）。 */
    private void drawLabel(MapCanvas canvas, int px, int pz, String name, byte plateColor) {
        MapFont font = MinecraftFont.Font;
        int w = font.getWidth(name);
        if (w <= 0) return;
        int h = font.getHeight() + 1;                 // 7px 字 + 1 行间距
        int tx = px - w / 2;
        int ty = pz - 3 - h;                          // 默认放点的上方
        if (ty < 0) ty = pz + 3;                      // 上方放不下就放下方
        if (tx < 0) tx = 0;
        if (tx + w > SIZE) tx = SIZE - w;

        // 底板（玩家色）+ 黑边
        for (int i = -1; i <= w; i++) {
            for (int j = -1; j <= h; j++) {
                int bx = tx + i, by = ty + j;
                if (bx < 0 || bx >= SIZE || by < 0 || by >= SIZE) continue;
                boolean border = (i == -1 || i == w || j == -1 || j == h);
                canvas.setPixel(bx, by, border ? C_BLACK : plateColor);
            }
        }
        // 默认字体色为深棕，画在亮底板上清晰可读
        canvas.drawText(tx, ty, font, name);
    }

    // ---------------------------------------------------------------- 工具

    private UUID ownerOf(MapView mv) {
        for (Map.Entry<UUID, MapView> e : playerMaps.entrySet()) {
            MapView v = e.getValue();
            if (v != null && v.getId() == mv.getId()) return e.getKey();
        }
        return null;
    }

    private static long key(int x, int z) {
        return (((long) x) & 0xFFFFFFFFL) << 32 | (z & 0xFFFFFFFFL);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ---------------------------------------------------------------- 事件

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
