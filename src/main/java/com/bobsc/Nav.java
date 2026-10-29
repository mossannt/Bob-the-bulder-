package com.bobsc;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;

/**
 * Path-finder + executor for the player bot.
 *
 *  - A* over "feet cells": walk, step up (jump), drop down (max 3), and - when the way is blocked by air -
 *    BRIDGE (place a block in front, walk on it) and PILLAR (jump + place a block under the feet).
 *  - Every block it places for that is TEMPORARY: it is remembered in {@link #scaffold} and removed again by
 *    {@link #cleanup} (last placed -> first removed, so the bot always retreats over the way it came).
 *  - Lava, fire, cactus, magma, berry bushes, cobwebs and water are never entered.
 *  - Long ways are walked in several partial plans (the search is limited), unreachable goals report
 *    {@link #failed} so the caller can skip instead of walking into a wall forever.
 */
public class Nav {
    enum Move { WALK, UP, DOWN, BRIDGE, PILLAR }

    static final class Node {
        final int x, y, z;
        Node parent;
        Move mv = Move.WALK;
        double g, f;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        long key() { return BlockPos.asLong(x, y, z); }
    }

    /** What we walk to: a sphere around a point (measured from the eyes or from the feet) or one exact cell. */
    record Goal(Vec3d c, double dist, boolean feet, BlockPos ex, BlockPos exact) {
        String key() {
            if (exact != null) return "C" + exact.asLong();
            return "N" + c.x + "," + c.y + "," + c.z + "," + dist + (feet ? "f" : "e") + (ex == null ? "" : ex.asLong());
        }
        /** Cell may be used at all (not inside the target, not standing on a block we are about to remove). */
        boolean allowed(int x, int y, int z) {
            if (ex != null && ex.getX() == x && ex.getZ() == z && (ex.getY() == y || ex.getY() == y + 1)) return false;
            return avoid.isEmpty() || !avoid.contains(new BlockPos(x, y - 1, z));
        }
        boolean ok(int x, int y, int z) {
            if (exact != null) return exact.getX() == x && exact.getY() == y && exact.getZ() == z;
            if (!allowed(x, y, z)) return false;
            double dx = x + 0.5 - c.x, dy = y + (feet ? 0.5 : 1.62) - c.y, dz = z + 0.5 - c.z;
            return dx * dx + dy * dy + dz * dz <= dist * dist;
        }
        double h(int x, int y, int z) {
            if (exact != null) {
                double dy = exact.getY() - y;
                return Math.abs(exact.getX() - x) + Math.abs(exact.getZ() - z) + (dy > 0 ? dy * climb() : -dy);
            }
            double dx = x + 0.5 - c.x, dz = z + 0.5 - c.z;
            double dy = c.y - (y + (feet ? 0.5 : 1.62));
            double v = dy > 0 ? dy * climb() : -dy * 0.5;          // way up costs a pillar block per step, way down is cheap
            return Math.max(0, Math.hypot(dx, dz) + v - dist);
        }
    }

    /** Cost estimate of one block of height: with scaffold blocks we must pillar, otherwise (jumps / stairs) it is cheap. */
    static double climb() { return scaffoldOk ? 3.0 : 1.0; }

    static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    static final int MAX_EXP = 5000;

    /** The last goal could not be reached (read it right after a call that returned false). */
    public static boolean failed;
    /** Temporary blocks that could not be removed again. */
    public static int abandoned;
    /** Temporary blocks we placed (placement order). */
    public static final List<BlockPos> scaffold = new ArrayList<>();
    static final Set<BlockPos> avoid = new HashSet<>();

    static List<Node> path = new ArrayList<>();
    static BlockPos startCell = BlockPos.ORIGIN;
    static int idx, noProgress, replans, plans, airTicks, sx, sz;
    static String goalKey = "", failKey = "";
    static long failAt;
    static ClientWorld W;
    static boolean scaffoldOk;

    public static void reset() {
        path = new ArrayList<>(); idx = 0; goalKey = ""; failKey = ""; failed = false;
        noProgress = 0; replans = 0; plans = 0; airTicks = 0; avoid.clear();
    }

