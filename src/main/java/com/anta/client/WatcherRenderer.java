package com.anta.client;

import com.anta.entity.WatcherEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * Renders the watcher as a Steve-shaped figure. The lean (from the waist, legs stay upright)
 * is done in {@link WatcherModel}.
 *
 * Multiplayer: a watcher that came for another player is not drawn at all.
 * Mirror ("reflection"): the body wears the viewer's own skin, the head stays the empty one.
 */
public class WatcherRenderer extends HumanoidMobRenderer<WatcherEntity, WatcherModel> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("anta", "textures/entity/watcher_double.png");

    /** Texture used by the current render pass. */
    private ResourceLocation passTexture = TEXTURE;
    private final WatcherModel wideModel;
    /** Same model with thin arms, for mirror mode when the viewer's own skin is slim. */
    private final WatcherModel slimModel;

    public WatcherRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new WatcherModel(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        wideModel = getModel();
        slimModel = new WatcherModel(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    @Override
    public ResourceLocation getTextureLocation(WatcherEntity entity) {
        return passTexture;
    }

    @Override
    public boolean shouldRender(WatcherEntity entity, Frustum frustum, double x, double y, double z) {
        Optional<UUID> target = entity.getViewer();
        LocalPlayer me = Minecraft.getInstance().player;
        if (target.isPresent() && me != null && !target.get().equals(me.getUUID())) return false;
        return super.shouldRender(entity, frustum, x, y, z);
    }

    @Override
    public void render(WatcherEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        LocalPlayer me = Minecraft.getInstance().player;
        if (!entity.isMirror() || me == null) {
            passTexture = TEXTURE;
            super.render(entity, yaw, partialTick, pose, buffers, light);
            return;
        }
        if ("slim".equals(me.getModelName())) model = slimModel;
        WatcherModel m = getModel();
        try {
            // Pass 1: everything but the head, in your skin.
            setHead(m, false);
            setBody(m, true);
            passTexture = me.getSkinTextureLocation();
            super.render(entity, yaw, partialTick, pose, buffers, light);
            // Pass 2: only the empty head.
            setHead(m, true);
            setBody(m, false);
            passTexture = TEXTURE;
            super.render(entity, yaw, partialTick, pose, buffers, light);
        } finally {
            setHead(m, true);
            setBody(m, true);
            passTexture = TEXTURE;
            model = wideModel;
        }
    }

    private static void setHead(WatcherModel m, boolean v) {
        m.head.visible = v;
        m.hat.visible = v;
    }

    private static void setBody(WatcherModel m, boolean v) {
        m.body.visible = v;
        m.jacket.visible = v;
        m.rightArm.visible = v;
        m.leftArm.visible = v;
        m.rightSleeve.visible = v;
        m.leftSleeve.visible = v;
        m.rightLeg.visible = v;
        m.leftLeg.visible = v;
        m.rightPants.visible = v;
        m.leftPants.visible = v;
    }
}
