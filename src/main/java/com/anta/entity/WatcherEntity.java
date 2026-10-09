package com.anta.entity;

import com.anta.anim.WatcherPose;
import com.anta.cover.CoverFinder;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/**
 * The antagonist: stands still behind cover, leans sideways by {@link #getLean()} degrees
 * (positive = to its right) and peeks. Its body never moves; only the head turns toward the
 * viewer (done client-side in WatcherModel, so in multiplayer each player sees it look at them).
 *
 * Version 2 behaviour: when a player looks at its head ({@link #NOTICE_HALF_ANGLE} cone + clear
 * line of sight), it straightens up behind the cover within {@link #HIDE_TICKS} ticks and then
 * vanishes {@link #VANISH_TICKS} ticks after the moment it was noticed.
 */
public class WatcherEntity extends PathfinderMob {
    private static final EntityDataAccessor<Float> LEAN =
            SynchedEntityData.defineId(WatcherEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> HIDING =
            SynchedEntityData.defineId(WatcherEntity.class, EntityDataSerializers.BOOLEAN);
    /** The player it came for. Empty = everyone (debug / ownWatcherOnly off). Others don't see it. */
    private static final EntityDataAccessor<Optional<UUID>> TARGET =
            SynchedEntityData.defineId(WatcherEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    /** "Reflection": rendered with the viewer's own skin, head stays empty. */
    private static final EntityDataAccessor<Boolean> MIRROR =
            SynchedEntityData.defineId(WatcherEntity.class, EntityDataSerializers.BOOLEAN);

    /** Finale: "turned around" = its head within this angle of the look direction. */
    public static final double FINALE_ANGLE = 45.0;

    /** Half-angle of the "player is looking at it" cone, in degrees. */
    public static final double NOTICE_HALF_ANGLE = WatcherPose.NOTICE_HALF_ANGLE;
    /** Players farther than this are ignored. */
    public static final double NOTICE_RANGE = 48.0;
    /** Ticks after spawning during which it does not react (so /anta test any can be seen). */
    public static final int ARM_DELAY_TICKS = 10;
    /** Ticks to straighten up behind the cover (see {@link WatcherPose#hideLean}). */
    public static final int HIDE_TICKS = WatcherPose.HIDE_TICKS;
    /** Ticks from being noticed until it is removed. */
    public static final int VANISH_TICKS = HIDE_TICKS + 2;
    /** Unnoticed and its head has had no line to the viewer this long (you walked on): it moves to a new spot. */
    public static final int RELOCATE_TICKS = 60;
    /** At most this many moves per appearance. */
    public static final int MAX_RELOCATIONS = 4;
    /** A player this close makes it hide even without looking at it. */
    public static final double TOO_CLOSE = 5.0;
    /** With no player within this range it is removed. */
    public static final double LOST_RANGE = 64.0;
    /** Glimpse mode: "on screen" means within this angle of the look direction (screen edge at FOV 70 is ~50°). */
    public static final double GLIMPSE_ANGLE = 50.0;
    /** Glimpse mode: ticks on screen before it ducks away (5 ticks = 0.25 s). */
    public static final int GLIMPSE_TICKS = 5;

    /** Debug switch for /anta react on|off: when false it never hides (to look at it calmly). */
    public static boolean reactEnabled = true;
    /** Debug (/anta react stare|...): every look gets this reaction; null = the brain decides. Reset on server start. */
    public static Reaction forcedReaction = null;

    // --- "Brain": how it reacts when you look at it is not always the same (see Reaction). ---
    /** STARE: it keeps looking back at you this long before it ducks (1-2 s). */
    public static final int STARE_MIN_TICKS = 20, STARE_MAX_TICKS = 40;
    /** WAIT: it does not move while you look; gives up after this long and slowly retreats. */
    public static final int WAIT_MAX_TICKS = 100;
    /** STARE / WAIT: off your screen this many ticks in a row = it is gone when you look back. */
    public static final int LOOK_AWAY_TICKS = 2;
    /** RETURN: hidden this long (random) before it peeks out once more from the same spot. */
    public static final int RETURN_MIN_TICKS = 40, RETURN_MAX_TICKS = 100;
    /** RETURN: if it could not come back unseen within this many ticks, it leaves. */
    public static final int RETURN_GIVE_UP_TICKS = 200;
    /** Saved with the player: the last reaction, so an unusual one is never repeated twice in a row. */
    public static final String KEY_LAST_REACTION = "anta_last_reaction";

    /** What it does when it is looked at directly. */
    public enum Reaction {
        /** The usual: jerks back behind the cover at once. */
        SNAP,
        /** Holds still and looks back at you for 1-2 s, then jerks back; look away and it is gone. */
        STARE,
        /** No jerk: calmly straightens up behind the cover over 1.5 s, as if it is not afraid of you. */
        SLOW,
        /** Does not react at all while you look; the moment you look away it is gone. */
        WAIT,
        /** Jerks back, and a few seconds later peeks out once more from the same spot. */
        RETURN
    }

    private enum Phase { WATCHING, STARING, HIDING, AWAY }

    private Phase phase = Phase.WATCHING;
    private Reaction reaction = Reaction.SNAP;
    /** Server: the player whose look started the current reaction. */
    private UUID reactingTo;
    /** Server: STARE / WAIT end at this tick; RETURN comes back at this tick. */
    private int phaseUntil;
    private int offScreenTicks;
    /** Server: tick at which the current peek-out started (0 at spawn, later after a RETURN). */
    private int peekStart = 0;
    /** Server: already came back once - the next look always makes it jerk away for good. */
    private boolean returned = false;
    /** Ticks the hide animation takes for the current hide (fast jerk or slow retreat). */
    private int vanishTicks = 6;
    private static final EntityDataAccessor<Boolean> SLOW_HIDE =
            SynchedEntityData.defineId(WatcherEntity.class, EntityDataSerializers.BOOLEAN);

    /** Server: tick count at which it was noticed, -1 if not yet. */
    private int noticedAt = -1;
    /** Server: removed quietly after this many ticks if nobody noticed it; -1 = stays (debug spawns). */
    private int lifeTicks = -1;
    /**
     * Server: cave ambush ("glimpse") mode - it also hides once its head has been on the player's
     * screen for {@link #GLIMPSE_TICKS}, even at the edge, so it is only ever seen for a moment.
     */
    private boolean glimpse = false;
    private boolean finale = false;
    private int seenTicks = 0;
    /** Client: tick count at which the HIDING flag arrived, -1 if not hiding. */
    private int hideStartClient = -1;
    /** Client (WatcherModel): delayed head angles and the time they were last updated. */
    public float clientHeadYaw, clientHeadPitch, clientHeadTime = -1f;
    public final boolean[] clientHeadTurningYaw = {false}, clientHeadTurningPitch = {false};

    public WatcherEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setNoAi(true);
        setInvulnerable(true);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(LEAN, 0f);
        entityData.define(HIDING, false);
        entityData.define(TARGET, Optional.empty());
        entityData.define(MIRROR, false);
        entityData.define(SLOW_HIDE, false);
    }

    public Optional<UUID> getViewer() {
        return entityData.get(TARGET);
    }

    public void setViewer(UUID player) {
        entityData.set(TARGET, Optional.ofNullable(player));
    }

    public boolean isMirror() {
        return entityData.get(MIRROR);
    }

    public void setMirror(boolean on) {
        entityData.set(MIRROR, on);
    }

    /** Finale mode (server): stands right behind the player; when turned to, blinds and vanishes. */
    public void setFinale(boolean on) {
        finale = on;
    }

    /** Whether this player is one it reacts to (its target, or anyone when it has none). */
    public boolean isFor(ServerPlayer p) {
        Optional<UUID> t = getViewer();
        return t.isEmpty() || t.get().equals(p.getUUID());
    }

    public float getLean() {
        return entityData.get(LEAN);
    }

    public void setLean(float degrees) {
        entityData.set(LEAN, degrees);
    }

    public boolean isHiding() {
        return entityData.get(HIDING);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (HIDING.equals(key) && level().isClientSide) {
            if (isHiding()) {
                hideStartClient = tickCount;
            } else if (hideStartClient >= 0) {
                // RETURN: it comes out again - a fresh slow peek from upright.
                hideStartClient = -1;
                peekStartClient = tickCount;
            }
        }
    }

    /** Client: tick count at which the current peek-out started. */
    private int peekStartClient = 0;

    /**
     * Lean to draw this frame (client-side): the slow peek-out after spawning, then the jerk
     * back (or the slow retreat) from wherever the peek was when it got noticed. getLean() is the target lean.
     */
    public float getRenderLean(float partialTick) {
        float lean = getLean();
        if (hideStartClient < 0) return WatcherPose.peekLean(lean, tickCount + partialTick - peekStartClient);
        float from = WatcherPose.peekLean(lean, hideStartClient - peekStartClient);
        float t = tickCount + partialTick - hideStartClient;
        return entityData.get(SLOW_HIDE) ? WatcherPose.slowHideLean(from, t) : WatcherPose.hideLean(from, t);
    }

    /** Backward recoil to draw this frame (client-side), only while jerking back. */
    public float getRenderRecoil(float partialTick) {
        if (hideStartClient < 0 || entityData.get(SLOW_HIDE)) return 0f;
        return WatcherPose.hideRecoil(tickCount + partialTick - hideStartClient);
    }

    /** Server: the lean the head has right now (peek-out in progress or done; 0 once hidden). */
    private float currentLean() {
        if (isHiding()) return 0f;
        return WatcherPose.peekLean(getLean(), tickCount - peekStart);
    }

    /**
     * Spawns a watcher at a spot found by {@link CoverFinder}.
     *
     * @param glow      outline visible through walls (debug spawns only)
     * @param lifeTicks removed quietly after this many ticks if not noticed; -1 = stays
     */
    public static WatcherEntity spawn(ServerLevel level, CoverFinder.Result r, boolean glow, int lifeTicks) {
        return spawnAt(level, r.pos(), r.yaw(), r.lean(), glow, lifeTicks, null, false);
    }

    /**
     * Spawns standing at {@code pos} facing {@code yaw}, with the given target lean.
     * Viewer and mirror are set BEFORE the entity enters the world, so the very first packet
     * already says whom it is for (other players never render it, not even for one frame).
     *
     * @param viewer only this player sees it and makes it react; null = everyone
     */
    public static WatcherEntity spawnAt(ServerLevel level, Vec3 pos, float yaw, float lean, boolean glow,
                                        int lifeTicks, UUID viewer, boolean mirror) {
        WatcherEntity w = ModEntities.WATCHER.get().create(level);
        if (w == null) return null;
        w.moveTo(pos.x, pos.y, pos.z, yaw, 0f);
        w.setYHeadRot(yaw);
        w.setYBodyRot(yaw);
        w.setLean(lean);
        w.setGlowingTag(glow);
        w.lifeTicks = lifeTicks;
        w.setViewer(viewer);
        w.setMirror(mirror);
        level.addFreshEntity(w);
        return w;
    }

    /** Cave ambush mode, see {@link #glimpse}. Call right after {@link #spawn}. */
    public void setGlimpse(boolean on) {
        glimpse = on;
    }

    /** World position of the eyes for a given lean (from the waist; positive = to its right). */
    public Vec3 headPosition(float leanDeg) {
        double yaw = yBodyRot * Mth.DEG_TO_RAD;
        // Forward is (-sin yaw, cos yaw); its right-hand side is (-cos yaw, -sin yaw).
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double[] eye = WatcherPose.eyeOffset(leanDeg);
        return position().add(rx * eye[0], eye[1], rz * eye[0]);
    }

    @Override
    public void tick() {
        super.tick();
        // Body is locked to the facing chosen at spawn time (head tracking is client-side).
        float yaw = getYRot();
        setYBodyRot(yaw);
        yBodyRotO = yaw;

        if (level() instanceof ServerLevel server) serverTick(server);
    }

    private void serverTick(ServerLevel server) {
        switch (phase) {
            case HIDING -> {
                if (tickCount - noticedAt < vanishTicks) return;
                if (reaction == Reaction.RETURN && !returned) phase = Phase.AWAY; // waits upright behind the cover
                else if (!uprightSeen(server)) discard();
                // else: you can still see it standing there (you moved while it hid) - it never vanishes
                // in front of your eyes; it is gone the moment you look away.
                return;
            }
            case AWAY -> {
                tickAway(server);
                return;
            }
            case STARING -> {
                tickStaring(server);
                return;
            }
            default -> { }
        }

        Vec3 head = headPosition(currentLean());
        boolean anyoneNear = false;
        boolean seenByAnyone = false;
        boolean onScreen = false;
        for (ServerPlayer p : server.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            if (!isFor(p)) continue; // someone else's watcher: invisible to this player, ignores him
            Vec3 eye = p.getEyePosition();
            Vec3 toHead = head.subtract(eye);
            double dist = toHead.length();
            if (dist > LOST_RANGE) continue;
            anyoneNear = true;
            if (dist > NOTICE_RANGE || dist < 1e-3) continue;
            if (!reactEnabled || tickCount - peekStart < ARM_DELAY_TICKS) continue;

            boolean visible = CoverFinder.canSee(server, p, eye, head);
            if (visible) seenByAnyone = true;
            double angle = angleTo(p, toHead, dist);

            if (finale) {
                if (angle <= FINALE_ANGLE && visible) {
                    // You turned around - and there is nothing. No effect on you: it never makes contact.
                    com.anta.director.Director.onNoticed(p, true);
                    discard();
                    return;
                }
                continue;
            }

            if (dist < TOO_CLOSE) {
                hide(p, Reaction.SNAP, "too close", angle, dist);
                return;
            }
            if (angle <= NOTICE_HALF_ANGLE && visible) {
                react(p, chooseReaction(p), angle, dist);
                return;
            }
            if (glimpse && visible && angle <= GLIMPSE_ANGLE) {
                onScreen = true;
                if (++seenTicks >= GLIMPSE_TICKS) {
                    hide(p, Reaction.SNAP, "glimpsed", angle, dist);
                    return;
                }
            }
        }
        if (!onScreen) seenTicks = 0;

        if (anyoneNear && !finale && !glimpse && noticedAt < 0 && relocations < MAX_RELOCATIONS
                && tickCount % 10 == 0 && tickCount - peekStart > ARM_DELAY_TICKS && maybeRelocate(server, head)) {
            return;
        }

        if (!anyoneNear) {
            discard();
            return;
        }
        if (lifeTicks >= 0 && tickCount >= lifeTicks) {
            // Time is up: leave quietly; if someone can see the head right now, duck away instead of popping out of existence.
            if (seenByAnyone) hide(null, Reaction.SNAP, null, 0, 0);
            else discard();
        }
    }

    // --- Staying where it can be seen ---

    private int relocations = 0;
    private int lostTicks = 0;

    /** True if some player it is for has its upright head on screen with a clear line of sight. */
    private boolean uprightSeen(ServerLevel server) {
        Vec3 head = headPosition(0f);
        for (ServerPlayer p : server.players()) {
            if (p.isSpectator() || !p.isAlive() || !isFor(p)) continue;
            Vec3 eye = p.getEyePosition();
            Vec3 to = head.subtract(eye);
            double d = to.length();
            if (d > LOST_RANGE || d < 1e-3) continue;
            if (angleTo(p, to, d) <= GLIMPSE_ANGLE && CoverFinder.canSee(server, p, eye, head)) return true;
        }
        return false;
    }

    private ServerPlayer viewerPlayer(ServerLevel server) {
        Optional<UUID> id = getViewer();
        Player p = id.isPresent() ? server.getPlayerByUUID(id.get()) : server.getNearestPlayer(this, LOST_RANGE);
        return p instanceof ServerPlayer sp && !sp.isSpectator() && sp.isAlive() ? sp : null;
    }

    /**
     * The peek was aimed at where you stood when it came. If you walked on and its head has had no
     * line to you for {@link #RELOCATE_TICKS}, it moves: a new watcher takes a fresh spot (out of
     * view, peeking at where you are now) and this one is removed. Only while neither spot is on your
     * screen, so you never see it jump. Called every 10 ticks.
     */
    private boolean maybeRelocate(ServerLevel server, Vec3 head) {
        if (lifeTicks < 0) return false; // debug spawns stay where they were put
        ServerPlayer p = viewerPlayer(server);
        if (p == null) return false;
        Vec3 eye = p.getEyePosition();
        if (CoverFinder.canSee(server, p, eye, head)) {
            lostTicks = 0;
            return false;
        }
        lostTicks += 10;
        if (lostTicks < RELOCATE_TICKS) return false;
        Vec3 to = position().add(0, 1, 0).subtract(eye);
        if (to.length() > 1e-3 && angleTo(p, to, to.length()) <= GLIMPSE_ANGLE) return false; // old spot on screen
        lostTicks = 0;
        double pref = Mth.clamp(position().distanceTo(p.position()), 8.0, CoverFinder.PREFERRED_DISTANCE);
        Optional<CoverFinder.Result> found = CoverFinder.find(server, p, true, pref).best();
        if (found.isEmpty()) return false;
        CoverFinder.Result r = found.get();
        int left = Math.max(100, lifeTicks - tickCount);
        WatcherEntity n = spawnAt(server, r.pos(), r.yaw(), r.lean(), hasGlowingTag(), left,
                getViewer().orElse(null), isMirror());
        if (n == null) return false;
        n.relocations = relocations + 1;
        com.anta.director.Director.onRelocated(p, n);
        debug(p, String.format(Locale.ROOT, "moved to a new spot, %.0f m (move %d)", r.distance(), n.relocations));
        discard();
        return true;
    }

    // --- The brain ---

    /**
     * Picks how to react to being looked at. The more often this player has noticed it, the
     * bolder it gets: at first it mostly jerks away, later it more and more often holds your
     * gaze, ignores it, or comes back. An unusual reaction never repeats twice in a row, so
     * the player cannot learn what it will do next.
     */
    private Reaction chooseReaction(ServerPlayer p) {
        if (returned) return Reaction.SNAP;
        if (forcedReaction != null) return forcedReaction;
        if (!com.anta.AntaConfig.VARIED_REACTIONS.get()) return Reaction.SNAP;
        CompoundTag saved = com.anta.director.Director.persisted(p);
        int sightings = saved.getInt(com.anta.director.Director.KEY_SIGHTINGS);
        float bold = Math.min(1f, sightings / 8f);
        Reaction[] all = Reaction.values();
        float[] w = {
                55f - 25f * bold, // SNAP
                12f + 13f * bold, // STARE
                10f + 5f * bold,  // SLOW
                8f + 7f * bold,   // WAIT
                15f               // RETURN
        };
        int last = saved.contains(KEY_LAST_REACTION) ? saved.getInt(KEY_LAST_REACTION) : 0;
        if (last > 0 && last < all.length) w[last] = 0f;
        float sum = 0f;
        for (float x : w) sum += x;
        float r = random.nextFloat() * sum;
        for (int i = 0; i < all.length; i++) {
            r -= w[i];
            if (r < 0f) return all[i];
        }
        return Reaction.SNAP;
    }

    /** Starts the chosen reaction to a direct look from {@code p}. */
    private void react(ServerPlayer p, Reaction r, double angle, double dist) {
        if (!counted) com.anta.director.Director.persisted(p).putInt(KEY_LAST_REACTION, r.ordinal());
        countSighting(p);
        switch (r) {
            case STARE, WAIT -> {
                reaction = r;
                reactingTo = p.getUUID();
                phase = Phase.STARING;
                offScreenTicks = 0;
                phaseUntil = tickCount + (r == Reaction.STARE
                        ? STARE_MIN_TICKS + random.nextInt(STARE_MAX_TICKS - STARE_MIN_TICKS + 1)
                        : WAIT_MAX_TICKS);
                debug(p, String.format(Locale.ROOT, "noticed, %s %.1f s \u2014 %.0f\u00b0, %.1f m",
                        r == Reaction.STARE ? "stares" : "waits", (phaseUntil - tickCount) / 20.0, angle, dist));
            }
            default -> hide(p, r, (returned ? "noticed again" : "noticed") + reactionNote(r), angle, dist);
        }
    }

    /** One appearance counts as one sighting, however many times it is looked at (RETURN, STARE then too close). */
    private boolean counted = false;

    private void countSighting(ServerPlayer p) {
        if (counted) return;
        counted = true;
        com.anta.director.Director.onNoticed(p, false);
    }

    private static String reactionNote(Reaction r) {
        return switch (r) {
            case SLOW -> ", slow retreat";
            case RETURN -> ", will come back";
            default -> "";
        };
    }

    /** STARE / WAIT: holds still while you look; gone the moment you look away. */
    private void tickStaring(ServerLevel server) {
        ServerPlayer p = reactingTo == null ? null : server.getServer().getPlayerList().getPlayer(reactingTo);
        if (p == null || p.level() != server || !p.isAlive() || p.isSpectator()) {
            discard();
            return;
        }
        Vec3 eye = p.getEyePosition();
        Vec3 toHead = headPosition(currentLean()).subtract(eye);
        double dist = toHead.length();
        if (dist > LOST_RANGE) {
            discard();
            return;
        }
        if (dist < TOO_CLOSE) {
            hide(p, Reaction.SNAP, "too close", 0, dist);
            return;
        }
        boolean onScreen = dist > 1e-3 && angleTo(p, toHead, dist) <= GLIMPSE_ANGLE
                && CoverFinder.canSee(server, p, eye, eye.add(toHead));
        if (!onScreen) {
            if (++offScreenTicks >= LOOK_AWAY_TICKS) {
                // You looked away for a moment - and when you look back, the spot is empty.
                debug(p, "gone while you looked away");
                discard();
            }
            return;
        }
        offScreenTicks = 0;
        if (tickCount >= phaseUntil) {
            // STARE: usually the jerk, sometimes the calm retreat; WAIT: it gives up slowly.
            Reaction end = reaction == Reaction.WAIT || random.nextFloat() < 0.3f ? Reaction.SLOW : Reaction.SNAP;
            reaction = end;
            startHide(end);
        }
    }

    /** RETURN: hidden upright behind the cover; peeks out once more when you are not looking at the spot. */
    private void tickAway(ServerLevel server) {
        ServerPlayer p = reactingTo == null ? null : server.getServer().getPlayerList().getPlayer(reactingTo);
        if (p == null || p.level() != server || !p.isAlive() || p.isSpectator()) {
            discard();
            return;
        }
        Vec3 eye = p.getEyePosition();
        Vec3 toUpright = headPosition(0f).subtract(eye);
        double dist = toUpright.length();
        if (dist > LOST_RANGE || dist < TOO_CLOSE) {
            discard();
            return;
        }
        boolean uprightSeen = CoverFinder.canSee(server, p, eye, eye.add(toUpright));
        boolean spotOnScreen = angleTo(p, toUpright, dist) <= GLIMPSE_ANGLE;
        if (uprightSeen && !spotOnScreen) {
            // You walked around the cover: it is not standing there waiting for you to find it.
            discard();
            return;
        }
        if (uprightSeen) {
            // You found it standing behind the cover and you are looking: it holds still, like WAIT, and is gone
            // the moment you look away (the branch above). Hard cap so it can never stand there forever.
            if (tickCount - noticedAt > RETURN_GIVE_UP_TICKS * 2) discard();
            return;
        }
        if (tickCount - noticedAt > RETURN_GIVE_UP_TICKS) {
            discard(); // could not come back unseen in time (and nobody can see it now)
            return;
        }
        if (tickCount < phaseUntil || spotOnScreen) return;
        // Comes out again, slowly, from the same place.
        returned = true;
        phase = Phase.WATCHING;
        peekStart = tickCount;
        // Its time may have run out while it was hidden: give the second peek its moment instead of leaving at once.
        if (lifeTicks >= 0) lifeTicks = Math.max(lifeTicks, tickCount + 100);
        noticedAt = -1;
        seenTicks = 0;
        entityData.set(SLOW_HIDE, false);
        entityData.set(HIDING, false);
        debug(p, "peeks out again");
    }

    private void hide(ServerPlayer reason, Reaction r, String what, double angle, double dist) {
        if (reason != null) countSighting(reason);
        reaction = r;
        if (reason != null) reactingTo = reason.getUUID();
        startHide(r);
        if (r == Reaction.RETURN && !returned) {
            phaseUntil = tickCount + RETURN_MIN_TICKS + random.nextInt(RETURN_MAX_TICKS - RETURN_MIN_TICKS + 1);
        }
        if (reason != null && what != null) {
            debug(reason, String.format(Locale.ROOT, "%s \u2014 %.0f\u00b0, %.1f m", what, angle, dist));
        }
    }

    private void startHide(Reaction r) {
        phase = Phase.HIDING;
        noticedAt = tickCount;
        boolean slow = r == Reaction.SLOW;
        vanishTicks = slow ? WatcherPose.SLOW_HIDE_TICKS + 2 : VANISH_TICKS;
        entityData.set(SLOW_HIDE, slow);
        entityData.set(HIDING, true);
    }

    private static double angleTo(ServerPlayer p, Vec3 toHead, double dist) {
        double cos = p.getLookAngle().dot(toHead.scale(1.0 / dist));
        return Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
    }

    private static void debug(ServerPlayer p, String text) {
        if (com.anta.director.Director.debugFor(p)) {
            p.displayClientMessage(Component.literal("[anta] " + text).withStyle(ChatFormatting.YELLOW), true);
        }
    }

    // --- It is not a thing you can touch: no hitbox for the crosshair or projectiles, no leads, no name tags. ---

    /** Not targetable: clicks, attacks, arrows and name tags go through it (and through other players' watchers). */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    /** Watchers are never written to the save: each appearance is a one-off. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Lean", getLean());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setLean(tag.getFloat("Lean"));
    }
}
