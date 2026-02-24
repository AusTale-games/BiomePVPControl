package games.austale.zonepvpcontrol;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.RefChangeSystem;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;

import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;
import com.hypixel.hytale.server.core.universe.world.worldgen.WorldGenLoadException;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import com.hypixel.hytale.server.worldgen.zone.Zone;
import com.hypixel.hytale.server.worldgen.zone.ZoneGeneratorResult;
import com.hypixel.hytale.server.worldgen.zone.ZonePatternGenerator;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;

import games.austale.zonepvpcontrol.commands.ExampleCommand;
import games.austale.zonepvpcontrol.events.ExampleEvent;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * ZonePVPControl plugin main class.
 */
public class ZonePVPControl extends JavaPlugin {

    // How often to scan player positions and update zone PvP flags.
    private static final long ZONE_POLL_SECONDS = 1L;
    // Zone group prefixes that allow PvP when config is missing.
    private static final Set<String> PVP_ZONE_GROUPS = Set.of("Zone2", "Zone3", "Zone4");

    // Cache of last known zone state per player.
    private final Map<UUID, ZoneState> lastZoneByPlayer = new ConcurrentHashMap<>();
    // Set of players who have finished loading and are ready for notifications.
    private final Set<UUID> readyPlayers = ConcurrentHashMap.newKeySet();
    // Scheduled task for periodic zone checks.
    private ScheduledFuture<?> zoneTask;
    // Configurable world allowlist/behavior.
    private ZonePvpControlConfig config;

    public ZonePVPControl(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        config = ZonePvpControlConfig.load(this);
        // Register example command and ready events used for client notifications.
        this.getCommandRegistry().registerCommand(new ExampleCommand("example", "An example command"));
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ExampleEvent::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        // ECS system enforces PvP rules at the damage layer.
        getEntityStoreRegistry().registerSystem(new ZonePvpDamageSystem(this));
        // ECS system applies PVP-specific drop rules on death.
        getEntityStoreRegistry().registerSystem(new ZonePvpDeathDropSystem(this));
    }

    /**
     * Called when the plugin is started.
     */
    public void start() {
        // Begin periodic polling of player zones.
        startZoneTracking();
    }

    /**
     * Called when the plugin is shut down.
     */
    public void shutdown() {
        // Clean-up code runs when plugin unloads
    }

