package com.anta.director;

import com.anta.AntaConfig;
import com.anta.AntaMod;
import com.anta.entity.WatcherEntity;
import com.anta.network.AntaNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "The dead world": at a random moment, every creature in the Overworld disappears for one day.
 * No mobs, no animals, no villagers, nothing spawns, and the watcher does not come either. The sky
 * and the distance are lost in a medium grey fog (client side, synced by AntaNetwork). After one day
 * everything comes back exactly where it was (EntityVault): nothing is lost.
 *
 * Players, armor stands and the watcher are not "creatures" and stay. Server-side logic.
 */
@Mod.EventBusSubscriber(modid = AntaMod.MODID)
public final class DeadWorld extends SavedData {
    private static final String NAME = "anta_dead_world";
    public static final long DAY = 24000L;
    /** How often (ticks) the random start is rolled; chancePerDay is spread over the day. */
    private static final int ROLL_EVERY = 1200;

    private boolean active;
    private long startDayTime, startGameTime;
    private long lastEndGameTime = Long.MIN_VALUE;

    private DeadWorld() {}

    private static DeadWorld get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(DeadWorld::load, DeadWorld::new, NAME);
    }

    /** Whether the dead day is going on in this level (only ever in the Overworld). */
    public static boolean isActive(Level level) {
        if (!(level instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return false;
        DeadWorld d = sl.getDataStorage().get(DeadWorld::load, NAME);
        return d != null && d.active;
    }

    // ------------------------------------------------------------------ start / end

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD || level.getGameTime() % 20 != 0) return;
        DeadWorld d = get(level);
        if (d.active) {
            if (level.getDayTime() - d.startDayTime >= DAY || level.getGameTime() - d.startGameTime >= DAY) end(level);
            return;
        }
        if (level.getGameTime() % ROLL_EVERY != 0 || !AntaConfig.DEAD_WORLD.get()) return;
        if (!Director.worldChangesAllowed(level.getServer())) return;
        if (level.getGameTime() < AntaConfig.DEAD_WORLD_MIN_DAYS.get() * DAY) return;
        if (d.lastEndGameTime != Long.MIN_VALUE
                && level.getGameTime() - d.lastEndGameTime < AntaConfig.DEAD_WORLD_COOLDOWN_DAYS.get() * DAY) return;
        double perRoll = AntaConfig.DEAD_WORLD_CHANCE_PER_DAY.get() * ROLL_EVERY / DAY;
        if (level.getRandom().nextDouble() < perRoll) start(level);
    }

    /** Starts the dead day now. Returns how many creatures were taken away. */
    public static int start(ServerLevel overworld) {
        DeadWorld d = get(overworld);
        if (d.active) return 0;
        d.active = true;
        d.startDayTime = overworld.getDayTime();
        d.startGameTime = overworld.getGameTime();
        d.setDirty();

        EntityVault vault = EntityVault.get(overworld);
        List<Entity> roots = new ArrayList<>();
        for (Entity e : overworld.getAllEntities()) {
            if (e instanceof WatcherEntity) roots.add(e); // gone without a trace; never saved anyway
            else if (e.getVehicle() == null && takesAway(e)) roots.add(e);
        }
        int n = 0;
        for (Entity e : roots) {
            if (e.isRemoved()) continue;
            if (e instanceof WatcherEntity) {
                e.discard();
                continue;
            }
            try {
                if (vault.store(overworld, e, 0)) n++;
            } catch (RuntimeException ex) {
                AntaMod.LOGGER.error("Could not store {}, left in the world", e, ex);
            }
        }
        AntaNetwork.sendDeadWorld(overworld.getServer(), true);
        AntaMod.LOGGER.info("Dead world started: {} creatures stored", n);
        return n;
    }

    /** Ends the dead day; the vault puts everyone back within a second (as their chunks are loaded). */
    public static boolean end(ServerLevel overworld) {
        DeadWorld d = get(overworld);
        if (!d.active) return false;
        d.active = false;
        d.lastEndGameTime = overworld.getGameTime();
        d.setDirty();
        AntaNetwork.sendDeadWorld(overworld.getServer(), false);
        AntaMod.LOGGER.info("Dead world ended");
        return true;
    }

    /**
     * A root entity to take away: a creature (not a player, not an armor stand), or anything carrying
     * creatures (a boat with a villager). Never anything that carries a player.
     */
    private static boolean takesAway(Entity root) {
        boolean creature = false;
        for (Entity e : root.getPassengersAndSelf().toList()) {
            if (e instanceof Player) return false;
            if (isCreature(e)) creature = true;
        }
        return creature;
    }

    private static boolean isCreature(Entity e) {
        return e instanceof LivingEntity && !(e instanceof Player) && !(e instanceof ArmorStand)
                && !(e instanceof WatcherEntity);
    }

    // ------------------------------------------------------------------ keeping the world empty

    /**
     * During the dead day nothing enters the Overworld: new spawns (natural, spawners, breeding, eggs)
     * are cancelled; creatures loaded from disk with their chunk are stored in the vault instead, and come
     * back with everyone else.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (EntityVault.restoring || !(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (!isCreature(e) && !(e instanceof WatcherEntity)) return;
        if (!isActive(level)) return;
        if (event.loadedFromDisk()) {
            // Only single creatures; a mount with riders keeps its riders in its own data, handled whole.
            if (e.getVehicle() != null || !e.getPassengers().isEmpty()) return;
            try {
                if (!EntityVault.get(level).storeLoading(e)) return; // could not save it: let it in
            } catch (RuntimeException ex) {
                return;
            }
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) AntaNetwork.sendDeadWorld(p, isActive(p.server.overworld()));
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) AntaNetwork.sendDeadWorld(p, isActive(p.server.overworld()));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) AntaNetwork.sendDeadWorld(p, isActive(p.server.overworld()));
    }

    // ------------------------------------------------------------------ status, save

    public static String status(MinecraftServer server) {
        ServerLevel ow = server.overworld();
        DeadWorld d = get(ow);
        if (d.active) {
            long left = Math.max(0, DAY - Math.max(ow.getDayTime() - d.startDayTime, ow.getGameTime() - d.startGameTime));
            return String.format(Locale.ROOT, "dead world ACTIVE, %d s left, %d creatures stored",
                    left / 20, EntityVault.get(ow).size());
        }
        return String.format(Locale.ROOT, "dead world off%s | enabled %s, %.0f%% per day after day %d, cooldown %d days",
                Director.worldChangesAllowed(server) ? "" : " (not in multiplayer, see worldChangesInMultiplayer)",
                AntaConfig.DEAD_WORLD.get(), AntaConfig.DEAD_WORLD_CHANCE_PER_DAY.get() * 100,
                AntaConfig.DEAD_WORLD_MIN_DAYS.get(), AntaConfig.DEAD_WORLD_COOLDOWN_DAYS.get());
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("active", active);
        tag.putLong("startDay", startDayTime);
        tag.putLong("startGame", startGameTime);
        tag.putLong("lastEnd", lastEndGameTime);
        return tag;
    }

    private static DeadWorld load(CompoundTag tag) {
        DeadWorld d = new DeadWorld();
        d.active = tag.getBoolean("active");
        d.startDayTime = tag.getLong("startDay");
        d.startGameTime = tag.getLong("startGame");
        d.lastEndGameTime = tag.contains("lastEnd") ? tag.getLong("lastEnd") : Long.MIN_VALUE;
        return d;
    }
}
