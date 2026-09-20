package me.cortex.voxy.client.compat.distant;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL45C.*;

public final class DistantMesh {
    public static final int STRIDE = 28;
    private static int indexBuffer;
    private static int indexCapacity;

    private final int vao;
    private final int vbo;
    private final int quads;

    public DistantMesh(ByteBuffer data, int quads) {
        this.quads = quads;
        ensureIndices(quads);
        this.vbo = glCreateBuffers();
        glNamedBufferData(this.vbo, data, GL_STATIC_DRAW);
        this.vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(this.vao, 0, this.vbo, 0, STRIDE);
        glVertexArrayElementBuffer(this.vao, indexBuffer);
        glEnableVertexArrayAttrib(this.vao, 0);
        glVertexArrayAttribFormat(this.vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(this.vao, 0, 0);
        glEnableVertexArrayAttrib(this.vao, 1);
        glVertexArrayAttribFormat(this.vao, 1, 2, GL_FLOAT, false, 12);
        glVertexArrayAttribBinding(this.vao, 1, 0);
        glEnableVertexArrayAttrib(this.vao, 2);
        glVertexArrayAttribFormat(this.vao, 2, 2, GL_UNSIGNED_BYTE, true, 20);
        glVertexArrayAttribBinding(this.vao, 2, 0);
        glEnableVertexArrayAttrib(this.vao, 3);
        glVertexArrayAttribFormat(this.vao, 3, 4, GL_UNSIGNED_BYTE, true, 22);
        glVertexArrayAttribBinding(this.vao, 3, 0);
        glEnableVertexArrayAttrib(this.vao, 4);
        glVertexArrayAttribIFormat(this.vao, 4, 1, GL_UNSIGNED_BYTE, 26);
        glVertexArrayAttribBinding(this.vao, 4, 0);
    }

    private static void ensureIndices(int quads) {
        if (quads <= indexCapacity) return;
        int capacity = Math.max(quads, Math.max(indexCapacity * 2, 1024));
        int[] indices = new int[capacity * 6];
        for (int q = 0; q < capacity; q++) {
            int v = q * 4, i = q * 6;
            indices[i] = v; indices[i + 1] = v + 1; indices[i + 2] = v + 2;
            indices[i + 3] = v + 2; indices[i + 4] = v + 3; indices[i + 5] = v;
        }
        if (indexBuffer != 0) glDeleteBuffers(indexBuffer);
        indexBuffer = glCreateBuffers();
        glNamedBufferData(indexBuffer, indices, GL_STATIC_DRAW);
        indexCapacity = capacity;
    }

    public void draw() {
        glBindVertexArray(this.vao);
        glDrawElements(GL_TRIANGLES, this.quads * 6, GL_UNSIGNED_INT, 0);
    }

    public void free() {
        glDeleteVertexArrays(this.vao);
        glDeleteBuffers(this.vbo);
    }

    public static final class Builder {
        private ByteBuffer data = MemoryUtil.memAlloc(4096);
        private int vertices;
        private final RandomSource random = RandomSource.create(42);

        public void vertex(float x, float y, float z, float u, float v, int rgb, int face) {
            this.vertex(x, y, z, u, v, rgb, 255, 8, 248, face);
        }

        public void vertex(float x, float y, float z, float u, float v, int rgb, int alpha,
                           int blockLight, int skyLight, int face) {
            if (this.data.remaining() < STRIDE) this.data = MemoryUtil.memRealloc(this.data, this.data.capacity() * 2);
            this.data.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
            this.data.put((byte) blockLight).put((byte) skyLight);
            this.data.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) alpha);
            this.data.put((byte) face).put((byte) 0);
            this.vertices++;
        }

        public void blockModel(BlockState state, BakedModel model, BlockPos tintPos, float ox, float oy, float oz) {
            for (Direction direction : Direction.values()) {
                this.random.setSeed(42);
                for (BakedQuad quad : model.getQuads(state, direction, this.random)) {
                    this.quad(state, tintPos, quad, ox, oy, oz);
                }
            }
            this.random.setSeed(42);
            for (BakedQuad quad : model.getQuads(state, null, this.random)) {
                this.quad(state, tintPos, quad, ox, oy, oz);
            }
        }

        private void quad(BlockState state, BlockPos tintPos, BakedQuad quad, float ox, float oy, float oz) {
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            float shade = switch (quad.getDirection()) {
                case DOWN -> 0.5f;
                case NORTH, SOUTH -> 0.8f;
                case WEST, EAST -> 0.6f;
                default -> 1.0f;
            };
            int color = quad.isTinted() ? Minecraft.getInstance().getBlockColors().getColor(
                    state, Minecraft.getInstance().level, tintPos, quad.getTintIndex()) : 0xFFFFFF;
            if (color == -1) color = 0xFFFFFF;
            int r = Math.min(255, Math.round(((color >> 16) & 255) * shade));
            int g = Math.min(255, Math.round(((color >> 8) & 255) * shade));
            int b = Math.min(255, Math.round((color & 255) * shade));
            color = (r << 16) | (g << 8) | b;
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * stride;
                this.vertex(Float.intBitsToFloat(vertices[base]) + ox,
                        Float.intBitsToFloat(vertices[base + 1]) + oy,
                        Float.intBitsToFloat(vertices[base + 2]) + oz,
                        Float.intBitsToFloat(vertices[base + 4]),
                        Float.intBitsToFloat(vertices[base + 5]), color, quad.getDirection().ordinal());
            }
        }

        public DistantMesh build() {
            int quads = this.vertices / 4;
            if (quads == 0) {
                MemoryUtil.memFree(this.data);
                return null;
            }
            this.data.flip();
            this.data.limit(quads * 4 * STRIDE);
            try {
                return new DistantMesh(this.data, quads);
            } finally {
                MemoryUtil.memFree(this.data);
            }
        }
    }
}
