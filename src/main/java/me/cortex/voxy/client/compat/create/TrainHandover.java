package me.cortex.voxy.client.compat.create;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class TrainHandover {
    public static final double CREATE_TRACKING_CAP = 224;

    private TrainHandover() {}

    public static double handoverDist() {
        //Full view distance: the switch belongs at the vanilla->LOD transition, and handing over any
        //earlier is glaring at small view distances (4 chunks rendered, trains going distant at 2).
        return Math.min(CREATE_TRACKING_CAP,
                Minecraft.getInstance().options.getEffectiveRenderDistance() * 16);
    }

    public static boolean beyondLive(Vec3 entityPos, Vec3 cam) {
        double d = handoverDist();
        return entityPos.distanceToSqr(cam) > d * d;
    }
}
