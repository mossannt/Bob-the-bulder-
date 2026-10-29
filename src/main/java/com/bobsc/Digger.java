package com.bobsc;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;

/**
 * Digger: mark area A and area B (two opposite corners), the bot digs everything in between, layer by layer from the
 * top, snake pattern. It reports how many chests the drops need. Resources are either collected into chests (placed
 * next to the area, chest items from the inventory) or left on the ground.
 */
public class Digger {
    public static final long MAX_VOLUME = 1_000_000L;

    public static BlockPos a, b;
    public static boolean collect = true;
    /** Resource filter: drops of these items are not collected / are thrown away. */
    public static final Set<Item> unwanted = new HashSet<>();

    // ---- scan result
    public static long volume;
    public static int toDig, unbreakable;
    public static boolean unloaded;
    public static String error = "";
    public static final Map<Item, Integer> drops = new TreeMap<>(Comparator.comparing((Item i) -> Registries.ITEM.getId(i)));

    // ---- run state
    static int minX, minY, minZ, maxX, maxY, maxZ;
    static BlockPos anchor = BlockPos.ORIGIN, cur;
    static int cx, cy, cz, dx = 1, done, total, skippedCount, stuck, mineTicks;
    static boolean exhausted, finishing, lavaWarned;
    static int dodge, dodgeTries;
    static final Set<BlockPos> skipped = new HashSet<>();

    public static boolean hasArea() { return a != null || b != null; }
    public static boolean ready() { return a != null && b != null; }
    public static boolean running() { return BuilderBot.mode == BuilderBot.Mode.DIG; }

    static void bounds() {
        minX = Math.min(a.getX(), b.getX()); maxX = Math.max(a.getX(), b.getX());
        minY = Math.min(a.getY(), b.getY()); maxY = Math.max(a.getY(), b.getY());
        minZ = Math.min(a.getZ(), b.getZ()); maxZ = Math.max(a.getZ(), b.getZ());
    }

    /** The block the player looks at, otherwise the block under the feet. */
    public static BlockPos lookTarget(MinecraftClient mc) {
        if (mc.crosshairTarget instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK) return h.getBlockPos();
        return mc.player.getBlockPos().down();
    }

    // ------------------------------------------------------------ scan / chest estimate
    static boolean isTarget(ClientWorld w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return !s.isAir() && !(s.getBlock() instanceof FluidBlock) && s.getHardness(w, p) >= 0;
    }

