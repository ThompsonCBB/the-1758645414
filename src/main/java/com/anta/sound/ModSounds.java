package com.anta.sound;

import com.anta.AntaMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The mod's ONLY sounds (user's exception to the "no sounds" rule, 2026-10-09): footsteps and a
 * rustle somewhere behind you in the forest. No screams, no voices.
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, AntaMod.MODID);

    /** Index used in the network packet. */
    public static final int STEPS = 0, RUSTLE = 1, COUNT = 2;

    public static final RegistryObject<SoundEvent> FOREST_STEPS = register("forest_steps");
    public static final RegistryObject<SoundEvent> FOREST_RUSTLE = register("forest_rustle");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(AntaMod.MODID, name)));
    }

    public static SoundEvent get(int index) {
        return (index == STEPS ? FOREST_STEPS : FOREST_RUSTLE).get();
    }

    private ModSounds() {}
}
