package com.bobsc;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Litematica-style hologram:
 *  - GHOST: the real block models, semi-transparent, only for blocks still missing / wrong
 *  - outline: blue = missing, red = wrong block already standing there
 */
public class HologramRenderer {
    public static final int GHOST = 0, WIREFRAME = 1, BOTH = 2;
    public static int mode = BOTH;

    static final int RANGE = 48;
    static final int MAX_BOXES = 6000;
    static final int MAX_GHOST = 3000;
    static final float GHOST_ALPHA = 0.45f;

    // our OWN buffer: vanilla's shared one is flushed before/after this event, which is why nothing showed
    static final BufferAllocator ALLOC = new BufferAllocator(1 << 20);
    static final VertexConsumerProvider.Immediate IMM = VertexConsumerProvider.immediate(ALLOC);

    static final Random RNG = Random.create();
    static final List<BlockModelPart> PARTS = new ArrayList<>();
    static Map<BlockPos, BlockState> cacheKey;
    static final List<BlockPos> surface = new ArrayList<>();   // schematic blocks with an exposed face

    public static void render(WorldRenderContext ctx) {
        try {
            draw(ctx);
        } catch (Throwable t) {
            if (!failedOnce) { failedOnce = true; t.printStackTrace(); }
        }
    }
    static boolean failedOnce = false;

    static void rebuildSurface() {
        surface.clear();
        for (BlockPos k : BuildSession.blocks.keySet()) {
            boolean exposed = false;
            for (Direction d : Direction.values())
                if (!BuildSession.blocks.containsKey(k.offset(d))) { exposed = true; break; }
            if (exposed) surface.add(k);
        }
        cacheKey = BuildSession.blocks;
    }

    static void draw(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        if (BuildSession.blocks.isEmpty() && BuildSession.chests.isEmpty() && !Digger.hasArea()) return;
        if (cacheKey != BuildSession.blocks) rebuildSurface();

        // matrices() can be null in this event on 1.21.11 -> the old code returned and drew nothing.
        // Camera-relative coordinates + a fresh stack is exactly what vanilla does for entities.
        MatrixStack ms = new MatrixStack();
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        long r2 = (long) RANGE * RANGE;

        BlockRenderManager brm = mc.getBlockRenderManager();
        Ghost world = new Ghost(mc);
        VertexConsumer ghostBuf = null;
        VertexConsumer lines = IMM.getBuffer(RenderLayer.getLines());
        int ghosts = 0, drawn = 0;

        for (BlockPos rel : surface) {
            BlockState want = BuildSession.blocks.get(rel);
            if (want == null) continue;
            BlockPos pos = BuildSession.origin.add(rel);
            if (pos.getSquaredDistance(cam) > r2) continue;
            BlockState have = mc.world.getBlockState(pos);
            if (BuildSession.matches(have, want)) continue;          // already built correctly

            boolean wrong = !have.isAir() && have.getBlock() != want.getBlock();

            if (mode != WIREFRAME && ghosts < MAX_GHOST && want.getRenderType() == BlockRenderType.MODEL) {
                if (ghostBuf == null)
                    ghostBuf = new AlphaConsumer(IMM.getBuffer(RenderLayer.getTranslucent()), GHOST_ALPHA);
                ms.push();
                ms.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
                PARTS.clear();
                RNG.setSeed(want.getRenderingSeed(pos));
                brm.getModel(want).addParts(RNG, PARTS);
                brm.renderBlock(want, pos, world, ms, ghostBuf, true, PARTS);
                ms.pop();
                ghosts++;
            }
            if (mode != GHOST && drawn < MAX_BOXES) {
                if (wrong) box(ms, lines, pos, cam, 1f, 0.2f, 0.2f, 1f);
                else box(ms, lines, pos, cam, 0.35f, 0.8f, 1f, 0.9f);
                drawn++;
            }
        }

        // chest markers: red = no chest, yellow = chest not scanned, green = scanned
        for (BlockPos c : BuildSession.chests) {
            float r = 1, g = 0, b = 0;
            if (BuildSession.checked.contains(c)) { r = 0; g = 1; }
            else if (mc.world.getBlockState(c).getBlock() instanceof ChestBlock) { r = 1; g = 1; }
            box(ms, lines, c, cam, r, g, b, 1f);
        }

        Digger.drawArea(ms, lines, cam);

        // elytra: the cell the bot will land on (cyan box + beam up into the sky)
        BlockPos st = Elytra.stand;
        if (st != null) {
            box(ms, lines, st, cam, 0.1f, 1f, 1f, 1f);
            VertexRendering.drawBox(ms, lines,
                    st.getX() + 0.4 - cam.x, st.getY() - cam.y, st.getZ() + 0.4 - cam.z,
                    st.getX() + 0.6 - cam.x, st.getY() + 60 - cam.y, st.getZ() + 0.6 - cam.z,
                    0.1f, 1f, 1f, 0.8f);
        }

        IMM.draw();     // flush everything ourselves
    }

    static void box(MatrixStack ms, VertexConsumer lines, BlockPos p, Vec3d cam, float r, float g, float b, float a) {
        VertexRendering.drawBox(ms, lines,
                p.getX() - cam.x, p.getY() - cam.y, p.getZ() - cam.z,
                p.getX() + 1 - cam.x, p.getY() + 1 - cam.y, p.getZ() + 1 - cam.z,
                r, g, b, a);
    }

    /** Forces a fixed alpha on every vertex so block models become see-through. */
    static class AlphaConsumer implements VertexConsumer {
        final VertexConsumer d; final int alpha;
        AlphaConsumer(VertexConsumer d, float a) { this.d = d; this.alpha = (int) (a * 255); }
        public VertexConsumer vertex(float x, float y, float z) { d.vertex(x, y, z); return this; }
        public VertexConsumer color(int r, int g, int b, int a) { d.color(r, g, b, alpha); return this; }
        public VertexConsumer texture(float u, float v) { d.texture(u, v); return this; }
        public VertexConsumer overlay(int u, int v) { d.overlay(u, v); return this; }
        public VertexConsumer light(int u, int v) { d.light(u, v); return this; }
        public VertexConsumer normal(float x, float y, float z) { d.normal(x, y, z); return this; }
    }

    /** World view where schematic blocks count as neighbours (so faces between hologram blocks are culled) and light is full-bright. */
    static class Ghost implements BlockRenderView {
        final MinecraftClient mc;
        Ghost(MinecraftClient mc) { this.mc = mc; }

        public BlockState getBlockState(BlockPos p) {
            BlockState s = BuildSession.blocks.get(p.subtract(BuildSession.origin));
            return s != null ? s : mc.world.getBlockState(p);
        }
        public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos p) { return null; }
        public int getHeight() { return mc.world.getHeight(); }
        public int getBottomY() { return mc.world.getBottomY(); }
        public float getBrightness(Direction d, boolean shaded) { return mc.world.getBrightness(d, shaded); }
        public LightingProvider getLightingProvider() { return mc.world.getLightingProvider(); }
        public int getColor(BlockPos p, ColorResolver r) { return mc.world.getColor(p, r); }
        public int getLightLevel(LightType t, BlockPos p) { return 15; }
        public int getBaseLightLevel(BlockPos p, int ambient) { return 15; }
    }
}