    // ------------------------------------------------------------ public goals
    /** Walk until the target block is within {@code dist} of the eyes. */
    public static boolean goNear(MinecraftClient mc, BlockPos t, double dist, boolean scaffolding) {
        return go(mc, new Goal(Vec3d.ofCenter(t), dist, false, t, null), scaffolding);
    }

    /** Walk until the feet are within {@code dist} of the point (item pick-up). */
    public static boolean goFeet(MinecraftClient mc, double x, double y, double z, double dist) {
        return go(mc, new Goal(new Vec3d(x, y, z), dist, true, null, null), false);
    }

    /** Walk to exactly this feet cell (a floor is built under it when needed). */
    public static boolean goCell(MinecraftClient mc, BlockPos cell) {
        return go(mc, new Goal(Vec3d.ofCenter(cell), 0, false, null, cell), true);
    }

    public static boolean cellFree(ClientWorld w, BlockPos c) {
        W = w;
        return bodyFree(c.getX(), c.getY(), c.getZ());
    }

    // ------------------------------------------------------------ world queries
    // facts about a cell, cached while a plan is searched (bit 1 solid, 2 hazard, 4 fluid, 8 low enough to stand on)
    static final Map<Long, Byte> CACHE = new HashMap<>();
    static boolean caching;
    static final BlockPos.Mutable MP = new BlockPos.Mutable();

    static byte info(int x, int y, int z) {
        long k = 0;
        if (caching) { k = BlockPos.asLong(x, y, z); Byte c = CACHE.get(k); if (c != null) return c; }
        MP.set(x, y, z);
        BlockState s = W.getBlockState(MP);
        byte f = 0;
        VoxelShape sh = s.getCollisionShape(W, MP);
        Block b = s.getBlock();
        if (!sh.isEmpty() && !openable(s, x, y, z)) { f |= 1; if (sh.getMax(Direction.Axis.Y) <= 1.0001) f |= 8; }
        if (b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CACTUS
                || b == Blocks.MAGMA_BLOCK || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.CAMPFIRE
                || b == Blocks.SOUL_CAMPFIRE || b == Blocks.WITHER_ROSE || b == Blocks.POWDER_SNOW
                || b == Blocks.COBWEB) f |= 2;
        if (!s.getFluidState().isEmpty()) f |= 4;
        if (caching) CACHE.put(k, f);
        return f;
    }

    /** A closed wooden door / fence gate that is not part of the building: the bot opens it and walks through. */
    static boolean openable(BlockState s, int x, int y, int z) {
        Block b = s.getBlock();
        if (!(b instanceof DoorBlock || b instanceof FenceGateBlock) || b == Blocks.IRON_DOOR) return false;
        if (!s.contains(Properties.OPEN) || s.get(Properties.OPEN)) return false;
        return !BuildSession.blocks.containsKey(new BlockPos(x, y, z).subtract(BuildSession.origin));
    }

    static boolean solid(int x, int y, int z) { return (info(x, y, z) & 1) != 0; }

    static boolean hazard(int x, int y, int z) { return (info(x, y, z) & 2) != 0; }

    static boolean wet(int x, int y, int z) { return (info(x, y, z) & 4) != 0; }

    static boolean free(int x, int y, int z) { return (info(x, y, z) & 7) == 0; }

    static boolean bodyFree(int x, int y, int z) { return free(x, y, z) && free(x, y + 1, z); }

    /** Something to stand on (not higher than a full block, not dangerous, not marked for removal). */
    static boolean floorOk(int x, int y, int z) {
        byte f = info(x, y - 1, z);
        if ((f & 1) == 0 || (f & 8) == 0 || (f & 2) != 0) return false;
        return avoid.isEmpty() || !avoid.contains(new BlockPos(x, y - 1, z));
    }

    static boolean loaded(int x, int z) {
        return W.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
    }

    static double footprint(BlockPos abs) {
        return BuildSession.blocks.containsKey(abs.subtract(BuildSession.origin)) ? 1.5 : 0;
    }

