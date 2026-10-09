package com.anta.director;

import com.anta.AntaConfig;
import com.anta.AntaMod;
import com.anta.cover.CoverFinder;
import com.anta.entity.WatcherEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * "Director": decides when the watcher appears for each player. Server-side only.
 *
 * Rules (see docs/DIRECTOR.md):
 *  - nothing during the first minutes of a play session (config graceSeconds);
 *  - one watcher per player at a time, always spawned out of the player's view, and only for that player;
 *  - the pause between appearances is random and shrinks the longer the player plays
 *    (tension), is shorter in the dark and longer in daylight under open sky;
 *  - each appearance in a session prefers a spot a bit closer than the last ("closer than before");
 *  - coming back to tunnels the player dug himself triggers an appearance at once (MineMemory);
 *  - once per world, after enough sightings: the finale (right behind the player);
 *  - if no cover is found, it retries soon; an unnoticed watcher leaves after a while;
 *  - when it is gone, sometimes a presence trace is left (Presence).
 *
 * Session state is kept in memory; sightings and one-off events are saved with the player.
 */
@Mod.EventBusSubscriber(modid = AntaMod.MODID)
public final class Director {
    public static final int TICKS_PER_MINUTE = 20 * 60;

    /** Tension: the pause shrinks linearly to TENSION_MIN_FACTOR over TENSION_FULL_TICKS of play. */
    public static final int TENSION_FULL_TICKS = 120 * TICKS_PER_MINUTE;
    public static final double TENSION_MIN_FACTOR = 0.5;
    /** Pause multipliers. */
    public static final double DAYLIGHT_FACTOR = 2.0;
    public static final double DARK_FACTOR = 0.75;
    /** Light level at the player's feet at or below which it counts as dark. */
    public static final int DARK_LIGHT = 6;
    /** Retry delay when no cover was found: random in [MIN, MAX]. */
    public static final int RETRY_MIN = 10 * 20;
    public static final int RETRY_MAX = 20 * 20;
    /** How long an unnoticed watcher stays: random in [MIN, MAX]. */
    public static final int LIFE_MIN = 20 * 20;
    public static final int LIFE_MAX = 40 * 20;
    /** Own mine: away from the dug tunnels at least this long, then coming back triggers an appearance. */
    public static final int MINE_AWAY_TICKS = 4 * TICKS_PER_MINUTE;
    /** Finale: how long it stands behind the player waiting to be turned to. */
    public static final int FINALE_LIFE = 60 * 20;

    /** Persisted (with the player, survives death and relog) keys. */
    public static final String KEY_SIGHTINGS = "anta_sightings";
    public static final String KEY_FINALE_DONE = "anta_finale_done";

    /** /anta director on|off (runtime switch on top of the config). */
    public static boolean enabled = true;
    /** /anta debug on|off until the server stops; null = use the config (debugMessages, off by default). */
    public static Boolean debugOverride = null;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static final class State {
        int played;            // ticks online in this session
        int nextAttempt;       // value of `played` at which the next appearance is tried
        UUID watcher;          // current watcher, null if none
        Vec3 watcherPos;       // where it stood
        ResourceKey<Level> watcherDim; // in which dimension
        boolean forced;        // /anta director now: skip grace and pause once
        boolean forceFinale;   // /anta finale
        int appearances;       // this session, for "closer than before"
        int lastInMine = -1;   // `played` when last inside own dug tunnels
    }

    private Director() {}