    /**
     * Start the scheduled task for periodic zone checks.
     */
    private void startZoneTracking() {
        if (zoneTask != null) {
            return;
        }

        // Poll all worlds and run zone checks on each world thread.
        zoneTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            Universe universe = Universe.get();
            for (World world : universe.getWorlds().values()) {
                if (!world.isAlive()) {
                    continue;
                }

                if (!isWorldEnabled(world)) {
                    continue;
                }

                world.execute(() -> trackWorldZones(world));
            }
        }, ZONE_POLL_SECONDS, ZONE_POLL_SECONDS, TimeUnit.SECONDS);

        @SuppressWarnings("unchecked")
        ScheduledFuture<Void> task = (ScheduledFuture<Void>) zoneTask;
        getTaskRegistry().registerTask(task);
    }

    /**
     * Track player zones for a single world.
     *
     * @param world the world to track
     */
    private void trackWorldZones(World world) {
        // Use the world generator's zone pattern to determine PvP zones by position.
        if (!isWorldEnabled(world)) {
            return;
        }
        WorldConfig worldConfig = world.getWorldConfig();

        long worldSeed = worldConfig.getSeed();
        int seed = (int) worldSeed;
        IWorldGenProvider worldGenProvider = worldConfig.getWorldGenProvider();
        IWorldGen worldGen;

        try {
            worldGen = worldGenProvider.getGenerator();
        } catch (WorldGenLoadException e) {
            return;
        }

        if (!(worldGen instanceof ChunkGenerator chunkGenerator)) {
            return;
        }

        ZonePatternGenerator zoneGenerator = chunkGenerator.getZonePatternGenerator(seed);
        if (zoneGenerator == null) {
            return;
        }

        for (Player player : world.getPlayers()) {
            TransformComponent transform = player.getTransformComponent();
            if (transform == null) {
                continue;
            }

            UUID playerId = resolvePlayerId(player);
            if (playerId == null) {
                continue;
            }

            double x = transform.getPosition().getX();
            double z = transform.getPosition().getZ();
            ZoneState state = lastZoneByPlayer.computeIfAbsent(playerId, id -> new ZoneState());
            zoneGenerator.generate(seed, x, z, state.zoneResult);

            Zone zone = state.zoneResult.getZone();
            if (zone == null) {
                continue;
            }

            String zoneName = zone.name();
            boolean pvpEnabled = isPvpZone(zoneName);
            boolean playerReady = readyPlayers.contains(playerId);
            boolean wasPvpEnabled = state.pvpEnabled;
            boolean statusChanged = wasPvpEnabled != pvpEnabled;
            if (state.zoneName == null || !state.zoneName.equals(zoneName)) {
                state.zoneName = zoneName;
            }

            if (statusChanged) {
                state.pvpEnabled = pvpEnabled;
                if (playerReady) {
                    // Push a PvP status notification when the zone changes.
                    sendPvpNotification(player, pvpEnabled);
                }
                if (playerReady && wasPvpEnabled && !pvpEnabled && !isWorldPvpEnabled(worldConfig) && isOperator(playerId)) {
                    player.sendMessage(Message.raw("Warning: world PvP is disabled in config; PvP will remain off."));
                }
            }
        }
    }

    /**
     * Handle player ready event.
     *
     * @param event the player ready event
     */
    private void onPlayerReady(PlayerReadyEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        UUID playerId = resolvePlayerId(player);
        if (playerId == null) {
            return;
        }
        readyPlayers.add(playerId);
        ZoneState state = lastZoneByPlayer.get(playerId);
        if (state != null) {
            state.pvpEnabled = isPvpZone(state.zoneName);
            // Sync initial PvP status notification once the client is ready.
            sendPvpNotification(player, state.pvpEnabled);
        }
    }

    private UUID resolvePlayerId(Player player) {
        if (player == null) {
            return null;
        }
        PlayerRef ref = player.getPlayerRef();
        if (ref != null && ref.getUuid() != null) {
            return ref.getUuid();
        }
        return player.getUuid();
    }

    /**
     * Send a PvP status notification to a player.
     *
     * @param player    the player to notify
     * @param pvpEnabled whether PvP is enabled
     */
    private void sendPvpNotification(Player player, boolean pvpEnabled) {
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null) {
            return;
        }

        // Show a HUD notification reflecting PvP/PvE state.
        String label = pvpEnabled ? "PVP ENABLED" : "PVE ONLY";
        NotificationStyle style = pvpEnabled ? NotificationStyle.Danger : NotificationStyle.Success;
        NotificationUtil.sendNotification(playerRef.getPacketHandler(), Message.raw(label), style);
    }

    private boolean isWorldEnabled(World world) {
        if (config == null || world == null) {
            return true;
        }
        String key = resolveWorldKey(world);
        return config.isWorldEnabled(key);
    }

    private String resolveWorldKey(World world) {
        if (world == null) {
            return null;
        }
        Object name = tryInvoke(world, "getName");
        if (name instanceof String value && !value.isBlank()) {
            return value;
        }
        Object folder = tryInvoke(world, "getFolderName");
        if (folder instanceof String value && !value.isBlank()) {
            return value;
        }
        Object id = tryInvoke(world, "getId");
        if (id != null) {
            return id.toString();
        }
        return null;
    }

    private Object tryInvoke(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        for (java.lang.reflect.Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            try {
                return method.invoke(target, args);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Check if PvP is enabled for a player.
     *
     * @param playerId the player's UUID
     * @return whether PvP is enabled
     */
    private boolean isPvpEnabled(UUID playerId) {
        ZoneState state = lastZoneByPlayer.get(playerId);
        return state != null && state.pvpEnabled;
    }

    private String describeZone(UUID playerId) {
        if (playerId == null) {
            return "unknown";
        }
        ZoneState state = lastZoneByPlayer.get(playerId);
        if (state == null || state.zoneName == null) {
            return "unknown";
        }
        return state.zoneName + " (pvp=" + state.pvpEnabled + ")";
    }

    private boolean isWorldPvpEnabled(WorldConfig worldConfig) {
        if (worldConfig == null) {
            return true;
        }
        Object value = tryInvoke(worldConfig, "isPvpEnabled");
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        value = tryInvoke(worldConfig, "getIsPvpEnabled");
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        value = tryInvoke(worldConfig, "getPvpEnabled");
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return true;
    }

    private boolean isOperator(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        PermissionsModule perms = PermissionsModule.get();
        if (perms == null) {
            return false;
        }
        try {
            for (String group : perms.getGroupsForUser(playerId)) {
                if (group == null || group.trim().isEmpty()) {
                    continue;
                }
                String normalized = group.trim().toLowerCase();
                if ("op".equals(normalized) || "admin".equals(normalized) || "operator".equals(normalized)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private String resolveZoneGroupForPlayer(UUID playerId) {
        ZoneState state = lastZoneByPlayer.get(playerId);
        if (state == null || state.zoneName == null) {
            return null;
        }
        return resolveZoneGroup(state.zoneName);
    }

    private String resolveZoneGroup(String zoneName) {
        if (zoneName == null) {
            return null;
        }
        int underscoreIndex = zoneName.indexOf('_');
        return underscoreIndex == -1 ? zoneName : zoneName.substring(0, underscoreIndex);
    }

    /**
     * Check if a zone allows PvP.
     *
     * @param zoneName the zone name
     * @return whether PvP is allowed
     */
    private boolean isPvpZone(String zoneName) {
        String zoneGroup = resolveZoneGroup(zoneName);
        if (zoneGroup == null) {
            return false;
        }
        if (config != null) {
            return config.isPvpZoneEnabled(zoneGroup);
        }
        // Match by group prefix to allow multiple zone variants.
        return PVP_ZONE_GROUPS.contains(zoneGroup);
    }

    private void applyPvpDropRules(Ref<EntityStore> victimRef, DeathComponent deathComponent, Store<EntityStore> store) {
        if (config == null || victimRef == null || deathComponent == null || store == null) {
            return;
        }
        Player victim = store.getComponent(victimRef, Player.getComponentType());
        if (victim == null) {
            return;
        }

        Damage damage = deathComponent.getDeathInfo();
        if (damage == null || !(damage.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (attackerRef == null) {
            return;
        }
        Player attacker = store.getComponent(attackerRef, Player.getComponentType());
        if (attacker == null) {
            return;
        }

        UUID victimId = victim.getPlayerRef().getUuid();
        UUID attackerId = attacker.getPlayerRef().getUuid();
        if (!isPvpEnabled(victimId) || !isPvpEnabled(attackerId)) {
            return;
        }

        String zoneGroup = resolveZoneGroupForPlayer(victimId);
        ZonePvpControlConfig.PvpDropMode dropMode = config.getPvpDropMode(zoneGroup);
        if (dropMode == ZonePvpControlConfig.PvpDropMode.DEFAULT) {
            return;
        }

        if (dropMode == ZonePvpControlConfig.PvpDropMode.FULL) {
            deathComponent.setItemsLossMode(DeathConfig.ItemsLossMode.ALL);
            deathComponent.setItemsAmountLossPercentage(100.0);
            return;
        }

        if (dropMode == ZonePvpControlConfig.PvpDropMode.PARTIAL) {
            double amountLoss = clampPercent(config.getPvpPartialDropAmountPercent());
            double durabilityLoss = clampPercent(config.getPvpPartialDropDurabilityPercent());
            deathComponent.setItemsLossMode(DeathConfig.ItemsLossMode.CONFIGURED);
            deathComponent.setItemsAmountLossPercentage(amountLoss);
            deathComponent.setItemsDurabilityLossPercentage(durabilityLoss);
        }
    }

    private double clampPercent(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    /**
     * Cache of zone state per player.
     */
    private static final class ZoneState {
        // Cache zone query results per-player to avoid reallocations.
        private final ZoneGeneratorResult zoneResult = new ZoneGeneratorResult();
        private String zoneName;
        private boolean pvpEnabled;
    }

    /**
     * ECS system that configures drop behavior when a player is killed via PVP.
     */
    private static final class ZonePvpDeathDropSystem extends RefChangeSystem<EntityStore, DeathComponent> {
        private final ZonePVPControl plugin;

        private ZonePvpDeathDropSystem(ZonePVPControl plugin) {
            this.plugin = plugin;
        }

        @Override
        public ComponentType<EntityStore, DeathComponent> componentType() {
            return DeathComponent.getComponentType();
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void onComponentAdded(Ref<EntityStore> ref, DeathComponent component, Store<EntityStore> store,
                                     CommandBuffer<EntityStore> commandBuffer) {
            plugin.applyPvpDropRules(ref, component, store);
        }

        @Override
        public void onComponentSet(Ref<EntityStore> ref, DeathComponent previous, DeathComponent current,
                                   Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
            plugin.applyPvpDropRules(ref, current, store);
        }

        @Override
        public void onComponentRemoved(Ref<EntityStore> ref, DeathComponent component, Store<EntityStore> store,
                                       CommandBuffer<EntityStore> commandBuffer) {
            // No-op.
        }
    }

    /**
     * ECS system that enforces PvP rules at the damage layer.
     */
    private static final class ZonePvpDamageSystem extends DamageEventSystem {
        private final ZonePVPControl plugin;
        private static final long LOG_COOLDOWN_MS = 1500L;
        private static final ConcurrentHashMap<UUID, Long> LAST_LOG_MS = new ConcurrentHashMap<>();

        private ZonePvpDamageSystem(ZonePVPControl plugin) {
            this.plugin = plugin;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, Damage damage) {
            // Cancel player-vs-player damage unless both attacker and target are in PvP zones.
            Ref<EntityStore> targetRef = chunk.getReferenceTo(index);
            Player targetPlayer = store.getComponent(targetRef, Player.getComponentType());
            if (targetPlayer == null) {
                return;
            }

            if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) {
                return;
            }

            Ref<EntityStore> attackerRef = entitySource.getRef();
            Player attackerPlayer = store.getComponent(attackerRef, Player.getComponentType());
            if (attackerPlayer == null) {
                return;
            }

            UUID targetId = plugin.resolvePlayerId(targetPlayer);
            UUID attackerId = plugin.resolvePlayerId(attackerPlayer);
            if (targetId == null || attackerId == null) {
                return;
            }
            boolean targetPvp = plugin.isPvpEnabled(targetId);
            boolean attackerPvp = plugin.isPvpEnabled(attackerId);
            boolean blocked = !targetPvp || !attackerPvp;
            long now = System.currentTimeMillis();
            Long last = LAST_LOG_MS.get(attackerId);
            if (last == null || now - last >= LOG_COOLDOWN_MS) {
                LAST_LOG_MS.put(attackerId, now);
                System.err.println("[ZonePVPControl] PvP " + (blocked ? "BLOCK" : "ALLOW")
                        + " attacker=" + attackerId
                        + " target=" + targetId
                        + " attackerZone=" + plugin.describeZone(attackerId)
                        + " targetZone=" + plugin.describeZone(targetId)
                        + " attackerOp=" + plugin.isOperator(attackerId)
                        + " targetOp=" + plugin.isOperator(targetId));
            }
            if (blocked) {
                damage.setCancelled(true);
            }
        }
    }
}
