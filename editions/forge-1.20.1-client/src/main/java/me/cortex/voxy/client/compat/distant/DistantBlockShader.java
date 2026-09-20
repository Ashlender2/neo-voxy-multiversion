package me.cortex.voxy.client.compat.distant;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL20C.nglUniformMatrix4fv;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

public final class DistantBlockShader {
    private static Shader shader;
    private static Shader patchedShader;
    private static AbstractRenderPipeline patchedOwner;
    private static boolean patchFailed;

    private DistantBlockShader() {}

    public static Shader get(AbstractRenderPipeline pipeline) {
        if (patchedOwner != pipeline) {
            if (patchedShader != null) patchedShader.free();
            patchedShader = null;
            patchedOwner = pipeline;
            patchFailed = false;
        }
        if (!patchFailed) {
            try {
                String fragment = pipeline.patchOpaqueShader(null,
                        ShaderLoader.parse("voxy:compat/distant_block.frag"));
                if (fragment != null) {
                    if (patchedShader == null) {
                        String vertex = ShaderLoader.parse("voxy:compat/distant_block.vert");
                        String taa = pipeline.taaFunction("distantTaaShift");
                        vertex += "\n" + (taa != null ? taa
                                : "vec2 distantTaaShift() { return vec2(0.0); }");
                        patchedShader = Shader.make().define("PATCHED_SHADER")
                                .addSource(ShaderType.VERTEX, vertex)
                                .addSource(ShaderType.FRAGMENT, fragment)
                                .compile().name("distant_block_patched");
                    }
                    return patchedShader;
                }
            } catch (Throwable error) {
                patchFailed = true;
                Logger.error("Failed to compile shader-pack patched 1.20.1 distant block shader", error);
            }
        }
        if (shader == null) {
            shader = Shader.make().add(ShaderType.VERTEX, "voxy:compat/distant_block.vert")
                    .add(ShaderType.FRAGMENT, "voxy:compat/distant_block.frag")
                    .compile().name("distant_block");
        }
        return shader;
    }

    public static void bindTextures() {
        glBindTextureUnit(0, Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getId());
        glBindSampler(0, 0);
        LightMapHelper.bind(1);
    }

    public static void uploadTransform(Matrix4f transform) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var data = stack.mallocFloat(16);
            transform.get(data);
            nglUniformMatrix4fv(0, 1, false, org.lwjgl.system.MemoryUtil.memAddress(data));
        }
    }
}