    public static int graceTicks() {
        return AntaConfig.GRACE_SECONDS.get() * 20;
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        State st = STATES.computeIfAbsent(player.getUUID(), id -> newState(player.getRandom()));
        st.played++;

        if (st.played % 20 == 0) checkOwnMine(player, st);

        if (st.watcher != null) {
            Entity e = player.serverLevel().getEntity(st.watcher);
            if (e != null && e.isAlive()) return;
            // It is gone (noticed, timed out, or the player changed dimension): schedule the next one.
            st.watcher = null;
            st.nextAttempt = st.played + nextPause(player, st);
            if (st.watcherPos != null && player.level().dimension().equals(st.watcherDim)) {
                try {
                    Presence.maybeLeave(player, st.watcherPos);
                } catch (RuntimeException ex) {
                    AntaMod.LOGGER.error("Presence trace failed, skipped", ex); // a trace must never take the server down
                }
            }
            st.watcherPos = null;
            return;
        }

        boolean on = enabled && AntaConfig.DIRECTOR.get();
        if (!on && !st.forced && !st.forceFinale) return;
        if (DeadWorld.isActive(player.serverLevel().getServer().overworld())) return; // the dead day: it does not come
        if (!st.forced && !st.forceFinale && (st.played < graceTicks() || st.played < st.nextAttempt)) return;
        if (player.isSpectator() || !player.isAlive() || player.isSleeping()) return;
        if (!st.forced && !st.forceFinale && AntaConfig.NOT_IN_DAYLIGHT.get() && inDaylight(player)) return;

        tryAppear(player, st);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    /** Command switches are per server run: a new world (or server start) begins with the config defaults. */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        STATES.clear();
        enabled = true;
        debugOverride = null;
        WatcherEntity.reactEnabled = true; WatcherEntity.forcedReaction = null;
    }

    private static State newState(RandomSource random) {
        State st = new State();
        st.nextAttempt = graceTicks() + between(random, 0, intervalMin());
        return st;
    }

    private static int intervalMin() {
        return AntaConfig.INTERVAL_MIN_SECONDS.get() * 20;
    }

    private static int intervalMax() {
        return Math.max(intervalMin(), AntaConfig.INTERVAL_MAX_SECONDS.get() * 20);
    }

    /** Own mine: coming back after a while to tunnels you dug yourself - it is already waiting there. */
    private static void checkOwnMine(ServerPlayer player, State st) {
        if (!AntaConfig.OWN_MINE.get() || !player.serverLevel().dimensionType().hasSkyLight()) return;
        if (!MineMemory.get(player.serverLevel()).isDugNear(player.blockPosition())) return;
        boolean cameBack = st.lastInMine >= 0 && st.played - st.lastInMine > MINE_AWAY_TICKS;
        st.lastInMine = st.played;
        if (cameBack && st.watcher == null && st.played >= graceTicks() && enabled && AntaConfig.DIRECTOR.get()) {
            st.forced = true;
            say(player, "director: you came back to your own tunnels");
        }
    }

    private static void tryAppear(ServerPlayer player, State st) {
        st.forced = false;
        ServerLevel level = player.serverLevel();
        RandomSource rnd = player.getRandom();

        if (st.forceFinale || finaleDue(player)) {
            boolean forced = st.forceFinale;
            st.forceFinale = false;
            if (tryFinale(player, st)) return;
            if (forced) say(player, "director: no room for the finale right behind you");
        }

        double preferred = preferredDistance(st);
        boolean cave = inCave(player);
        CoverFinder.Search search = cave ? CoverFinder.findAmbush(level, player)
                : CoverFinder.find(level, player, true, preferred);
        if (cave && search.best().isEmpty()) {
            search = CoverFinder.find(level, player, true, preferred); // no corner ahead: the usual out-of-view spot
            cave = false;
        }
        if (search.best().isEmpty()) {
            int retry = between(rnd, RETRY_MIN, RETRY_MAX);
            st.nextAttempt = st.played + retry;
            say(player, String.format(Locale.ROOT, "director: no cover here, retry in %d s", retry / 20));
            return;
        }
        CoverFinder.Result r = search.best().get();
        WatcherEntity w = spawnFor(player, r, false, between(rnd, LIFE_MIN, LIFE_MAX));
        if (w == null) return;
        w.setGlimpse(cave);
        st.watcher = w.getUUID();
        st.watcherPos = r.pos();
        st.watcherDim = level.dimension();
        st.appearances++;
        say(player, String.format(Locale.ROOT, "director: appeared%s%s, %.0f m away (prefers %.0f), lean %s, light %d (%d ms)",
                cave ? " (CAVE AMBUSH: around the corner ahead)" : "", w.isMirror() ? " (MIRROR)" : "",
                r.distance(), preferred, r.lean() > 0 ? "right" : "left", r.light(), search.stats().millis()));
    }

