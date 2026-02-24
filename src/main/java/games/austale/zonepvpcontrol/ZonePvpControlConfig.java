package games.austale.zonepvpcontrol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import games.austale.zonepvpcontrol.util.DataPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ZonePvpControlConfig {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName(value = "restrict_to_worlds", alternate = {"restrictToWorlds"})
    private boolean restrictToWorlds = false;

    @SerializedName(value = "enabled_worlds", alternate = {"enabledWorlds"})
    private List<String> enabledWorlds = new ArrayList<>();

    // Per-zone-group PVP enable/disable control.
    @SerializedName(value = "pvp_zone_enabled", alternate = {"pvpZoneEnabled"})
    private Map<String, Boolean> pvpZoneEnabled = new HashMap<>(Map.of(
            "Zone1", false,
            "Zone2", true,
            "Zone3", true,
            "Zone4", true
    ));

    // Per-zone-group PVP drop mode: FULL or PARTIAL. Missing entries mean no override.
    @SerializedName(value = "pvp_zone_drop_modes", alternate = {"pvpZoneDropModes"})
    private Map<String, String> pvpZoneDropModes = new HashMap<>(Map.of(
            "Zone1", "FULL",
            "Zone2", "FULL",
            "Zone3", "FULL",
            "Zone4", "FULL"
    ));

    // Percentage of items lost when PARTIAL drop mode is used.
    @SerializedName(value = "pvp_partial_drop_amount_percent", alternate = {"pvpPartialDropAmountPercent"})
    private double pvpPartialDropAmountPercent = 50.0;

    // Percentage of durability loss when PARTIAL drop mode is used.
    @SerializedName(value = "pvp_partial_drop_durability_percent", alternate = {"pvpPartialDropDurabilityPercent"})
    private double pvpPartialDropDurabilityPercent = 0.0;

    public boolean isRestrictToWorlds() {
        return restrictToWorlds;
    }

    public boolean isWorldEnabled(String worldKey) {
        if (!restrictToWorlds) {
            return true;
        }
        if (worldKey == null || enabledWorlds == null) {
            return false;
        }
        for (String allowed : enabledWorlds) {
            if (allowed != null && allowed.equalsIgnoreCase(worldKey)) {
                return true;
            }
        }
        return false;
    }

    public boolean isPvpZoneEnabled(String zoneGroup) {
        if (zoneGroup == null || pvpZoneEnabled == null) {
            return false;
        }
        for (Map.Entry<String, Boolean> entry : pvpZoneEnabled.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(zoneGroup)) {
                return Boolean.TRUE.equals(entry.getValue());
            }
        }
        return false;
    }

    public PvpDropMode getPvpDropMode(String zoneGroup) {
        if (zoneGroup == null || pvpZoneDropModes == null) {
            return PvpDropMode.DEFAULT;
        }
        for (Map.Entry<String, String> entry : pvpZoneDropModes.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(zoneGroup)) {
                return PvpDropMode.fromString(entry.getValue());
            }
        }
        return PvpDropMode.DEFAULT;
    }

    public double getPvpPartialDropAmountPercent() {
        return pvpPartialDropAmountPercent;
    }

    public double getPvpPartialDropDurabilityPercent() {
        return pvpPartialDropDurabilityPercent;
    }

    public enum PvpDropMode {
        FULL,
        PARTIAL,
        DEFAULT;

        public static PvpDropMode fromString(String value) {
            if (value == null) {
                return DEFAULT;
            }
            String normalized = value.trim().toUpperCase();
            if ("FULL".equals(normalized)) {
                return FULL;
            }
            if ("PARTIAL".equals(normalized)) {
                return PARTIAL;
            }
            return DEFAULT;
        }
    }

    public static ZonePvpControlConfig load(JavaPlugin plugin) {
        Path dataPath = DataPaths.resolveDataPath(plugin);
        Path configPath = dataPath.resolve("config.json");
        if (Files.exists(configPath)) {
            try (BufferedReader reader = Files.newBufferedReader(configPath)) {
                ZonePvpControlConfig config = GSON.fromJson(reader, ZonePvpControlConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (Exception ex) {
                LOGGER.atWarning().withCause(ex).log("Failed to read ZonePVPControl config. Using defaults.");
            }
        }

        ZonePvpControlConfig defaults = new ZonePvpControlConfig();
        defaults.save(configPath);
        return defaults;
    }

    private void save(Path configPath) {
        try (BufferedWriter writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(this, writer);
        } catch (Exception ex) {
            LOGGER.atWarning().withCause(ex).log("Failed to save ZonePVPControl config.");
        }
    }
}