    public static void scan(MinecraftClient mc) {
        drops.clear(); toDig = 0; unbreakable = 0; volume = 0; error = ""; unloaded = false;
        if (!ready() || mc.world == null) return;
        bounds();
        volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_VOLUME) { error = "Area too large (max " + MAX_VOLUME + " blocks)"; return; }
        ClientWorld w = mc.world;
        for (int x = minX >> 4; x <= maxX >> 4; x++)
            for (int z = minZ >> 4; z <= maxZ >> 4; z++)
                if (w.getChunk(x, z, ChunkStatus.FULL, false) == null) unloaded = true;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = minY; y <= maxY; y++)
            for (int z = minZ; z <= maxZ; z++)
                for (int x = minX; x <= maxX; x++) {
                    m.set(x, y, z);
                    BlockState s = w.getBlockState(m);
                    if (s.isAir() || s.getBlock() instanceof FluidBlock) continue;
                    if (s.getHardness(w, m) < 0) { unbreakable++; continue; }
                    toDig++;
                    addDrop(drops, s);
                }
    }

    static Item item(String n) { return Registries.ITEM.get(Identifier.of("minecraft", n)); }

    /** Approximation of what breaking the block gives (no silk touch / fortune). */
    static void addDrop(Map<Item, Integer> m, BlockState s) {
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return;
        if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD) return;
        String id = Registries.BLOCK.getId(s.getBlock()).getPath();
        String out = id;
        int n = 1;
        switch (id) {
            case "stone" -> out = "cobblestone";
            case "deepslate" -> out = "cobbled_deepslate";
            case "grass_block", "podzol", "mycelium", "dirt_path", "farmland", "rooted_dirt" -> out = "dirt";
            case "coal_ore", "deepslate_coal_ore" -> out = "coal";
            case "iron_ore", "deepslate_iron_ore" -> out = "raw_iron";
            case "copper_ore", "deepslate_copper_ore" -> { out = "raw_copper"; n = 3; }
            case "gold_ore", "deepslate_gold_ore" -> out = "raw_gold";
            case "nether_gold_ore" -> { out = "gold_nugget"; n = 4; }
            case "diamond_ore", "deepslate_diamond_ore" -> out = "diamond";
            case "emerald_ore", "deepslate_emerald_ore" -> out = "emerald";
            case "lapis_ore", "deepslate_lapis_ore" -> { out = "lapis_lazuli"; n = 6; }
            case "redstone_ore", "deepslate_redstone_ore" -> { out = "redstone"; n = 4; }
            case "nether_quartz_ore" -> out = "quartz";
            case "glowstone" -> { out = "glowstone_dust"; n = 3; }
            case "clay" -> { out = "clay_ball"; n = 4; }
            case "bookshelf" -> { out = "book"; n = 3; }
            case "ice", "packed_ice", "blue_ice", "tinted_glass", "short_grass", "tall_grass", "fern", "large_fern",
                 "seagrass", "tall_seagrass", "vine", "spawner" -> { return; }
            default -> { if (id.endsWith("glass") || id.endsWith("glass_pane") || id.contains("leaves")) return; }
        }
        Item it = item(out);
        if (it == Items.AIR) it = s.getBlock().asItem();
        if (it == Items.AIR) return;
        m.merge(it, n, Integer::sum);
    }

    public static int stacks() {
        int t = 0;
        for (var e : drops.entrySet()) { if (unwanted.contains(e.getKey())) continue; int max = Math.max(1, e.getKey().getMaxCount()); t += (e.getValue() + max - 1) / max; }
        return t;
    }

    public static int chestsNeeded() { int s = stacks(); return s == 0 ? 0 : (s + 26) / 27; }

    public static int chestsInInventory(MinecraftClient mc) {
        int n = 0;
        if (mc.player != null)
            for (ItemStack s : mc.player.getInventory().getMainStacks()) if (s.isOf(Items.CHEST)) n += s.getCount();
        return n;
    }

    // ------------------------------------------------------------ resource filter
    static final Set<String> COMMON = Set.of("stone", "cobblestone", "deepslate", "cobbled_deepslate", "dirt", "gravel", "sand",
            "red_sand", "netherrack", "andesite", "diorite", "granite", "tuff", "sandstone", "red_sandstone", "basalt",
            "blackstone", "end_stone", "calcite", "dripstone_block", "pointed_dripstone", "clay", "mud", "coarse_dirt");

    static String path(Item i) { return Registries.ITEM.getId(i).getPath(); }

    public static boolean isOre(Item i) {
        String p = path(i);
        for (String k : new String[]{"ore", "raw_", "coal", "diamond", "emerald", "lapis", "redstone", "quartz", "gold", "iron",
                "copper", "netherite", "debris", "amethyst"})
            if (p.contains(k)) return true;
        return false;
    }

    public static void keepAll() { unwanted.clear(); }
    public static void oresOnly() { unwanted.clear(); for (Item i : drops.keySet()) if (!isOre(i)) unwanted.add(i); }
    public static void throwCommon() { for (Item i : drops.keySet()) if (COMMON.contains(path(i))) unwanted.add(i); }

    /** Throws unwanted stacks away when the inventory gets full (always at the end). Keeps 64 scaffold blocks. */
    static boolean trash(MinecraftClient mc) {
        if (unwanted.isEmpty()) return false;
        ClientPlayerEntity p = mc.player;
        if (!finishing && Loot.freeSlots(p) > 8) return false;
        Item sc = BuilderBot.scaffoldItem(p);
        int keepLeft = 64;
        var inv = p.getInventory().getMainStacks();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !unwanted.contains(s.getItem()) || s.isOf(Items.CHEST)) continue;
            if (s.getItem() == sc && keepLeft > 0) { keepLeft -= s.getCount(); continue; }
            mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i < 9 ? 36 + i : i, 1, SlotActionType.THROW, p);
            BuilderBot.wait = 2;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ start / stop
    /** Returns an error text, or null when the bot started. */
    public static String start(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return "No world";
        if (!ready()) return "Set point A and point B first";
        if (BuilderBot.busy() || CreativeFill.busy()) return "Stop the bot first";
        scan(mc);
        if (!error.isEmpty()) return error;
        if (toDig == 0) return "Nothing to dig in this area";
        bounds();
        anchor = new BlockPos(minX, mc.player.getBlockY(), minZ);
        cx = minX; cz = minZ; cy = maxY; dx = 1; exhausted = false;
        skipped.clear(); cur = null; done = 0; skippedCount = 0; total = toDig; stuck = 0; mineTicks = 0;
        finishing = false; lavaWarned = false;

        BuilderBot.layers = new ArrayList<>(); BuilderBot.li = 0; BuilderBot.qi = 0;   // nothing is needed: every drop is "surplus"
        BuilderBot.wait = 0; BuilderBot.linger = 0; BuilderBot.phase = ""; BuilderBot.finishTicks = 0;
        Nav.reset(); Elytra.reset(); Loot.reset(); Nav.abandoned = 0;
        BuilderBot.mode = BuilderBot.Mode.DIG; Safety.reset(); dodge = 0; dodgeTries = 0;
        Progress.saveDig(mc);
        BuilderBot.msg(mc, "Digging " + toDig + " blocks" + (collect ? " (collecting into chests)" : " (leaving the drops)"));
        return null;
    }

    static BlockPos nextTarget(ClientWorld w) {
        while (!exhausted) {
            BlockPos p = new BlockPos(cx, cy, cz);
            if (!skipped.contains(p) && isTarget(w, p)) return p;
            cx += dx;
            if (cx > maxX || cx < minX) {
                cx -= dx; dx = -dx; cz++;
                if (cz > maxZ) { cz = minZ; cy--; if (cy < minY) exhausted = true; }
            }
        }
        return null;
    }

    static void skip(MinecraftClient mc) {
        if (cur != null) { skipped.add(cur); skippedCount++; }
        cur = null; stuck = 0; mineTicks = 0; Nav.reset();
    }

    static boolean lavaNear(ClientWorld w, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockState s = w.getBlockState(p.offset(d));
            if (s.isOf(Blocks.LAVA) || s.isOf(Blocks.WATER)) return true;      // lava burns, water floods the hole (drowning)
        }
        return false;
    }

    static void updateHud() {
        float prog = total == 0 ? 1 : Math.min(1f, (float) (done + skippedCount) / total);
        BuilderBot.hudProg = prog;
        BuilderBot.hudText = "Bob the Builder - " + (!BuilderBot.phase.isEmpty() ? BuilderBot.phase : "Digging layer " + cy)
                + " - " + (int) (prog * 100) + "% (" + done + "/" + total + ")";
    }

    // ------------------------------------------------------------ tick (called by BuilderBot.tick in DIG mode)
    static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (mc.currentScreen instanceof GenericContainerScreen) {          // a chest is open: only the deposit may use it
            if (Loot.depositing) Loot.run(mc, false); else p.closeHandledScreen();
            return;
        }
        updateHud();
        if (trash(mc)) return;

        if (finishing) {
            boolean busy = collect ? BuilderBot.finishing(mc) : finishLeave();
            if (busy) { updateHud(); return; }
            BuilderBot.endWith(mc, "§aDigging finished ✔ (" + done + " blocks" + (skippedCount > 0 ? ", " + skippedCount + " skipped" : "") + ")",
                    "Bob the Builder - Dig finished", 1f);
            Progress.clearDig();
            return;
        }

        if (collect) {
            if (Loot.run(mc, false)) return;
            boolean mining = cur != null && !w.getBlockState(cur).isAir();
            if (!mining && Loot.collect(mc)) return;
        }

        if (cur != null && (w.getBlockState(cur).isAir() || w.getBlockState(cur).getBlock() instanceof FluidBlock)) {
            done++; cur = null; stuck = 0; mineTicks = 0;
        }
        if (cur == null) {
            cur = nextTarget(w);
            if (cur == null) { finishing = true; BuilderBot.finishTicks = 0; return; }
        }
        if (lavaNear(w, cur)) {
            if (!lavaWarned) { lavaWarned = true; BuilderBot.msg(mc, "§eBlocks next to lava / water are skipped"); }
            skip(mc); return;
        }
        if (!BuilderBot.walkNear(mc, cur, 4.0)) {
            if (Nav.failed || ++stuck > 600) skip(mc);
            return;
        }
        stuck = 0;
        // sand / gravel above a block over our own head would fall on us and suffocate the bot
        boolean underFalling = cur.getX() == p.getBlockX() && cur.getZ() == p.getBlockZ() && cur.getY() > p.getBlockY()
                && w.getBlockState(cur.up()).getBlock() instanceof FallingBlock;
        if (dodge > 0 || underFalling) {                                     // step out of the column first
            Nav.W = w;
            if (dodge == 0) { if (++dodgeTries > 4) { dodgeTries = 0; skip(mc); return; } dodge = 8; }
            dodge--;
            double yaw = Math.toRadians(p.getYaw()), fx = -Math.sin(yaw), fz = Math.cos(yaw);
            if (Safety.safeStep(p, -fz, fx)) mc.options.rightKey.setPressed(true);
            else if (Safety.safeStep(p, fz, -fx)) mc.options.leftKey.setPressed(true);
            else { dodge = 0; dodgeTries = 0; skip(mc); }
            return;
        }
        dodgeTries = 0;
        if (++mineTicks > 500) { skip(mc); return; }                         // cannot be mined with what we have
        BuilderBot.setSneak(mc, false);
        BuilderBot.breakBlock(mc, cur);
    }

    static boolean finishLeave() {
        BuilderBot.phase = "Removing temporary blocks";
        if (++BuilderBot.finishTicks > 3000) { Nav.abandonAll(); BuilderBot.phase = ""; return false; }
        if (!Nav.cleanup(MinecraftClient.getInstance())) return true;
        BuilderBot.phase = "";
        return false;
    }

    // ------------------------------------------------------------ loot chest spots (used by Loot while digging)
    /** Chest k stands west / north of the area, on the ground. */
    static BlockPos chestSpot(int k) {
        int x = minX - 2 - (k / 4) * 2, z = minZ - 2 - (k % 4) * 2;
        ClientWorld w = MinecraftClient.getInstance().world;
        if (w == null) return new BlockPos(x, anchor.getY(), z);
        for (int y = anchor.getY() + 3; y >= anchor.getY() - 8; y--) {
            BlockPos q = new BlockPos(x, y, z);
            BlockState s = w.getBlockState(q);
            if (s.getBlock() instanceof ChestBlock) return q;
            if (!s.isAir() && !s.isReplaceable()) return y == anchor.getY() + 3 ? new BlockPos(x, anchor.getY(), z) : q.up();
        }
        return new BlockPos(x, anchor.getY(), z);
    }

    // ------------------------------------------------------------ rendering
    static void cell(MatrixStack ms, VertexConsumer lines, BlockPos p, Vec3d cam, float r, float g, float bl) {
        VertexRendering.drawBox(ms, lines, p.getX() - cam.x, p.getY() - cam.y, p.getZ() - cam.z,
                p.getX() + 1 - cam.x, p.getY() + 1 - cam.y, p.getZ() + 1 - cam.z, r, g, bl, 1f);
    }

    public static void drawArea(MatrixStack ms, VertexConsumer lines, Vec3d cam) {
        if (a != null) cell(ms, lines, a, cam, 0.2f, 1f, 0.2f);
        if (b != null) cell(ms, lines, b, cam, 1f, 0.3f, 0.3f);
        if (ready()) {
            bounds();
            VertexRendering.drawBox(ms, lines, minX - cam.x, minY - cam.y, minZ - cam.z,
                    maxX + 1 - cam.x, maxY + 1 - cam.y, maxZ + 1 - cam.z, 1f, 0.9f, 0.2f, 1f);
        }
        if (running() && cur != null) cell(ms, lines, cur, cam, 0.1f, 1f, 1f);
    }
}