    /** Called by a watcher that moved to a new spot: track the new entity instead of scheduling the next one. */
    public static void onRelocated(ServerPlayer player, WatcherEntity moved) {
        State st = STATES.get(player.getUUID());
        if (st == null) return;
        st.watcher = moved.getUUID();
        st.watcherPos = moved.position();
    }

    /** Spawns a watcher meant for this player only (multiplayer), maybe wearing the player's skin. */
    public static WatcherEntity spawnFor(ServerPlayer player, CoverFinder.Result r, boolean glow, int life) {
        return WatcherEntity.spawnAt(player.serverLevel(), r.pos(), r.yaw(), r.lean(), glow, life,
                AntaConfig.OWN_WATCHER_ONLY.get() ? player.getUUID() : null,
                player.getRandom().nextDouble() < AntaConfig.MIRROR_CHANCE.get());
    }

    /** "Closer than before": 16 blocks at first, a step closer each appearance of this session. */
    private static double preferredDistance(State st) {
        if (!AntaConfig.CLOSER.get()) return CoverFinder.PREFERRED_DISTANCE;
        return Math.max(AntaConfig.CLOSER_MIN.get(),
                CoverFinder.PREFERRED_DISTANCE - st.appearances * AntaConfig.CLOSER_STEP.get());
    }

    // --- finale ---

    private static boolean finaleDue(ServerPlayer player) {
        if (!AntaConfig.FINALE.get()) return false;
        CompoundTag tag = persisted(player);
        if (tag.getBoolean(KEY_FINALE_DONE)) return false;
        if (tag.getInt(KEY_SIGHTINGS) < AntaConfig.FINALE_MIN_SIGHTINGS.get()) return false;
        return player.getRandom().nextDouble() < AntaConfig.FINALE_CHANCE.get();
    }