    // ------------------------------------------------------------ main entry
    static boolean go(MinecraftClient mc, Goal g, boolean allowScaffold) {
        ClientPlayerEntity p = mc.player;
        W = mc.world;
        int cx = MathHelper.floor(p.getX()), cz = MathHelper.floor(p.getZ()), cy = MathHelper.floor(p.getY() + 0.5);

        if (Elytra.state != Elytra.State.NONE) {                 // a flight is running: it steers, nothing else
            if (Elytra.tick(mc)) { failed = false; return false; }
        }
        if (g.exact == null) {                                   // already close enough?
            Vec3d s = g.feet ? new Vec3d(p.getX(), p.getY() + 0.5, p.getZ()) : p.getEyePos();
            if (s.distanceTo(g.c) <= g.dist && g.allowed(cx, cy, cz)) { failed = false; return true; }
        }
        if (g.ok(cx, cy, cz)) {                                  // right cell, maybe not right spot inside it
            failed = false;
            if (g.exact != null) return true;
            if (Math.hypot(cx + 0.5 - p.getX(), cz + 0.5 - p.getZ()) < 0.15) return true;
            walkToward(mc, p, cx + 0.5, cz + 0.5, false, false, false);
            return false;
        }

        if (Elytra.tryStart(mc, g) && Elytra.tick(mc)) { failed = false; return false; }   // far / high: fly

        String key = g.key() + (allowScaffold ? "S" : "N");
        if (key.equals(failKey) && System.currentTimeMillis() - failAt < 4000) { failed = true; return false; }
        if (!key.equals(goalKey)) { goalKey = key; replans = 0; plans = 0; path = new ArrayList<>(); idx = 0; }
        failed = false;

        if (path.isEmpty() || idx >= path.size()) {
            if (p.isOnGround()) airTicks = 0;
            else if (!p.isTouchingWater() && ++airTicks < 40) return false;   // wait until we land
            if (++plans > 40) { fail(); return false; }
            scaffoldOk = allowScaffold && BuilderBot.hasScaffold(mc);
            List<Node> np = plan(mc, g);
            if (np == null || np.isEmpty()) { fail(); return false; }
            path = np; idx = 0; noProgress = 0;
        }
        follow(mc, g);
        return false;
    }

    static void fail() {
        failed = true; failKey = goalKey; failAt = System.currentTimeMillis();
        path = new ArrayList<>(); idx = 0;
    }

    // ------------------------------------------------------------ A*
    static List<Node> plan(MinecraftClient mc, Goal g) {
        CACHE.clear(); caching = true;
        try { return plan0(mc, g); }
        finally { caching = false; CACHE.clear(); }
    }

