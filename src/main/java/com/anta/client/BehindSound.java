package com.anta.client;

import com.anta.sound.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Footsteps or a rustle somewhere behind you in the forest. Positional (you hear the side, and it
 * turns with your head), but NOT pinned to a block: the source stays {@link #DISTANCE} blocks from
 * you in a fixed world direction, so it neither fades nor slides away while you walk or run.
 */
public final class BehindSound extends AbstractTickableSoundInstance {
    public static final double DISTANCE = 3.0;
    private final double dx, dz;

    private BehindSound(float yawDeg, int which) {
        super(ModSounds.get(which), SoundSource.HOSTILE, RandomSource.create());
        // Minecraft yaw: 0 = +Z, 90 = -X.
        double rad = Math.toRadians(yawDeg);
        dx = -Math.sin(rad);
        dz = Math.cos(rad);
        volume = 0.8f;
        pitch = 0.92f + random.nextFloat() * 0.16f;
        attenuation = SoundInstance.Attenuation.LINEAR;
        relative = false;
        follow();
    }

    private void follow() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        Vec3 pos = p.position();
        x = pos.x + dx * DISTANCE;
        y = pos.y + 0.3; // at ground level, where steps and leaves are
        z = pos.z + dz * DISTANCE;
    }

    @Override
    public void tick() {
        if (Minecraft.getInstance().player == null) {
            stop();
            return;
        }
        follow();
    }

    public static void play(float yawDeg, int which) {
        Minecraft.getInstance().getSoundManager().play(new BehindSound(yawDeg, which));
    }
}
