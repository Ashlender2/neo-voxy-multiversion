package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.distant.DistantBlockShader;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import me.cortex.voxy.client.compat.distant.DistantVertexCapture;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantCreateRenderer implements LodPipelineHooks.Renderer {
    public static final DistantCreateRenderer INSTANCE = new DistantCreateRenderer();
    private static final PoseStack POSE = new PoseStack();
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private ResourceKey<Level> dimension;

    private static final class Snapshot {
        DistantMesh mesh;
        final Matrix4f local = new Matrix4f();
        double x;
        double y;
        double z;
        long signature;
        long lastSeen;
        boolean train;
        boolean live;
    }

    private DistantCreateRenderer() {}

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !VoxyConfig.CONFIG.isRenderingEnabled()
                || (!VoxyConfig.CONFIG.distantContraptions && !VoxyConfig.CONFIG.distantTrains)) {
            this.clear();
            return;
        }
        if (!mc.level.dimension().equals(this.dimension)) {
            this.clear();
            this.dimension = mc.level.dimension();
        }

        long now = System.currentTimeMillis();
        var seen = new HashSet<UUID>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractContraptionEntity contraptionEntity)) continue;
            var contraption = contraptionEntity.getContraption();
            if (contraption == null || contraption.getBlocks().isEmpty()) continue;
            UUID id = entity.getUUID();
            seen.add(id);
            Snapshot snapshot = this.snapshots.computeIfAbsent(id, ignored -> new Snapshot());
            snapshot.train = entity instanceof CarriageContraptionEntity;
            snapshot.live = true;
            snapshot.x = entity.getX();
            snapshot.y = entity.getY();
            snapshot.z = entity.getZ();
            snapshot.lastSeen = now;

            long signature = 1;
            for (var entry : contraption.getBlocks().entrySet()) {
                signature = signature * 31 + entry.getKey().asLong();
                signature = signature * 31 + entry.getValue().state().hashCode();
            }
            if (snapshot.mesh == null || snapshot.signature != signature) {
                DistantMesh replacement = bake(contraptionEntity);
                if (replacement != null) {
                    if (snapshot.mesh != null) snapshot.mesh.free();
                    snapshot.mesh = replacement;
                    snapshot.signature = signature;
                }
            }

            POSE.pushPose();
            try {
                contraptionEntity.applyLocalTransforms(POSE, 1.0f);
                snapshot.local.set(POSE.last().pose());
            } catch (Throwable ignored) {
            } finally {
                POSE.popPose();
            }
        }

        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        double reachSq = reach * reach;
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        this.snapshots.entrySet().removeIf(entry -> {
            Snapshot snapshot = entry.getValue();
            if (seen.contains(entry.getKey())) return false;
            snapshot.live = false;
            double dx = snapshot.x - camera.x;
            double dy = snapshot.y - camera.y;
            double dz = snapshot.z - camera.z;
            if (now - snapshot.lastSeen >= 2000 && dx * dx + dy * dy + dz * dz < reachSq) {
                if (snapshot.mesh != null) snapshot.mesh.free();
                return true;
            }
            double max = distanceFor(snapshot) + 32.0;
            if (dx * dx + dy * dy + dz * dz > max * max) {
                if (snapshot.mesh != null) snapshot.mesh.free();
                return true;
            }
            return false;
        });
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        this.clear();
        this.dimension = null;
    }

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(this.dimension) || this.snapshots.isEmpty()) return;
        double vanilla = Math.max(16.0, mc.options.getEffectiveRenderDistance() * 16.0 - 8.0);
        double vanillaSq = vanilla * vanilla;
        boolean bound = false;
        Matrix4f transform = new Matrix4f();
        pipeline.setupAndBindOpaque(viewport);
        for (Snapshot snapshot : this.snapshots.values()) {
            if (snapshot.mesh == null || snapshot.train && !VoxyConfig.CONFIG.distantTrains
                    || !snapshot.train && !VoxyConfig.CONFIG.distantContraptions) continue;
            double dx = snapshot.x - viewport.cameraX;
            double dy = snapshot.y - viewport.cameraY;
            double dz = snapshot.z - viewport.cameraZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            double max = distanceFor(snapshot);
            if (distanceSq < vanillaSq || distanceSq > max * max) continue;
            if (!bound) {
                DistantBlockShader.get(pipeline).bind();
                DistantBlockShader.bindTextures();
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(depthFunc);
                glDepthMask(true);
                glDisable(GL_CULL_FACE);
                glDisable(GL_BLEND);
                bound = true;
            }
            transform.set(viewport.MVP).translate((float) dx, (float) dy, (float) dz).mul(snapshot.local);
            DistantBlockShader.uploadTransform(transform);
            snapshot.mesh.draw();
        }
        if (bound) {
            glBindVertexArray(0);
            glUseProgram(0);
        }
    }

    private static double distanceFor(Snapshot snapshot) {
        int chunks = snapshot.train ? VoxyConfig.CONFIG.distantTrainMaxChunks
                : VoxyConfig.CONFIG.distantContraptionMaxChunks;
        return chunks == 0 ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks() : chunks * 16.0;
    }

    private static DistantMesh bake(AbstractContraptionEntity entity) {
        var contraption = entity.getContraption();
        var builder = new DistantMesh.Builder();
        var capture = new DistantVertexCapture(builder, true);
        var clientContraption = contraption.getOrCreateClientContraptionLazy();
        var renderWorld = clientContraption.getRenderLevel();
        var pose = new PoseStack();
        for (var layer : net.minecraft.client.renderer.RenderType.chunkBufferLayers()) {
            try {
                var buffer = com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer
                        .getBuffer(contraption, renderWorld, layer);
                if (!buffer.isEmpty()) buffer.renderInto(pose, capture);
            } catch (Throwable ignored) {}
        }
        return builder.build();
    }

    private void clear() {
        for (Snapshot snapshot : this.snapshots.values()) {
            if (snapshot.mesh != null) snapshot.mesh.free();
        }
        this.snapshots.clear();
    }
}
