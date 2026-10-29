package com.bobsc;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.*;

/**
 * A visible builder character (singleplayer): joins the world, walks to the building's corner, waits for the
 * build command (key V / chat "!build" / settings button) and then builds the whole schematic block by block.
 * It is drawn by this class itself (own player-shaped model + the bundled skin), so it needs no entity API.
 * Blocks are placed on the integrated server directly -> no reach / permission / chat-spam limits.
 */
public class BotCharacter {
    public enum State { NONE, WALKING, WAITING, BUILDING, DONE }
    public static State state = State.NONE;

    static final String NAME = "Bob the Builder";
    static final Identifier SKIN = Identifier.of("bobsc", "textures/entity/bot.png");
    static final int FULL_LIGHT = 15728880;
    static final BufferAllocator ALLOC = new BufferAllocator(1 << 18);
    static final VertexConsumerProvider.Immediate IMM = VertexConsumerProvider.immediate(ALLOC);
    static final Random RNG = Random.create();

    static double x, y, z, px, py, pz, moved;
    static float yaw, pyaw, headYaw, pHeadYaw, headPitch, pHeadPitch;
    static float walkPhase, pWalkPhase, walkAmp, pWalkAmp, stepDist;
    static int swingTicks, placeCd, blocked, lingerTicks;
    static long lastTick = System.nanoTime();
    static boolean buildQueued, failedOnce, toPlayer;
    public static int speed = 1;                                   // 0 slow, 1 normal, 2 fast, 3 turbo
    static final int[] CD = {5, 2, 1, 0};
    static final double[] MOVE = {0.3, 0.4, 0.55, 0.8};
    public static String speedName() { return new String[]{"Slow", "Normal", "Fast", "Turbo"}[speed]; }
    public static void cycleSpeed() { speed = (speed + 1) % 4; }
    public static String status() {
        return switch (state) {
            case NONE -> "Not in the world";
            case WALKING -> toPlayer ? "Walking to you" : "Walking to the building corner";
            case WAITING -> "Waiting for the build command";
            case BUILDING -> "Building " + qi + "/" + total;
            case DONE -> "Finished";
        };
    }
    static List<BlockPos> queue = new ArrayList<>();
    static int qi, total;

    // ------------------------------------------------------------ HUD (shown through BossHud)
    public static boolean hudVisible() { return state == State.BUILDING || lingerTicks > 0; }
    public static String hudText() {
        int pct = total == 0 ? 100 : (int) (100f * qi / total);
        return NAME + " - " + (state == State.BUILDING ? "Building " + pct + "% (" + qi + "/" + total + ")" : "Done 100%");
    }
    public static float hudProgress() { return total == 0 ? 1f : Math.min(1f, (float) qi / total); }

    // ------------------------------------------------------------ commands
    public static void summon(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        if (BuildSession.blocks.isEmpty()) { BuilderBot.msg(mc, "§cChoose a building first (key B)"); return; }
        if (mc.getServer() == null) { BuilderBot.msg(mc, "§cThe bot character needs a singleplayer world"); return; }
        if (state != State.NONE) { BuilderBot.msg(mc, NAME + " is already here"); return; }
        Vec3d back = Vec3d.fromPolar(0f, mc.player.getYaw()).multiply(-4);
        x = mc.player.getX() + back.x; z = mc.player.getZ() + back.z;
        y = groundY(mc.world, x, z, mc.player.getY());
        px = x; py = y; pz = z;
        yaw = pyaw = headYaw = pHeadYaw = mc.player.getYaw();
        headPitch = pHeadPitch = 0; walkPhase = pWalkPhase = 0; walkAmp = pWalkAmp = 0; stepDist = 0;
        blocked = 0; buildQueued = false; qi = 0; total = 0; lingerTicks = 0; lastTick = System.nanoTime();
        toPlayer = false; state = State.WALKING;
        mc.player.sendMessage(Text.literal("§e" + NAME + " joined the game"), false);
        sound(SoundEvents.ENTITY_PLAYER_LEVELUP, x, y, z, SoundCategory.PLAYERS, 0.7f, 1.3f);
        BuilderBot.msg(mc, NAME + " is walking to the building. Give the build command: key V, or chat !build");
    }

