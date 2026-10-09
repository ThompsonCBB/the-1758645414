package com.anta.client;

import com.anta.anim.CarcassLayout;
import com.anta.entity.CarcassEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Draws a dead animal. WHAT is drawn where is decided by {@link CarcassLayout} (pure code, shared with
 * the offline carcass simulator in tools/animsim); this class only turns its calls into a PoseStack,
 * the baked VANILLA model parts and textured quads.
 */
public class CarcassRenderer extends EntityRenderer<CarcassEntity> {
    private static final ResourceLocation GORE = tex("carcass_gore");
    private static final ResourceLocation[] DECALS = {tex("carcass_blood"), tex("carcass_smear")};
    /** Gore atlas: 8 tiles of 16x16 in a row. */
    private static final float ATLAS_W = 128f, ATLAS_H = 16f;
    /** A dead body is duller than a living one. */
    private static final float TINT_R = 0.84f, TINT_G = 0.78f, TINT_B = 0.76f;

    private record Model(ModelPart root, ModelPart fur, ResourceLocation tex, ResourceLocation furTex) {}

    /** Blood soaked into the skin, painted on each animal's own UV map, per way it was killed. */
    private static final ResourceLocation[][] SOAK = new ResourceLocation[CarcassEntity.Kind.values().length][CarcassEntity.Style.values().length];

