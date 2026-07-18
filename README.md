# MinimapPlugin

A Bukkit/Paper plugin that provides a real-time minimap for Bedrock Edition players using the vanilla filled map item. Designed for Geyser servers with Dmenu integration.

## Features

- **BE Only** — Only shows to Bedrock players (via Dmenu `visible: be`)
- **Real-time** — Map updates every 5 ticks (0.25s), keeping the player centered at all times
- **Vanilla Map** — Uses Minecraft's built-in `FILLED_MAP` item with `CLOSEST` scale (1:1, 128×128 area)
- **Toggle via Menu** — Built for Dmenu clock menu integration; one button to enable/disable
- **Green Crosshair** — Player always at the exact center with a visible cursor
- **Dimension-Aware** — Automatically creates a new map when changing worlds
- **Respawn Safe** — Map is re-given after death

## Command

```
/minimap
```

Toggles the minimap on/off. Takes no arguments — just run it to switch.

## Dmenu Integration

Add this button to your Dmenu form (e.g. `tool.yml`):

```yaml
    # 小地图
    -
        name: §a小地图开关
        run: minimap
        type: cmd
        visible: be
        isOp: false

        Je:
            type: "map"
            lore:
                - "§7仅基岩版可用"

        Be:
            path: textures/items/map_filled.png
            url: https://avatars.githubusercontent.com/u/142202241
```

## Building

```bash
javac --release 21 -cp spigot-api-1.21.4.jar -d out \
  src/main/java/com/example/minimap/MinimapPlugin.java
jar cf MinimapPlugin.jar -C out . src/main/resources/plugin.yml
```

Requires Java 21+ and Paper/Spigot API 1.21.4+.

## Installation

1. Drop `MinimapPlugin.jar` into `plugins/`
2. Add the Dmenu button to your form config
3. Restart or reload the server
4. Bedrock players click the minimap button in the menu to toggle

## How It Works

The plugin creates a `MapView` per player at `CLOSEST` scale (128×128 blocks). A repeating task sets the map center to the player's position every 5 ticks. A custom `MapRenderer` draws a green crosshair at the center pixel. The map is placed in the player's offhand slot so they can still use tools in their main hand.

## License

MIT