    /** Removes the bot (if any) and spawns it again next to the player. */
    public static void respawn(MinecraftClient mc) {
        if (state != State.NONE) {
            sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, x, y, z, SoundCategory.PLAYERS, 0.7f, 1f);
            state = State.NONE; buildQueued = false;
        }
        summon(mc);
    }

    public static void comeToMe(MinecraftClient mc) {
        if (state == State.NONE) { summon(mc); return; }
        buildQueued = false; toPlayer = true; blocked = 0; state = State.WALKING;
        BuilderBot.msg(mc, NAME + " is coming to you");
    }

    public static void goToCorner(MinecraftClient mc) {
        if (state == State.NONE) { summon(mc); return; }
        buildQueued = false; toPlayer = false; blocked = 0; state = State.WALKING;
        BuilderBot.msg(mc, NAME + " is walking to the building corner");
    }

    public static void startBuild(MinecraftClient mc) {
        if (state == State.NONE) { BuilderBot.msg(mc, "§cNo helper yet: Build a building -> step 7 -> Helper bot builds"); return; }
        if (BuildSession.blocks.isEmpty()) { BuilderBot.msg(mc, "§cChoose a building first (key B)"); return; }
        if (mc.getServer() == null) { BuilderBot.msg(mc, "§cThe bot character needs a singleplayer world"); return; }
        if (state == State.BUILDING) { BuilderBot.msg(mc, NAME + " is already building"); return; }
        if (state == State.WALKING) {
            buildQueued = true;
            BuilderBot.msg(mc, NAME + " will start as soon as it arrives");
            return;
        }
        beginBuild(mc);
    }

    public static void stopBuild(MinecraftClient mc) {
        if (state != State.BUILDING) return;
        state = State.WAITING;
        sound(SoundEvents.ENTITY_VILLAGER_NO, x, y, z, SoundCategory.PLAYERS, 0.8f, 1f);
        BuilderBot.msg(mc, NAME + " stopped");
    }

    public static void leave(MinecraftClient mc) {
        if (state == State.NONE) return;
        sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, x, y, z, SoundCategory.PLAYERS, 0.7f, 1f);
        state = State.NONE; buildQueued = false;
        if (mc.player != null) mc.player.sendMessage(Text.literal("§e" + NAME + " left the game"), false);
    }

    /** Chat commands. Returns false when the message was ours (it is then not sent to the server). */
    public static boolean chat(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (s.equals("!build") || s.equals("!bot build")) startBuild(mc);
        else if (s.equals("!stop") || s.equals("!bot stop")) stopBuild(mc);
        else if (s.equals("!leave") || s.equals("!bot leave")) leave(mc);
        else if (s.equals("!come") || s.equals("!bot")) summon(mc);
        else return true;
        return false;
    }

    static void beginBuild(MinecraftClient mc) {
        Map<Integer, List<BlockPos>> byY = new TreeMap<>();
        for (BlockPos k : BuildSession.blocks.keySet())
            byY.computeIfAbsent(k.getY(), a -> new ArrayList<>()).add(k);
        queue = new ArrayList<>();
        for (List<BlockPos> l : byY.values()) {                       // layer by layer, snake rows
            l.sort(Comparator.comparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
            int i = 0, row = 0;
            while (i < l.size()) {
                int zz = l.get(i).getZ(), j = i;
                while (j < l.size() && l.get(j).getZ() == zz) j++;
                List<BlockPos> r = new ArrayList<>(l.subList(i, j));
                if (row++ % 2 == 1) Collections.reverse(r);
                queue.addAll(r);
                i = j;
            }
        }
        qi = 0; total = queue.size(); placeCd = 0; state = State.BUILDING;
        sound(SoundEvents.ENTITY_VILLAGER_YES, x, y, z, SoundCategory.PLAYERS, 0.9f, 1f);
        BuilderBot.msg(mc, NAME + " started building (" + total + " blocks)");
    }

    // ------------------------------------------------------------ tick
    public static void tick(MinecraftClient mc) {
        if (lingerTicks > 0) lingerTicks--;
        if (state == State.NONE) return;
        if (mc.world == null || mc.player == null) { state = State.NONE; return; }
        px = x; py = y; pz = z; pyaw = yaw; pHeadYaw = headYaw; pHeadPitch = headPitch;
        pWalkPhase = walkPhase; pWalkAmp = walkAmp;
        lastTick = System.nanoTime();
        moved = 0;
        if (swingTicks > 0) swingTicks--;
        ClientWorld w = mc.world;

        switch (state) {
            case WALKING -> {
                BlockPos o = BuildSession.origin;
                boolean arrived = toPlayer
                        ? walk(w, mc.player.getX(), mc.player.getZ(), 0.26, 2.5)
                        : walk(w, o.getX() - 0.5, o.getZ() - 0.5, 0.22, 0.2);
                if (arrived) {
                    state = State.WAITING;
                    sound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, x, y, z, SoundCategory.PLAYERS, 0.8f, 1.2f);
                    BuilderBot.msg(mc, toPlayer ? NAME + " is next to you"
                            : NAME + " is at the corner. Give the build command (key V / chat !build)");
                    if (buildQueued) { buildQueued = false; beginBuild(mc); }
                }
            }
            case WAITING, DONE -> lookAtPlayer(mc);
            case BUILDING -> buildTick(mc, w);
            default -> { }
        }

        // walk animation + footsteps
        walkPhase += (float) (moved * 2.6);
        walkAmp += ((moved > 0.01 ? 1f : 0f) - walkAmp) * 0.35f;
        if (moved > 0.01) {
            stepDist += (float) moved;
            if (stepDist > 1.6f) {
                stepDist = 0;
                BlockSoundGroup g = w.getBlockState(BlockPos.ofFloored(x, y - 0.5, z)).getSoundGroup();
                sound(g.getStepSound(), x, y, z, SoundCategory.PLAYERS, 0.3f, g.getPitch());
            }
        }
    }

    static void buildTick(MinecraftClient mc, ClientWorld w) {
        int guard = 0;                                                  // skip blocks that are already right
        while (qi < queue.size() && guard++ < 300) {
            BlockPos rel = queue.get(qi);
            BlockState want = BuildSession.blocks.get(rel);
            if (want == null || BuildSession.matches(w.getBlockState(BuildSession.origin.add(rel)), want)) qi++;
            else break;
        }
        if (qi >= queue.size()) { finish(mc); return; }

        BlockPos rel = queue.get(qi);
        BlockPos tgt = BuildSession.origin.add(rel);
        BlockState want = BuildSession.blocks.get(rel);

        // stand on top of the layer, a little behind the block (in the direction of the previous one)
        BlockPos prev = qi > 0 ? BuildSession.origin.add(queue.get(qi - 1)) : tgt.add(0, 0, -1);
        double bx = prev.getX() - tgt.getX(), bz = prev.getZ() - tgt.getZ();
        double bl = Math.hypot(bx, bz);
        if (bl < 0.01) { bx = 0; bz = -1; bl = 1; }
        double hx = tgt.getX() + 0.5 + bx / bl * 1.6, hz = tgt.getZ() + 0.5 + bz / bl * 1.6;
        double gy = groundY(w, hx, hz, tgt.getY());
        double hy = tgt.getY() > BuildSession.origin.getY() ? Math.max(gy, tgt.getY() + 1) : gy;

        double dx = hx - x, dz = hz - z, d = Math.hypot(dx, dz);
        if (d > 24) {                                                   // too far: pop over
            x = hx; z = hz; y = hy;
            sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, x, y, z, SoundCategory.PLAYERS, 0.5f, 1.2f);
        } else {
            double step = Math.min(MOVE[speed], d);
            if (d > 0.001) { x += dx / d * step; z += dz / d * step; moved += step; }
            y += MathHelper.clamp(hy - y, -0.45, 0.35);
        }

        Vec3d c = Vec3d.ofCenter(tgt);
        lookAt(c);
        yaw = approachAngle(yaw, headYaw, 30f);

        if (placeCd > 0) { placeCd--; return; }
        if (new Vec3d(x, y + 1.62, z).distanceTo(c) <= 4.5) {
            place(mc, tgt, want);
            qi++;
            placeCd = CD[speed];
        }
    }

    static void finish(MinecraftClient mc) {
        state = State.DONE; lingerTicks = 140;
        sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, x, y, z, SoundCategory.PLAYERS, 1f, 1f);
        BuilderBot.msg(mc, "§a" + NAME + " finished the building ✔");
    }

    static void place(MinecraftClient mc, BlockPos tgt, BlockState want) {
        MinecraftServer server = mc.getServer();
        if (server == null || mc.world == null) return;
        RegistryKey<World> key = mc.world.getRegistryKey();
        server.execute(() -> {
            ServerWorld sw = server.getWorld(key);
            if (sw != null) sw.setBlockState(tgt, want, Block.NOTIFY_LISTENERS);   // exact state, no neighbour popping
        });
        swingTicks = 6;
        BlockSoundGroup g = want.getSoundGroup();
        sound(g.getPlaceSound(), tgt.getX() + 0.5, tgt.getY() + 0.5, tgt.getZ() + 0.5,
                SoundCategory.BLOCKS, (g.getVolume() + 1f) / 2f, g.getPitch() * 0.8f);
    }

    // ------------------------------------------------------------ movement helpers
    static boolean solid(ClientWorld w, int bx, int by, int bz) {
        BlockPos p = new BlockPos(bx, by, bz);
        return !w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }

    static double groundY(ClientWorld w, double wx, double wz, double ref) {
        int bx = MathHelper.floor(wx), bz = MathHelper.floor(wz);
        int top = MathHelper.floor(ref) + 3, bottom = Math.max(w.getBottomY() + 1, MathHelper.floor(ref) - 12);
        for (int by = top; by >= bottom; by--)
            if (solid(w, bx, by - 1, bz) && !solid(w, bx, by, bz) && !solid(w, bx, by + 1, bz)) return by;
        return ref;
    }

    /** Walks towards (tx, tz) on the ground. Returns true when arrived. */
    static boolean walk(ClientWorld w, double tx, double tz, double speed, double stop) {
        double dx = tx - x, dz = tz - z, d = Math.hypot(dx, dz);
        if (d < stop) return true;
        double step = Math.min(speed, d);
        double nx = x + dx / d * step, nz = z + dz / d * step;
        double gy = groundY(w, nx, nz, y);
        if (gy - y > 1.01) {                                            // wall in front: try to slide along it
            double gx = groundY(w, nx, z, y), gz = groundY(w, x, nz, y);
            if (gx - y <= 1.01 && Math.abs(dx) > 0.05) { nz = z; gy = gx; }
            else if (gz - y <= 1.01 && Math.abs(dz) > 0.05) { nx = x; gy = gz; }
            else {
                if (++blocked > 40) {                                   // stuck: pop to the goal
                    x = tx; z = tz; y = groundY(w, tx, tz, y); blocked = 0;
                    sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, x, y, z, SoundCategory.PLAYERS, 0.6f, 1f);
                }
                return false;
            }
        }
        blocked = 0;
        float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
        yaw = approachAngle(yaw, ty, 28f);
        headYaw = yaw; headPitch = 0;
        moved += Math.hypot(nx - x, nz - z);
        x = nx; z = nz;
        y += MathHelper.clamp(gy - y, -0.5, 0.5);
        return false;
    }

    static void lookAtPlayer(MinecraftClient mc) {
        lookAt(mc.player.getEyePos());
        if (Math.abs(MathHelper.wrapDegrees(headYaw - yaw)) > 60f) yaw = approachAngle(yaw, headYaw, 8f);
    }

    static void lookAt(Vec3d t) {
        double dx = t.x - x, dy = t.y - (y + 1.62), dz = t.z - z;
        headYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        headPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }

    static float approachAngle(float cur, float target, float maxStep) {
        return cur + MathHelper.clamp(MathHelper.wrapDegrees(target - cur), -maxStep, maxStep);
    }

    static void sound(SoundEvent ev, double sx, double sy, double sz, SoundCategory cat, float vol, float pitch) {
        try {
            MinecraftClient.getInstance().getSoundManager()
                    .play(new PositionedSoundInstance(ev, cat, vol, pitch, RNG, sx, sy, sz));
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ rendering (player-shaped model, 64x64 skin)
    public static void render(WorldRenderContext ctx) {
        try { draw(); } catch (Throwable t) { if (!failedOnce) { failedOnce = true; t.printStackTrace(); } }
    }

    static void draw() {
        if (state == State.NONE) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        float t = Math.min(1f, (System.nanoTime() - lastTick) / 50_000_000f);
        double ix = px + (x - px) * t, iy = py + (y - py) * t, iz = pz + (z - pz) * t;
        float iyaw = pyaw + MathHelper.wrapDegrees(yaw - pyaw) * t;
        float hYaw = pHeadYaw + MathHelper.wrapDegrees(headYaw - pHeadYaw) * t;
        float hPitch = pHeadPitch + (headPitch - pHeadPitch) * t;
        float phase = pWalkPhase + (walkPhase - pWalkPhase) * t;
        float amp = pWalkAmp + (walkAmp - pWalkAmp) * t;
        float swing = (float) Math.sin(phase) * 45f * amp;
        float rArm = -swing, lArm = swing;
        if (swingTicks > 0) {
            float sp = (6 - swingTicks + t) / 6f;
            rArm = -(50f + 45f * (float) Math.sin(sp * Math.PI));
        }
        float headRel = MathHelper.clamp(MathHelper.wrapDegrees(hYaw - iyaw), -75f, 75f);

        MatrixStack ms = new MatrixStack();
        ms.translate(ix - cam.x, iy - cam.y, iz - cam.z);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-iyaw));      // model front = +Z, MC yaw 0 = +Z
        VertexConsumer vc = IMM.getBuffer(RenderLayer.getEntityCutoutNoCull(SKIN));

        part(ms, vc, -2, 12, 0, swing, 0, -4, 0, -2, 4, 12, 4, 0, 16, 0);          // right leg
        part(ms, vc, 2, 12, 0, -swing, 0, 0, 0, -2, 4, 12, 4, 16, 48, 0);          // left leg
        part(ms, vc, 0, 24, 0, 0, 0, -4, 12, -2, 8, 12, 4, 16, 16, 0);             // body
        part(ms, vc, -6, 22, 0, rArm, 0, -8, 12, -2, 4, 12, 4, 40, 16, 0);         // right arm
        part(ms, vc, 6, 22, 0, lArm, 0, 4, 12, -2, 4, 12, 4, 32, 48, 0);           // left arm
        part(ms, vc, 0, 24, 0, hPitch, -headRel, -4, 24, -4, 8, 8, 8, 0, 0, 0);    // head
        part(ms, vc, 0, 24, 0, hPitch, -headRel, -4, 24, -4, 8, 8, 8, 32, 0, 0.5f); // hat layer

        IMM.draw();
    }

    static void part(MatrixStack ms, VertexConsumer vc, float pvx, float pvy, float pvz, float rotX, float rotY,
                     float x0, float y0, float z0, int w, int h, int d, int u, int v, float inf) {
        ms.push();
        ms.translate(pvx / 16f, pvy / 16f, pvz / 16f);
        if (rotY != 0) ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(rotY));
        if (rotX != 0) ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rotX));
        ms.translate(-pvx / 16f, -pvy / 16f, -pvz / 16f);
        box(ms.peek(), vc, x0 - inf, y0 - inf, z0 - inf, x0 + w + inf, y0 + h + inf, z0 + d + inf, u, v, w, h, d);
        ms.pop();
    }

    /** Vanilla skin layout: top, bottom, right(-X) | front(+Z) | left(+X) | back(-Z). Coordinates in 1/16 block. */
    static void box(MatrixStack.Entry e, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1,
                    int u, int v, int w, int h, int d) {
        quad(e, vc, 0, 0, 1,  x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1,  u + d, v + d, u + d + w, v + d + h);
        quad(e, vc, 0, 0, -1, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0,  u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h);
        quad(e, vc, -1, 0, 0, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0,  u, v + d, u + d, v + d + h);
        quad(e, vc, 1, 0, 0,  x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1,  u + d + w, v + d, u + 2 * d + w, v + d + h);
        quad(e, vc, 0, 1, 0,  x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1,  u + d, v, u + d + w, v + d);
        quad(e, vc, 0, -1, 0, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0,  u + d + w, v, u + d + 2 * w, v + d);
    }

    static void quad(MatrixStack.Entry e, VertexConsumer vc, float nx, float ny, float nz,
                     float ax, float ay, float az, float bx, float by, float bz,
                     float cx, float cy, float cz, float dx, float dy, float dz,
                     float u0, float v0, float u1, float v1) {
        vert(e, vc, ax, ay, az, u0, v0, nx, ny, nz);
        vert(e, vc, bx, by, bz, u1, v0, nx, ny, nz);
        vert(e, vc, cx, cy, cz, u1, v1, nx, ny, nz);
        vert(e, vc, dx, dy, dz, u0, v1, nx, ny, nz);
    }

    static void vert(MatrixStack.Entry e, VertexConsumer vc, float x, float y, float z, float u, float v,
                     float nx, float ny, float nz) {
        vc.vertex(e, x / 16f, y / 16f, z / 16f)
          .color(255, 255, 255, 255)
          .texture(u / 64f, v / 64f)
          .overlay(OverlayTexture.DEFAULT_UV)
          .light(FULL_LIGHT)
          .normal(e, nx, ny, nz);
    }
}
