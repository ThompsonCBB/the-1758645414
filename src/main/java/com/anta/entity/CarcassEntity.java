package com.anta.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * A dead farm animal in the forest, its head torn off and lying next to it in a dark pool.
 * Pure decoration: not a mob (so the dead day leaves it where it is), cannot be touched, hit or
 * pushed, does not drop anything. It is saved with the world and rots away after a few days,
 * only while nobody is near (it never vanishes in front of you).
 */
public class CarcassEntity extends Entity {
    public enum Kind { SHEEP, COW, CHICKEN, PIG }

    /** How it was killed. */
    public enum Style {
        /** Head torn off and lying next to the body. */
        DECAPITATED,
        /** Belly ripped open: ribs, guts spilled on the ground; head still on. */
        GUTTED,
        /** Torn apart: head, legs and chunks of meat scattered over 3-4 blocks. */
        SCATTERED,
        /** Headless body with a long smear, as if the rest was dragged away. */
        DRAGGED
    }

    private static final EntityDataAccessor<Integer> STYLE =
            SynchedEntityData.defineId(CarcassEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> KIND =
            SynchedEntityData.defineId(CarcassEntity.class, EntityDataSerializers.INT);

    /** Rots away after this many ticks (3 Minecraft days)... */
    public static final int MAX_AGE = 3 * 24000;
    /** ...but only when no player is this close. */
    public static final double NOBODY_NEAR = 48.0;

    /** World game time when it was placed (-1 = not yet known; set on its first tick). */
    private long bornAt = -1L;

    public CarcassEntity(EntityType<? extends CarcassEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    public Kind getKind() {
        Kind[] all = Kind.values();
        int i = entityData.get(KIND);
        return all[Math.floorMod(i, all.length)];
    }

    public void setKind(Kind kind) {
        entityData.set(KIND, kind.ordinal());
    }

    public Style getStyle() {
        Style[] all = Style.values();
        return all[Math.floorMod(entityData.get(STYLE), all.length)];
    }

    public void setStyle(Style style) {
        entityData.set(STYLE, style.ordinal());
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(KIND, 0);
        entityData.define(STYLE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        // Age by world time, not by ticks lived: a carcass in a forest nobody visits (not ticking) still rots,
        // and goes the next time its chunk is loaded with nobody near.
        long now = level().getGameTime();
        if (bornAt < 0 || bornAt > now) bornAt = now;
        if (tickCount % 100 == 0 && now - bornAt > MAX_AGE && level().getNearestPlayer(this, NOBODY_NEAR) == null) discard();
    }

    // --- Decoration only: nothing can interact with it. ---

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    /** Pieces lie up to four blocks from the entity position: do not cull it early. */
    @Override
    public AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(4.5, 0.5, 4.5);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(KIND, tag.getInt("Kind"));
        bornAt = tag.contains("BornAt") ? tag.getLong("BornAt") : -1L; // pre-4.6.1 saves: start counting now
        entityData.set(STYLE, tag.getInt("Style"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Kind", entityData.get(KIND));
        tag.putLong("BornAt", bornAt);
        tag.putInt("Style", entityData.get(STYLE));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }
}
