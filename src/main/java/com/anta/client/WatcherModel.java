package com.anta.client;

import com.anta.anim.WatcherPose;
import com.anta.entity.WatcherEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

/**
 * Steve model posed by {@link WatcherPose} (shared with the offline simulator in tools/animsim):
 * leans from the waist (legs upright), the head always turns to the camera of the player who
 * is rendering it, and the body never moves.
 */
public class WatcherModel extends PlayerModel<WatcherEntity> {
    /** @param slim thin (Alex-style) arms, used when it wears a slim skin in mirror mode */
    public WatcherModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(WatcherEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        float partialTick = ageInTicks - entity.tickCount;
        float lean = entity.getRenderLean(partialTick);

        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 d = cam.subtract(entity.headPosition(lean));
        float[] look = WatcherPose.lookAt(d.x, d.y, d.z, entity.yBodyRot);

        // Delayed head: it turns after you, not with you (several render passes per frame get dt = 0).
        if (entity.clientHeadTime < 0f) {
            entity.clientHeadYaw = look[0];
            entity.clientHeadPitch = look[1];
        } else {
            float dt = Math.min(ageInTicks - entity.clientHeadTime, 5f);
            entity.clientHeadYaw = WatcherPose.followHead(entity.clientHeadYaw, look[0], dt, entity.clientHeadTurningYaw);
            entity.clientHeadPitch = WatcherPose.followHead(entity.clientHeadPitch, look[1], dt, entity.clientHeadTurningPitch);
        }
        entity.clientHeadTime = ageInTicks;

        // Let vanilla reset everything (crouch, riding, ...), with no limb swing and no arm bob.
        super.setupAnim(entity, 0f, 0f, 0f, 0f, 0f);

        WatcherPose pose = WatcherPose.compute(lean, entity.getRenderRecoil(partialTick),
                entity.clientHeadYaw, entity.clientHeadPitch);
        if (!entity.isHiding()) pose.tiltHead(WatcherPose.idleTilt(ageInTicks, entity.getId()));
        apply(head, pose.head);
        apply(body, pose.body);
        apply(rightArm, pose.rightArm);
        apply(leftArm, pose.leftArm);
        apply(rightLeg, pose.rightLeg);
        apply(leftLeg, pose.leftLeg);
        hat.copyFrom(head);
        jacket.copyFrom(body);
        rightSleeve.copyFrom(rightArm);
        leftSleeve.copyFrom(leftArm);
        rightPants.copyFrom(rightLeg);
        leftPants.copyFrom(leftLeg);
    }

    private static void apply(ModelPart part, WatcherPose.Part p) {
        part.x = p.x;
        part.y = p.y;
        part.z = p.z;
        part.xRot = p.xRot;
        part.yRot = p.yRot;
        part.zRot = p.zRot;
    }
}