    /** Right behind the player's back, upright, facing him. Spawned only where he isn't looking. */
    private static boolean tryFinale(ServerPlayer player, State st) {
        ServerLevel level = player.serverLevel();
        // Never while the player is in danger: blindness + slowness must not get anyone killed.
        if (!player.onGround() || player.isInWater() || player.isInLava() || player.isOnFire()
                || player.isPassenger() || player.isFallFlying()) return false;
        Vec3 look = player.getLookAngle();
        Vec3 back = new Vec3(-look.x, 0, -look.z);
        if (back.lengthSqr() < 1e-4) return false;
        back = back.normalize();
        for (double d : new double[]{1.6, 2.2, 1.2}) {
            Vec3 p = player.position().add(back.scale(d));
            BlockPos feet = BlockPos.containing(p);
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos f = feet.above(dy);
                BlockPos below = f.below();
                if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
                if (!level.isLoaded(f)) continue;
                if (!level.getBlockState(f).getCollisionShape(level, f).isEmpty()) continue;
                if (!level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()) continue;
                if (!level.getFluidState(f).isEmpty() || !level.getFluidState(f.above()).isEmpty()) continue;
                Vec3 pos = new Vec3(p.x, f.getY(), p.z);
                Vec3 toPlayer = player.position().subtract(pos);
                float yaw = (float) Math.toDegrees(Math.atan2(toPlayer.z, toPlayer.x)) - 90f;
                WatcherEntity w = WatcherEntity.spawnAt(level, pos, yaw, 0f, false, FINALE_LIFE, player.getUUID(), false);
                if (w == null) return false;
                w.setFinale(true);
                st.watcher = w.getUUID();
                st.watcherPos = null; // no traces after the finale
                persisted(player).putBoolean(KEY_FINALE_DONE, true);
                say(player, "director: FINALE - it is right behind you");
                return true;
            }
        }
        return false;
    }

    /** Called by the watcher when a player notices it (or turns to it in the finale). */
    public static void onNoticed(ServerPlayer player, boolean finale) {
        CompoundTag tag = persisted(player);
        tag.putInt(KEY_SIGHTINGS, tag.getInt(KEY_SIGHTINGS) + 1);
    }

    /** Data saved with the player that also survives death (Forge copies PERSISTED_NBT_TAG). */
    public static CompoundTag persisted(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG)) root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    // --- helpers ---

    /**
     * Underground (or anywhere enclosed): no sky light reaches the player's eyes. Only in dimensions
     * that have sky light at all - in the Nether and the End sky light is 0 everywhere.
     */
    public static boolean inCave(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!level.dimensionType().hasSkyLight()) return false;
        return level.getBrightness(LightLayer.SKY, BlockPos.containing(player.getEyePosition())) == 0;
    }

    /**
     * Events that take something away (a torch disappears, a door opens, villagers vanish) only happen
     * in a world nobody else shares, unless the server owner allows them (worldChangesInMultiplayer).
     */
    public static boolean worldChangesAllowed(ServerPlayer player) {
        return worldChangesAllowed(player.getServer());
    }

    /** Same rule for world-wide events (the dead day): a world nobody else shares, or allowed by config. */
    public static boolean worldChangesAllowed(net.minecraft.server.MinecraftServer server) {
        if (AntaConfig.WORLD_CHANGES_IN_MULTIPLAYER.get()) return true;
        return server != null && !server.isDedicatedServer() && server.getPlayerCount() <= 1;
    }

    private static boolean inDaylight(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        return level.isDay() && level.canSeeSky(player.blockPosition());
    }

    /** Pause until the next appearance, counted from now. */
    private static int nextPause(ServerPlayer player, State st) {
        double pause = between(player.getRandom(), intervalMin(), intervalMax());
        double tension = Math.min(1.0, st.played / (double) TENSION_FULL_TICKS);
        pause *= 1.0 - tension * (1.0 - TENSION_MIN_FACTOR);
        pause *= lightFactor(player);
        return (int) pause;
    }

    /** Longer pause in daylight under open sky, shorter in the dark. */
    private static double lightFactor(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos feet = player.blockPosition();
        if (inDaylight(player)) return DAYLIGHT_FACTOR;
        if (level.getMaxLocalRawBrightness(feet) <= DARK_LIGHT) return DARK_FACTOR;
        return 1.0;
    }

    private static int between(RandomSource random, int min, int max) {
        return min + random.nextInt(Math.max(0, max - min) + 1);
    }

    /** Debug output for this player: switched on (config or /anta debug) and the player is an operator. */
    public static boolean debugFor(ServerPlayer player) {
        boolean on = debugOverride != null ? debugOverride : AntaConfig.DEBUG_MESSAGES.get();
        return on && player.hasPermissions(2);
    }

    /** Gray debug line in the player's chat (only with debug on, only for operators). */
    public static void say(ServerPlayer player, String text) {
        if (debugFor(player)) player.sendSystemMessage(Component.literal("[anta] " + text).withStyle(ChatFormatting.GRAY));
    }

    // --- used by /anta director and /anta finale ---

    /** Next appearance is tried on the next tick, ignoring grace and pause. */
    public static void forceNow(ServerPlayer player) {
        State st = STATES.computeIfAbsent(player.getUUID(), id -> newState(player.getRandom()));
        st.forced = true;
    }

    /** Next appearance is the finale (ignores "once per world" and sightings). */
    public static void forceFinale(ServerPlayer player) {
        State st = STATES.computeIfAbsent(player.getUUID(), id -> newState(player.getRandom()));
        st.forceFinale = true;
    }

    public static String status(ServerPlayer player) {
        State st = STATES.get(player.getUUID());
        if (st == null) return "director: no data yet";
        String next;
        if (st.watcher != null) next = "watcher is out now";
        else if (!enabled || !AntaConfig.DIRECTOR.get()) next = "director is OFF";
        else {
            int wait = Math.max(Math.max(graceTicks(), st.nextAttempt) - st.played, 0);
            next = String.format(Locale.ROOT, "next try in %d s", wait / 20);
        }
        CompoundTag tag = persisted(player);
        return String.format(Locale.ROOT,
                "director %s | played %d min | %s | light x%.2f | appearances %d, prefers %.0f m | sightings %d | finale %s",
                enabled && AntaConfig.DIRECTOR.get() ? "ON" : "OFF", st.played / TICKS_PER_MINUTE, next,
                lightFactor(player), st.appearances, preferredDistance(st), tag.getInt(KEY_SIGHTINGS),
                tag.getBoolean(KEY_FINALE_DONE) ? "done" : "not yet");
    }
}