    static List<Node> plan0(MinecraftClient mc, Goal g) {
        ClientPlayerEntity p = mc.player;
        int cx = MathHelper.floor(p.getX()), cz = MathHelper.floor(p.getZ()), cy = MathHelper.floor(p.getY() + 0.5);
        sx = cx; sz = cz;
        startCell = new BlockPos(cx, cy, cz);

        Node start = new Node(cx, cy, cz);
        start.f = 1.5 * g.h(cx, cy, cz);
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble((Node a) -> a.f));
        Map<Long, Node> seen = new HashMap<>();
        seen.put(start.key(), start);
        open.add(start);
        Node best = start;
        double bestH = g.h(cx, cy, cz);
        int exp = 0;
        while (!open.isEmpty() && exp < MAX_EXP) {
            Node n = open.poll();
            if (seen.get(n.key()) != n) continue;               // outdated entry
            exp++;
            if (n != start && g.ok(n.x, n.y, n.z)) return trace(n);
            double hh = g.h(n.x, n.y, n.z);
            if (hh < bestH - 1e-6) { bestH = hh; best = n; }
            expand(n, g, open, seen);
        }
        return best == start ? null : trace(best);              // limit reached: walk to the closest point, plan again
    }

    static List<Node> trace(Node n) {
        List<Node> out = new ArrayList<>();
        for (Node c = n; c.parent != null; c = c.parent) out.add(c);
        Collections.reverse(out);
        return out;
    }

    static void relax(Node from, int x, int y, int z, Move mv, double cost, Goal g,
                      PriorityQueue<Node> open, Map<Long, Node> seen) {
        double ng = from.g + cost;
        long k = BlockPos.asLong(x, y, z);
        Node old = seen.get(k);
        if (old != null && old.g <= ng) return;
        Node nn = new Node(x, y, z);
        nn.parent = from; nn.mv = mv; nn.g = ng; nn.f = ng + 1.5 * g.h(x, y, z);
        seen.put(k, nn);
        open.add(nn);
    }

    static void expand(Node n, Goal g, PriorityQueue<Node> open, Map<Long, Node> seen) {
        int lo = W.getBottomY() + 1, hi = W.getBottomY() + W.getHeight() - 3;
        for (int[] d : DIRS) {
            int nx = n.x + d[0], nz = n.z + d[1];
            if (Math.abs(nx - sx) > 96 || Math.abs(nz - sz) > 96 || !loaded(nx, nz)) continue;
            if (bodyFree(nx, n.y, nz)) {
                if (floorOk(nx, n.y, nz)) { relax(n, nx, n.y, nz, Move.WALK, 1.0, g, open, seen); continue; }
                boolean landed = false;                          // a drop of up to 3 blocks
                for (int j = 1; j <= 3; j++) {
                    int y2 = n.y - j;
                    if (y2 < lo || !free(nx, y2, nz)) break;
                    if (floorOk(nx, y2, nz)) {
                        relax(n, nx, y2, nz, Move.DOWN, 1.0 + 0.7 * j, g, open, seen);
                        landed = true;
                        break;
                    }
                }
                if (!landed && scaffoldOk && !solid(nx, n.y - 1, nz)) {          // nothing there: build a bridge
                    relax(n, nx, n.y, nz, Move.BRIDGE, 3.0 + footprint(new BlockPos(nx, n.y - 1, nz)), g, open, seen);
                }
            } else if (n.y + 1 < hi && solid(nx, n.y, nz) && floorOk(nx, n.y + 1, nz)
                    && bodyFree(nx, n.y + 1, nz) && free(n.x, n.y + 2, n.z)) {
                relax(n, nx, n.y + 1, nz, Move.UP, 1.6, g, open, seen);          // one block up: jump
            }
        }
        if (scaffoldOk && n.y + 1 < hi && free(n.x, n.y + 2, n.z)) {              // pillar up
            relax(n, n.x, n.y + 1, n.z, Move.PILLAR, 3.5 + footprint(new BlockPos(n.x, n.y, n.z)), g, open, seen);
        }
    }

    // ------------------------------------------------------------ executor
    static boolean stepValid(Node n) {
        if (n.mv == Move.BRIDGE || n.mv == Move.PILLAR) return bodyFree(n.x, n.y, n.z);
        return bodyFree(n.x, n.y, n.z) && floorOk(n.x, n.y, n.z);
    }

    static void follow(MinecraftClient mc, Goal g) {
        ClientPlayerEntity p = mc.player;
        int cx = MathHelper.floor(p.getX()), cz = MathHelper.floor(p.getZ()), cy = MathHelper.floor(p.getY() + 0.5);
        for (int j = Math.min(path.size() - 1, idx + 3); j >= idx; j--) {        // maybe we are already further
            Node a = path.get(j);
            if (a.x == cx && a.y == cy && a.z == cz && p.isOnGround()) { idx = j + 1; noProgress = 0; break; }
        }
        if (idx >= path.size()) return;
        Node n = path.get(idx);
        BlockPos from = startCell;
        if (idx > 0) { Node a = path.get(idx - 1); from = new BlockPos(a.x, a.y, a.z); }

        if (++noProgress > 100) {                                // stuck: plan again, give up after a few times
            if (++replans > 4) fail(); else { path = new ArrayList<>(); idx = 0; }
            return;
        }
        if (!stepValid(n)) { path = new ArrayList<>(); idx = 0; return; }       // world changed

        if (openDoor(mc, p, n)) return;
        switch (n.mv) {
            case WALK, DOWN -> {
                Node nx2 = idx + 1 < path.size() ? path.get(idx + 1) : null;
                boolean sprint = n.mv == Move.WALK && nx2 != null && nx2.mv == Move.WALK && !g.feet;
                walkToward(mc, p, n.x + 0.5, n.z + 0.5, sprint, false, false);
            }
            case UP -> {
                boolean near = Math.hypot(n.x + 0.5 - p.getX(), n.z + 0.5 - p.getZ()) < 1.5;
                walkToward(mc, p, n.x + 0.5, n.z + 0.5, false, near && p.isOnGround(), false);
            }
            case BRIDGE -> bridge(mc, p, from, n);
            case PILLAR -> pillar(mc, p, from, n);
        }
    }

    /** Opens a closed door / gate in the next cell (right click). */
    static boolean openDoor(MinecraftClient mc, ClientPlayerEntity p, Node n) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos q = new BlockPos(n.x, n.y + dy, n.z);
            if (!openable(W.getBlockState(q), q.getX(), q.getY(), q.getZ())) continue;
            Vec3d hit = Vec3d.ofCenter(q);
            if (p.getEyePos().distanceTo(hit) > 4.0) continue;
            BuilderBot.setSneak(mc, false);
            BuilderBot.face(p, hit);
            BuilderBot.sendLook(mc, p);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, q, false));
            p.swingHand(Hand.MAIN_HAND);
            BuilderBot.wait = 4;
            return true;
        }
        return false;
    }

    static void walkToward(MinecraftClient mc, ClientPlayerEntity p, double tx, double tz,
                           boolean sprint, boolean jump, boolean sneak) {
        double dx = tx - p.getX(), dz = tz - p.getZ();
        p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f);
        p.setPitch(0);
        mc.options.forwardKey.setPressed(true);
        if (sprint && !sneak) mc.options.sprintKey.setPressed(true);
        if (jump || (p.horizontalCollision && p.isOnGround())) mc.options.jumpKey.setPressed(true);
        BuilderBot.setSneak(mc, sneak);
    }

    static Direction dirOf(int dx, int dz) {
        return dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Place a temporary block in front of us (on the side of the block we stand on) and walk onto it. */
    static void bridge(MinecraftClient mc, ClientPlayerEntity p, BlockPos from, Node n) {
        int cx = MathHelper.floor(p.getX()), cz = MathHelper.floor(p.getZ()), cy = MathHelper.floor(p.getY() + 0.5);
        if (!(cx == from.getX() && cz == from.getZ() && cy == from.getY())) {
            walkToward(mc, p, from.getX() + 0.5, from.getZ() + 0.5, false, false, true);
            return;
        }
        BlockPos floor = new BlockPos(n.x, n.y - 1, n.z);
        if (!W.getBlockState(floor).getCollisionShape(W, floor).isEmpty()) {     // block is there: step on it (sneak = no fall)
            walkToward(mc, p, n.x + 0.5, n.z + 0.5, false, false, true);
            return;
        }
        BuilderBot.setSneak(mc, true);                           // sneak: never falls off, never opens a chest by accident
        if (!p.isSneaking()) return;
        int slot = BuilderBot.ensureScaffold(mc);
        if (slot < 0) { if (!BuilderBot.pendingSpawn) { path = new ArrayList<>(); idx = 0; } return; }
        BuilderBot.selectInv(mc, p, slot);
        BlockPos nb = from.down();
        Direction side = dirOf(n.x - from.getX(), n.z - from.getZ());
        Vec3d hit = BuilderBot.hitPos(nb, side, 0.5);
        BuilderBot.face(p, hit);
        BuilderBot.sendLook(mc, p);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, side, nb, false));
        p.swingHand(Hand.MAIN_HAND);
        if (!scaffold.contains(floor)) scaffold.add(floor);
        BuilderBot.wait = 2;
    }

    /** Jump and put a temporary block under the feet. */
    static void pillar(MinecraftClient mc, ClientPlayerEntity p, BlockPos from, Node n) {
        int cx = MathHelper.floor(p.getX()), cz = MathHelper.floor(p.getZ());
        if (!(cx == from.getX() && cz == from.getZ())) {
            walkToward(mc, p, from.getX() + 0.5, from.getZ() + 0.5, false, false, true);
            return;
        }
        double off = Math.hypot(from.getX() + 0.5 - p.getX(), from.getZ() + 0.5 - p.getZ());
        if (off > 0.25 && p.isOnGround()) {                      // centre first, so the new block is under both feet
            walkToward(mc, p, from.getX() + 0.5, from.getZ() + 0.5, false, false, true);
            return;
        }
        BuilderBot.setSneak(mc, true);
        if (!p.isSneaking()) return;
        int slot = BuilderBot.ensureScaffold(mc);
        if (slot < 0) { if (!BuilderBot.pendingSpawn) { path = new ArrayList<>(); idx = 0; } return; }
        BuilderBot.selectInv(mc, p, slot);
        BlockState here = W.getBlockState(from);
        if (p.getY() >= from.getY() + 1.0 + 0.001) {             // high enough: the block fits under us
            if (!(here.isAir() || here.isReplaceable())) return;     // already placed: just land on it
            BlockPos nb = from.down();
            Vec3d hit = BuilderBot.hitPos(nb, Direction.UP, 0.5);
            BuilderBot.face(p, hit);
            BuilderBot.sendLook(mc, p);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, nb, false));
            p.swingHand(Hand.MAIN_HAND);
            if (!scaffold.contains(from)) scaffold.add(from);
        } else if (p.isOnGround()) {
            mc.options.jumpKey.setPressed(true);
        }
    }

    // ------------------------------------------------------------ clean-up of the temporary blocks
    static boolean safeDrop(BlockPos t) {
        for (int k = 1; k <= 4; k++) {
            int y = t.getY() - k;
            if (solid(t.getX(), y, t.getZ())) return !hazard(t.getX(), y, t.getZ());
            if (hazard(t.getX(), y, t.getZ()) || wet(t.getX(), y, t.getZ())) return false;
        }
        return false;
    }

    public static void abandonAll() {
        abandoned += scaffold.size();
        scaffold.clear();
        avoid.clear();
    }

    /**
     * Removes the temporary blocks, last placed first. Standing on the block: break it when we fall at most
     * 3 blocks, otherwise step off first (bridge over a void: go back one block, break the end). Returns true when done.
     */
    public static boolean cleanup(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        W = mc.world;
        scaffold.removeIf(b -> {                                 // gone, or now part of the finished building
            BlockState s = W.getBlockState(b);
            if (s.isAir() || !s.getFluidState().isEmpty()) return true;
            BlockState want = BuildSession.blocks.get(b.subtract(BuildSession.origin));
            return want != null && BuildSession.matches(s, want);
        });
        avoid.clear();
        if (scaffold.isEmpty()) return true;

        BlockPos t = scaffold.get(scaffold.size() - 1);
        Vec3d c = Vec3d.ofCenter(t);
        int cx = MathHelper.floor(p.getX()), cz = MathHelper.floor(p.getZ()), cy = MathHelper.floor(p.getY() + 0.5);
        boolean on = cx == t.getX() && cz == t.getZ() && cy - 1 == t.getY();

        if (on && safeDrop(t)) {
            BuilderBot.setSneak(mc, false);
            BuilderBot.breakBlock(mc, t);
            return false;
        }
        if (!on && p.getEyePos().distanceTo(c) <= 4.3) {
            BuilderBot.setSneak(mc, false);
            BuilderBot.breakBlock(mc, t);
            return false;
        }
        avoid.add(t);                                            // go (or step off) without using the block itself
        go(mc, new Goal(c, 4.0, false, t, null), false);
        if (failed) {                                            // cannot get there: leave this one
            scaffold.remove(t);
            abandoned++;
            failed = false; failKey = "";
        }
        return false;
    }
}