    static {
        for (CarcassEntity.Kind k : CarcassEntity.Kind.values()) {
            for (CarcassEntity.Style s : CarcassEntity.Style.values()) {
                SOAK[k.ordinal()][s.ordinal()] = tex("carcass_soak_" + k.name().toLowerCase(java.util.Locale.ROOT)
                        + "_" + s.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    private final Model[] models = new Model[CarcassEntity.Kind.values().length];

    public CarcassRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        shadowRadius = 0f;
        models[CarcassEntity.Kind.SHEEP.ordinal()] = new Model(ctx.bakeLayer(ModelLayers.SHEEP), ctx.bakeLayer(ModelLayers.SHEEP_FUR),
                vanilla("sheep/sheep"), vanilla("sheep/sheep_fur"));
        models[CarcassEntity.Kind.COW.ordinal()] = new Model(ctx.bakeLayer(ModelLayers.COW), null, vanilla("cow/cow"), null);
        models[CarcassEntity.Kind.PIG.ordinal()] = new Model(ctx.bakeLayer(ModelLayers.PIG), null, vanilla("pig/pig"), null);
        models[CarcassEntity.Kind.CHICKEN.ordinal()] = new Model(ctx.bakeLayer(ModelLayers.CHICKEN), null, vanilla("chicken"), null);
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation("anta", "textures/entity/" + name + ".png");
    }

    private static ResourceLocation vanilla(String path) {
        return new ResourceLocation("minecraft", "textures/entity/" + path + ".png");
    }

    @Override
    public ResourceLocation getTextureLocation(CarcassEntity entity) {
        return models[entity.getKind().ordinal()].tex();
    }

    @Override
    public void render(CarcassEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        Model m = models[entity.getKind().ordinal()];
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180f - entity.getYRot()));
        CarcassLayout.draw(entity.getKind().ordinal(), entity.getStyle().ordinal(),
                entity.getUUID().getLeastSignificantBits(),
                new PoseSink(m, SOAK[entity.getKind().ordinal()][entity.getStyle().ordinal()], pose, buffers, light));
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    /** The layout's calls on a real PoseStack. Buffers are fetched per call: switching render types ends a batch. */
    private static final class PoseSink implements CarcassLayout.Sink {
        private final Model m;
        private final ResourceLocation soak;
        private final PoseStack pose;
        private final MultiBufferSource buffers;
        private final int light;

        PoseSink(Model m, ResourceLocation soak, PoseStack pose, MultiBufferSource buffers, int light) {
            this.m = m;
            this.soak = soak;
            this.pose = pose;
            this.buffers = buffers;
            this.light = light;
        }

        @Override public void push() { pose.pushPose(); }
        @Override public void pop() { pose.popPose(); }
        @Override public void translate(float x, float y, float z) { pose.translate(x, y, z); }
        @Override public void rotateX(float deg) { pose.mulPose(Axis.XP.rotationDegrees(deg)); }
        @Override public void rotateY(float deg) { pose.mulPose(Axis.YP.rotationDegrees(deg)); }
        @Override public void rotateZ(float deg) { pose.mulPose(Axis.ZP.rotationDegrees(deg)); }
        @Override public void scale(float x, float y, float z) { pose.scale(x, y, z); }

        @Override
        public void part(String name, int layer, float shiftX, float shiftY, float shiftZ) {
            ModelPart root = switch (layer) {
                case CarcassLayout.FUR -> m.fur();
                case CarcassLayout.SOAK -> m.fur() != null ? m.fur() : m.root();
                default -> m.root();
            };
            if (root == null || !root.hasChild(name)) return;
            ModelPart p = root.getChild(name);
            VertexConsumer vc = switch (layer) {
                case CarcassLayout.FUR -> buffers.getBuffer(RenderType.entityCutoutNoCull(m.furTex()));
                case CarcassLayout.SOAK -> buffers.getBuffer(RenderType.entityTranslucent(soak));
                default -> buffers.getBuffer(RenderType.entityCutoutNoCull(m.tex()));
            };
            float x = p.x, y = p.y, z = p.z;
            p.x = x - shiftX;
            p.y = y - shiftY;
            p.z = z - shiftZ;
            try {
                if (layer == CarcassLayout.SOAK) p.render(pose, vc, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
                else p.render(pose, vc, light, OverlayTexture.NO_OVERLAY, TINT_R, TINT_G, TINT_B, 1f);
            } finally {
                p.x = x;
                p.y = y;
                p.z = z;
            }
        }

        @Override
        public void cube(float cx, float cy, float cz, float sx, float sy, float sz, int tile, int capFace, int capTile) {
            VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(GORE));
            Matrix4f mat = pose.last().pose();
            Matrix3f nrm = pose.last().normal();
            float x0 = cx - sx / 2, x1 = cx + sx / 2, y0 = cy - sy / 2, y1 = cy + sy / 2, z0 = cz - sz / 2, z1 = cz + sz / 2;
            // Texels are block-scaled (16 per block, like every Minecraft texture), never stretched over a tiny face.
            face(vc, mat, nrm, t(CarcassLayout.POS_Y, tile, capFace, capTile), CarcassLayout.POS_Y == capFace, sx, sz, 0, 1, 0,
                    x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            face(vc, mat, nrm, t(CarcassLayout.NEG_Y, tile, capFace, capTile), CarcassLayout.NEG_Y == capFace, sx, sz, 0, -1, 0,
                    x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1);
            face(vc, mat, nrm, t(CarcassLayout.NEG_Z, tile, capFace, capTile), CarcassLayout.NEG_Z == capFace, sx, sy, 0, 0, -1,
                    x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0);
            face(vc, mat, nrm, t(CarcassLayout.POS_Z, tile, capFace, capTile), CarcassLayout.POS_Z == capFace, sx, sy, 0, 0, 1,
                    x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1);
            face(vc, mat, nrm, t(CarcassLayout.NEG_X, tile, capFace, capTile), CarcassLayout.NEG_X == capFace, sz, sy, -1, 0, 0,
                    x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1);
            face(vc, mat, nrm, t(CarcassLayout.POS_X, tile, capFace, capTile), CarcassLayout.POS_X == capFace, sz, sy, 1, 0, 0,
                    x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0);
        }

        private static int t(int face, int tile, int capFace, int capTile) {
            return face == capFace ? capTile : tile;
        }

        /**
         * Corners a..d go top-left, bottom-left, bottom-right, top-right of the face as seen from outside.
         * Ordinary tiles are block-scaled (16 texels per block); a cap (a designed cross-section) shows its whole tile.
         */
        private void face(VertexConsumer vc, Matrix4f mat, Matrix3f nrm, int tile, boolean cap, float w, float h,
                          float nx, float ny, float nz,
                          float ax, float ay, float az, float bx, float by, float bz,
                          float cx, float cy, float cz, float dx, float dy, float dz) {
            if (tile < 0) return; // CarcassLayout.SKIP
            cap |= tile == CarcassLayout.GUTS; // the loops pattern must show whole, even on a small cube
            float u0 = tile * 16f / ATLAS_W;
            float u1 = u0 + (cap ? 16f : Math.min(16f, Math.max(1f, w * 16f))) / ATLAS_W;
            float v1 = (cap ? 16f : Math.min(16f, Math.max(1f, h * 16f))) / ATLAS_H;
            put(vc, mat, nrm, ax, ay, az, u0, 0f, nx, ny, nz, 1f);
            put(vc, mat, nrm, bx, by, bz, u0, v1, nx, ny, nz, 1f);
            put(vc, mat, nrm, cx, cy, cz, u1, v1, nx, ny, nz, 1f);
            put(vc, mat, nrm, dx, dy, dz, u1, 0f, nx, ny, nz, 1f);
        }

        private void put(VertexConsumer vc, Matrix4f mat, Matrix3f nrm, float x, float y, float z,
                         float u, float v, float nx, float ny, float nz, float k) {
            vc.vertex(mat, x, y, z).color(k, k, k, 1f).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(light).normal(nrm, nx, ny, nz).endVertex();
        }

        @Override
        public void decal(int texture, float x, float z, float hx, float hz, float rotDeg, float lift) {
            VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(DECALS[Math.floorMod(texture, DECALS.length)]));
            pose.pushPose();
            pose.translate(x, lift, z);
            pose.mulPose(Axis.YP.rotationDegrees(rotDeg));
            Matrix4f mat = pose.last().pose();
            Matrix3f nrm = pose.last().normal();
            put(vc, mat, nrm, -hx, 0f, -hz, 0f, 0f, 0f, 1f, 0f, 1f);
            put(vc, mat, nrm, -hx, 0f, hz, 0f, 1f, 0f, 1f, 0f, 1f);
            put(vc, mat, nrm, hx, 0f, hz, 1f, 1f, 0f, 1f, 0f, 1f);
            put(vc, mat, nrm, hx, 0f, -hz, 1f, 0f, 0f, 1f, 0f, 1f);
            pose.popPose();
        }
    }
}
