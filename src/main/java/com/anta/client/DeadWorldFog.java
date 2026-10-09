package com.anta.client;

import com.anta.AntaMod;
import com.anta.network.AntaNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The dead day's medium grey fog, in the Overworld only. Fades in and out over a few seconds and also
 * covers the sky, so there is no blue above: just grey. Under water / in lava the vanilla fog is kept.
 */
@Mod.EventBusSubscriber(modid = AntaMod.MODID, value = Dist.CLIENT)
public final class DeadWorldFog {
    /** Fog distances at full strength, blocks: medium - you see about three chunks. */
    public static final float NEAR = 4f, FAR = 44f;
    /** Fog colour: medium grey. */
    public static final float GREY = 0.55f;
    /** Fade speed per tick (1/100 = 5 seconds). */
    private static final float FADE = 0.01f;

    private static float strength, strengthO;

    private DeadWorldFog() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        boolean on = AntaNetwork.clientDeadWorld && mc.level != null && mc.level.dimension() == Level.OVERWORLD;
        strengthO = strength;
        strength = on ? Math.min(1f, strength + FADE) : Math.max(0f, strength - FADE);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        AntaNetwork.clientDeadWorld = false;
        strength = strengthO = 0f;
    }

    private static float current(double partialTick) {
        return (float) (strengthO + (strength - strengthO) * partialTick);
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float k = current(event.getPartialTick());
        if (k <= 0f || event.getCamera().getFluidInCamera() != FogType.NONE) return;
        event.setNearPlaneDistance(lerp(event.getNearPlaneDistance(), NEAR, k));
        event.setFarPlaneDistance(lerp(event.getFarPlaneDistance(), FAR, k));
        event.setCanceled(true); // required for the new distances to be used
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float k = current(event.getPartialTick());
        if (k <= 0f || event.getCamera().getFluidInCamera() != FogType.NONE) return;
        event.setRed(lerp(event.getRed(), GREY, k));
        event.setGreen(lerp(event.getGreen(), GREY, k));
        event.setBlue(lerp(event.getBlue(), GREY, k));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
