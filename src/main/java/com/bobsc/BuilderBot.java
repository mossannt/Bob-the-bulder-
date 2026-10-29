package com.bobsc;

import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.VerticallyAttachableBlockItem;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.*;

/**
 * Client-side builder bot.
 *  - builds LAYER BY LAYER (bottom -> top), each layer in a snake pattern
 *  - precise: finds the exact look direction / click face / hit height that makes the game
 *    produce the wanted block state (facing, half, axis...), then fixes repeaters, comparators,
 *    doors, slabs... by clicking, and re-places the block if it still doesn't match.
 */
public class BuilderBot {
    enum Mode { IDLE, CHECK, BUILD, DIG }
    static Mode mode = Mode.IDLE;

    public static final Map<Item, Integer> found = new HashMap<>();
    static int wait, chestIdx, stuck, attempts, li, qi, openTicks, deferred, failed, total, completed;
    static boolean fetching, fetchedAll;
    static Item priorityItem;
    static List<List<BlockPos>> layers = new ArrayList<>();
    static final List<String> failedList = new ArrayList<>();
    // loop protection: blocks we placed once, how often we had to break them again, block being broken now
    static final Set<BlockPos> placedOnce = new HashSet<>();
    static final Map<BlockPos, Integer> redo = new HashMap<>();
    static BlockPos breaking;

    /** Cheap blocks the bot may use as temporary scaffolding (they drop themselves when broken). */
    public static final Set<Item> SCAFFOLD = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT,
            Items.NETHERRACK, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS);
    static boolean wantScaffold, scaffoldFetchTried, pendingSpawn;
    static int supportTries, supportDir, finishTicks;
    static String phase = "";

    // HUD data
    static String hudText = "";
    static float hudProg = 0;
    static int linger = 0;

    record Plan(Direction d, float yaw, float pitch, double hy) {}
    static final Map<String, Optional<Plan>> planCache = new HashMap<>();

    // ------------------------------------------------------------ HUD api
    public static boolean hudVisible() { return mode != Mode.IDLE || linger > 0 || BotCharacter.hudVisible(); }
    public static String hudText() { return mode == Mode.IDLE && linger == 0 && BotCharacter.hudVisible() ? BotCharacter.hudText() : hudText; }
    public static float hudProgress() { return mode == Mode.IDLE && linger == 0 && BotCharacter.hudVisible() ? BotCharacter.hudProgress() : hudProg; }

    static void updateHud() {
        if (mode == Mode.CHECK) {
            int n = BuildSession.chests.size();
            hudProg = n == 0 ? 0 : (float) chestIdx / n;
            hudText = "Bob the Builder - Checking chests " + Math.min(chestIdx + 1, n) + "/" + n;
        } else if (mode == Mode.BUILD) {
            hudProg = total == 0 ? 1 : (float) completed / total;
            hudText = "Bob the Builder - " + (!phase.isEmpty() ? phase : fetching ? "Fetching materials" : "Building")
                    + " - Layer " + Math.min(li + 1, layers.size()) + "/" + layers.size()
                    + " - " + (int) (hudProg * 100) + "% (" + completed + "/" + total + ")";
        }
    }

    // ------------------------------------------------------------ misc api
    public static boolean busy() { return mode != Mode.IDLE; }

    /** One line for the menu: what the bot is doing. */
    public static String status() {
        return switch (mode) {
            case CHECK -> "checking the chests";
            case BUILD -> "building " + completed + "/" + total;
            case DIG -> "digging " + (Digger.done + Digger.skippedCount) + "/" + Digger.total;
            default -> Progress.canContinue() ? "paused (press J to continue)" : "idle";
        };
    }

    public static void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[Bob the Builder] " + s), false);
    }

    public static void stop(MinecraftClient mc, String s) {
        Progress.saveActive(mc);                           // paused: keep the progress (a finished job clears it right after)
        mode = Mode.IDLE; BuildSession.building = false; linger = 0;
        phase = ""; Nav.reset(); Elytra.reset(); Loot.depositing = false;
        release(mc); setSneak(mc, false);
        if (s != null) msg(mc, s);
    }

    static void endWith(MinecraftClient mc, String chat, String hud, float prog) {
        stop(mc, chat);
        hudText = hud; hudProg = prog; linger = 140;
    }

    /** Slow mode: longer pauses between placements. */
    public static boolean slow;
    static int chunkWait;

    static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    // ------------------------------------------------------------ start
    public static void startCheck() {
        found.clear(); BuildSession.checked.clear();
        chestIdx = 0; wait = 0; stuck = 0; openTicks = 0; linger = 0; mode = Mode.CHECK;
    }

    public static void startBuild() {
        Map<Integer, List<BlockPos>> byY = new TreeMap<>();
        for (var e : BuildSession.blocks.entrySet())
            if (BuildSession.itemOf(e.getValue()) != null)
                byY.computeIfAbsent(e.getKey().getY(), k -> new ArrayList<>()).add(e.getKey());
        layers = new ArrayList<>();
        total = 0;
        for (List<BlockPos> l : byY.values()) {
            l.sort(Comparator.comparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
            List<BlockPos> out = new ArrayList<>();
            int i = 0, row = 0;
            while (i < l.size()) {                         // snake: every other row reversed
                int z = l.get(i).getZ(), j = i;
                while (j < l.size() && l.get(j).getZ() == z) j++;
                List<BlockPos> r = new ArrayList<>(l.subList(i, j));
                if (row++ % 2 == 1) Collections.reverse(r);
                out.addAll(r);
                i = j;
            }
            layers.add(out);
            total += out.size();
        }
        li = 0; qi = 0; completed = 0; failed = 0; failedList.clear();
        fetching = false; fetchedAll = false; attempts = 0; stuck = 0; deferred = 0;
        wait = 0; openTicks = 0; chestIdx = 0; linger = 0; planCache.clear();
        placedOnce.clear(); redo.clear(); breaking = null;
        Nav.reset(); Elytra.reset(); Loot.softReset(); Nav.abandoned = 0;
        supportTries = 0; supportDir = 0; finishTicks = 0; phase = ""; scaffoldFetchTried = false; wantScaffold = false;
        mode = Mode.BUILD; BuildSession.building = true; Safety.reset(); chunkWait = 0;
        Progress.saveBuild(mc());
    }

    // ------------------------------------------------------------ tick
    public static void tick(MinecraftClient mc) {
        if (linger > 0 && mode == Mode.IDLE) linger--;
        if (mode == Mode.IDLE || mc.player == null || mc.world == null) return;
        updateHud();
        Progress.tick(mc);
        release(mc);
        if (wait > 0) { wait--; return; }
        if (mc.currentScreen instanceof AbstractSignEditScreen) {   // placing a sign opens its editor -> close it
            mc.setScreen(null); wait = 2; return;
        }
        try {
            if ((mode == Mode.BUILD || mode == Mode.DIG) && Safety.tick(mc)) return;
            if (mode == Mode.CHECK) check(mc); else if (mode == Mode.DIG) Digger.tick(mc); else build(mc);
        } catch (Exception e) {
            e.printStackTrace();
            stop(mc, "Error: " + e);
        }
    }

    static void release(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
    }

    static void setSneak(MinecraftClient mc, boolean b) { mc.options.sneakKey.setPressed(b); }

    // ------------------------------------------------------------ CHECK
    static void check(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (mc.currentScreen instanceof GenericContainerScreen gs) {
            if (openTicks++ < 3) return;  // wait for contents sync
            GenericContainerScreenHandler h = gs.getScreenHandler();
            int n = h.getRows() * 9;
            for (int i = 0; i < n; i++) {
                ItemStack s = h.getSlot(i).getStack();
                if (!s.isEmpty()) found.merge(s.getItem(), s.getCount(), Integer::sum);
            }
            BuildSession.checked.add(BuildSession.chests.get(chestIdx));
            p.closeHandledScreen();
            openTicks = 0; chestIdx++; stuck = 0; wait = 5;
            return;
        }
        openTicks = 0;
        if (chestIdx >= BuildSession.chests.size()) { finishCheck(mc); return; }
        BlockPos c = BuildSession.chests.get(chestIdx);
        if (!(mc.world.getBlockState(c).getBlock() instanceof ChestBlock)) {
            msg(mc, "§cNo chest at " + c.toShortString());
            chestIdx++; return;
        }
        if (!walkNear(mc, c, 3.5)) {
            if (Nav.failed || ++stuck > 600) { msg(mc, "§cCan't reach chest " + c.toShortString()); chestIdx++; stuck = 0; }
            return;
        }
        openChest(mc, c);
    }

    static void finishCheck(MinecraftClient mc) {
        StringBuilder sb = new StringBuilder();
        boolean ok = BuildSession.chests.size() == BuildSession.checked.size();
        for (var e : BuildSession.required().entrySet()) {
            int have = found.getOrDefault(e.getKey(), 0);
            if (have < e.getValue()) {
                ok = false;
                sb.append("\n  ").append(e.getKey().getName().getString())
                  .append(" -").append(e.getValue() - have);
            }
        }
        endWith(mc, ok ? "§aAll materials are in the chests ✔" : "§cMissing:" + sb,
                ok ? "Bob the Builder - Materials OK" : "Bob the Builder - Materials missing", 1f);
    }

    // ------------------------------------------------------------ BUILD
    static void build(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;

        // ---- chest GUI open while fetching
        if (mc.currentScreen instanceof GenericContainerScreen gs) {
            if (Loot.depositing) { Loot.run(mc, false); return; }
            if (!fetching) { p.closeHandledScreen(); return; }
            if (openTicks++ < 3) return;
            GenericContainerScreenHandler h = gs.getScreenHandler();
            int n = h.getRows() * 9;
            Map<Item, Integer> need = remainingNeed(w);
            for (int pass = 0; pass < 2; pass++) {            // pass 0: the item we need right now
                for (int i = 0; i < n; i++) {
                    ItemStack s = h.getSlot(i).getStack();
                    if (s.isEmpty()) continue;
                    boolean pri = s.isOf(priorityItem);
                    if ((pass == 0) != pri) continue;
                    int missing = need.getOrDefault(s.getItem(), 0) - countInv(p, s.getItem());
                    if (wantScaffold && SCAFFOLD.contains(s.getItem()) && scaffoldSlot(p) < 0) missing = Math.max(missing, 1);
                    if (missing > 0)
                        mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, p);
                }
            }
            p.closeHandledScreen();
            openTicks = 0; chestIdx++; stuck = 0; wait = 6;
            return;
        }
        openTicks = 0;

        // ---- fetching: visit chests
        if (fetching) {
            if (chestIdx >= BuildSession.chests.size()) {
                fetching = false; Nav.reset();
                if (wantScaffold) wantScaffold = false; else fetchedAll = true;
                return;
            }
            BlockPos c = BuildSession.chests.get(chestIdx);
            if (!(w.getBlockState(c).getBlock() instanceof ChestBlock)) { chestIdx++; return; }
            if (!walkNear(mc, c, 3.5)) { if (Nav.failed || ++stuck > 600) { chestIdx++; stuck = 0; } return; }
            openChest(mc, c);
            return;
        }

        // ---- loot: store surplus items in chests, pick up the drops of what we broke
        if (li < layers.size()) {
            if (Loot.run(mc, false)) return;
            boolean mining = breaking != null && !w.getBlockState(breaking).isAir();
            if (!mining && Loot.collect(mc)) return;
        }

        // ---- advance through layers, skipping blocks that are already exactly right
        List<BlockPos> layer;
        while (true) {
            if (li >= layers.size()) { if (finishing(mc)) return; finishBuild(mc); return; }
            layer = layers.get(li);
            while (qi < layer.size() && done(w, layer.get(qi))) {
                Nav.scaffold.remove(BuildSession.origin.add(layer.get(qi)));
                qi++; completed++; fetchedAll = false; attempts = 0; stuck = 0; deferred = 0;
            }
            if (qi >= layer.size()) { li++; qi = 0; continue; }
            break;
        }

        BlockPos rel = layer.get(qi);
        BlockPos tgt = BuildSession.origin.add(rel);
        BlockState want = BuildSession.blocks.get(rel);
        Item item = BuildSession.itemOf(want);
        BlockState cur = w.getBlockState(tgt);
        boolean occupied = !cur.isAir() && !cur.isReplaceable();
        int slot = findInv(p, item);

        boolean slabMerge = occupied && cur.getBlock() == want.getBlock() && (isSlabMerge(cur, want) || needsMore(cur, want));
        if ((!occupied || slabMerge) && slot < 0) { needFetch(mc, item); return; }

        Nav.W = w;
        if (!Nav.loaded(tgt.getX(), tgt.getZ())) {                // too far away: the chunk is not loaded yet
            phase = "Waiting for the chunks";
            if (++chunkWait > 1200) { chunkWait = 0; stop(mc, "§cThe building is not loaded (too far away) - paused"); return; }
            Nav.walkToward(mc, p, tgt.getX() + 0.5, tgt.getZ() + 0.5, true, false, false);
            return;
        }
        if (chunkWait > 0) { chunkWait = 0; phase = ""; }
        if (!walkNear(mc, tgt, 4.0)) {
            if (Nav.failed) unreachable(mc, rel);
            else if (++stuck > 900) skip(rel, "unreachable");
            return;
        }

        // never stand inside the block we are about to place
        if (!occupied && p.getBoundingBox().intersects(new Box(tgt))) {
            setSneak(mc, false);
            mc.options.backKey.setPressed(true);
            return;
        }

        // ---- something is there already
        if (occupied) {
            setSneak(mc, false);
            if (cur.getBlock() == want.getBlock() && tryFix(mc, tgt, cur, want, slot)) {
                wait = slow ? 8 : 3;
                if (++attempts > 15) skip(rel, "state");
                return;
            }
            if (cur.getHardness(w, tgt) < 0) { skip(rel, "unbreakable"); return; }
            if (!tgt.equals(breaking)) {                      // a NEW break of a block we placed ourselves
                breaking = tgt;
                if (placedOnce.contains(rel) && redo.merge(rel, 1, Integer::sum) > 2) {
                    skip(rel, "state keeps mismatching"); breaking = null; return;
                }
            }
            int tool = bestTool(p, cur);
            if (tool >= 0) selectInv(mc, p, tool);
            face(p, Vec3d.ofCenter(tgt));
            mc.interactionManager.updateBlockBreakingProgress(tgt, Direction.UP);
            p.swingHand(Hand.MAIN_HAND);
            return;
        }

        // ---- place with the exact orientation
        List<Direction> sups = supports(w, tgt);
        if (sups.isEmpty()) {
            if (deferred <= layer.size() - qi) { layer.add(layer.remove(qi)); deferred++; return; }   // maybe a neighbour appears
            if (!tempSupport(mc, w, tgt)) { skip(rel, "no support"); deferred = 0; supportTries = 0; supportDir = 0; }
            return;
        }
        ItemStack stack = p.getInventory().getMainStacks().get(slot);
        BlockState pw = planState(want);
        Plan pl = null; Direction d = null;
        for (Direction c : sups) {
            Plan x = plan(p, stack, want, pw, tgt, c);
            if (x != null) { pl = x; d = c; break; }
        }
        if (d == null) {
            if (deferred <= layer.size() - qi) {                  // maybe a better support appears later
                layer.add(layer.remove(qi)); deferred++; return;
            }
            for (Direction c : sups) if (attachOk(stack, want, c)) { d = c; break; }   // last resort: imprecise placement
            if (d == null) { skip(rel, "no valid support"); return; }
        }

        selectInv(mc, p, slot);
        setSneak(mc, true);                                       // never open chests/doors by accident
        if (!p.isSneaking()) return;

        BlockPos nb = tgt.offset(d);
        Direction side = d.getOpposite();
        Vec3d hit = hitPos(nb, side, pl != null ? pl.hy() : 0.5);
        if (pl != null) { p.setYaw(pl.yaw()); p.setPitch(pl.pitch()); } else face(p, hit);
        sendLook(mc, p);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, side, nb, false));
        p.swingHand(Hand.MAIN_HAND);
        placedOnce.add(rel); breaking = null;
        Nav.scaffold.remove(tgt); supportTries = 0; supportDir = 0;
        wait = slow ? 8 : 3;
        if (++attempts > 15) skip(rel, "could not match state");
    }

    static void needFetch(MinecraftClient mc, Item item) {
        if (fetchedAll) { stop(mc, "§cMaterial missing: " + item.getName().getString()); return; }
        fetching = true; chestIdx = 0; stuck = 0; priorityItem = item;
    }

    static void finishBuild(MinecraftClient mc) {
        StringBuilder sb = new StringBuilder("§aBuild complete ✔");
        if (Nav.abandoned > 0) sb.append("\n  §e").append(Nav.abandoned).append(" temporary blocks could not be removed");
        if (failed > 0) {
            sb.append("  §e(").append(failed).append(" blocks could not be matched)");
            for (int i = 0; i < Math.min(5, failedList.size()); i++) sb.append("\n  ").append(failedList.get(i));
        }
        endWith(mc, sb.toString(),
                "Bob the Builder - Done 100%" + (failed > 0 ? " (" + failed + " issues)" : ""), 1f);
        Progress.clearBuild();
    }

    static void skip(BlockPos rel, String why) {
        failed++;
        BlockState s = BuildSession.blocks.get(rel);
        BlockPos a = BuildSession.origin.add(rel);
        failedList.add(s.getBlock().getName().getString() + " @ " + a.toShortString() + " (" + why + ")");
        List<BlockPos> layer = layers.get(li);
        qi++; completed++; attempts = 0; stuck = 0; deferred = 0;
    }

    // ------------------------------------------------------------ precision helpers
    static BlockState planState(BlockState want) {
        BlockState s = want;
        if (s.getBlock() instanceof SlabBlock && s.get(SlabBlock.TYPE) == SlabType.DOUBLE)
            s = s.with(SlabBlock.TYPE, SlabType.BOTTOM);          // placed as one slab, merged afterwards
        for (Property<?> pr : want.getProperties())               // candles / snow layers...: place one, add the rest by clicking
            if (BuildSession.COUNTERS.contains(pr.getName()) && pr instanceof net.minecraft.state.property.IntProperty ip)
                s = s.with(ip, ip.getValues().stream().min(Integer::compare).orElse(1));
        return s;
    }

    /** Same block, but fewer candles / pickles / snow layers than wanted. */
    static boolean needsMore(BlockState cur, BlockState want) {
        if (cur.getBlock() != want.getBlock()) return false;
        for (Property<?> pr : want.getProperties()) {
            if (!BuildSession.COUNTERS.contains(pr.getName()) || !cur.contains(pr)) continue;
            Object a = cur.get(pr), b = want.get(pr);
            if (a instanceof Integer ia && b instanceof Integer ib && ia < ib) return true;
        }
        return false;
    }

    static boolean isSlabMerge(BlockState cur, BlockState want) {
        return cur.getBlock() instanceof SlabBlock && want.get(SlabBlock.TYPE) == SlabType.DOUBLE
                && cur.get(SlabBlock.TYPE) != SlabType.DOUBLE;
    }

    static Vec3d hitPos(BlockPos nb, Direction side, double hy) {
        double x = nb.getX() + 0.5 + side.getOffsetX() * 0.5;
        double z = nb.getZ() + 0.5 + side.getOffsetZ() * 0.5;
        double y = side.getAxis() == Direction.Axis.Y ? nb.getY() + 0.5 + side.getOffsetY() * 0.5 : nb.getY() + hy;
        return new Vec3d(x, y, z);
    }

    /**
     * Asks the game itself: "if I click this face, looking this way, which state do you give me?"
     * Tries 16 yaws x 3 pitches x 2 hit heights and keeps the first combination that gives the wanted state.
     */
    /**
     * Items that place TWO different blocks (sign / wall sign, torch / wall torch, banner, head, coral fan, hanging sign...):
     * the item itself decides by the clicked face. Clicking the top of a block gives the standing one, a side gives the
     * wall one (hanging signs: the underside of a block gives the hanging one). Planning with block.getPlacementState()
     * ignored that, so the bot placed a standing sign where a wall sign was wanted, broke it and tried again.
     */
    static boolean attachOk(ItemStack stack, BlockState want, Direction d) {
        if (!(stack.getItem() instanceof VerticallyAttachableBlockItem) || !(stack.getItem() instanceof BlockItem bi)) return true;
        Direction vertical = bi.getBlock() instanceof HangingSignBlock ? Direction.UP : Direction.DOWN;
        boolean standing = want.getBlock() == bi.getBlock();
        return standing ? d == vertical : d.getAxis() != Direction.Axis.Y;
    }

    static Plan plan(ClientPlayerEntity p, ItemStack stack, BlockState want, BlockState pw, BlockPos tgt, Direction d) {
        if (!attachOk(stack, want, d)) return null;
        String key = want + "|" + d;
        Optional<Plan> cached = planCache.get(key);
        if (cached != null) return cached.orElse(null);

        Plan res = null;
        BlockPos nb = tgt.offset(d);
        Direction side = d.getOpposite();
        float oy = p.getYaw(), op = p.getPitch();
        outer:
        for (double hy : new double[]{0.25, 0.75}) {
            if (side.getAxis() == Direction.Axis.Y && hy > 0.5) continue;
            for (float pitch : new float[]{0f, 70f, -70f}) {
                for (int k = 0; k < 16; k++) {
                    float yaw = k * 22.5f;
                    p.setYaw(yaw); p.setPitch(pitch);
                    ItemPlacementContext ctx = new ItemPlacementContext(p, Hand.MAIN_HAND, stack,
                            new BlockHitResult(hitPos(nb, side, hy), side, nb, false));
                    if (!ctx.canPlace()) continue;
                    BlockState got = want.getBlock().getPlacementState(ctx);
                    if (got != null && BuildSession.matches(got, pw)) { res = new Plan(d, yaw, pitch, hy); break outer; }
                }
            }
        }
        p.setYaw(oy); p.setPitch(op);
        if (res != null) planCache.put(key, Optional.of(res));   // never cache a failure: it can depend on the position
        return res;
    }

    /** Fix a placed block by clicking it: repeater delay, comparator mode, door/trapdoor/gate open, note, slab merge. */
    static boolean tryFix(MinecraftClient mc, BlockPos tgt, BlockState cur, BlockState want, int slot) {
        ClientPlayerEntity p = mc.player;
        Block b = cur.getBlock();
        if (b == Blocks.IRON_DOOR || b == Blocks.IRON_TRAPDOOR) return false;

        if (isSlabMerge(cur, want)) {
            if (slot < 0) return false;
            selectInv(mc, p, slot);
            setSneak(mc, false);
            if (p.isSneaking()) return true;
            Direction side = cur.get(SlabBlock.TYPE) == SlabType.BOTTOM ? Direction.UP : Direction.DOWN;
            Vec3d hit = Vec3d.ofCenter(tgt).add(Vec3d.of(side.getVector()).multiply(0.5));
            click(mc, p, tgt, side, hit);
            return true;
        }

        if (needsMore(cur, want)) {                                // add one more candle / layer / pickle
            if (slot < 0) return false;
            selectInv(mc, p, slot);
            setSneak(mc, false);
            if (p.isSneaking()) return true;
            click(mc, p, tgt, Direction.UP, Vec3d.ofCenter(tgt).add(0, 0.5, 0));
            return true;
        }

        Property<?>[] fixables = { Properties.DELAY, Properties.COMPARATOR_MODE, Properties.OPEN,
                Properties.INVERTED, Properties.NOTE };
        for (Property<?> pr : fixables) {
            if (cur.contains(pr) && want.contains(pr) && !cur.get(pr).equals(want.get(pr))) {
                setSneak(mc, false);
                if (p.isSneaking()) return true;
                click(mc, p, tgt, Direction.UP, Vec3d.ofCenter(tgt).add(0, 0.4, 0));
                return true;
            }
        }
        return false;
    }

    static void click(MinecraftClient mc, ClientPlayerEntity p, BlockPos tgt, Direction side, Vec3d hit) {
        face(p, hit);
        sendLook(mc, p);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, side, tgt, false));
        p.swingHand(Hand.MAIN_HAND);
    }

    static void sendLook(MinecraftClient mc, ClientPlayerEntity p) {
        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(p.getYaw(), p.getPitch(), p.isOnGround(), p.horizontalCollision));
    }

    // ------------------------------------------------------------ helpers
    static boolean done(ClientWorld w, BlockPos rel) {
        return BuildSession.matches(w.getBlockState(BuildSession.origin.add(rel)), BuildSession.blocks.get(rel));
    }

    /** Materials for the current layer (+ following layers while they still fit in the inventory). */
    static Map<Item, Integer> remainingNeed(ClientWorld w) {
        Map<Item, Integer> m = new HashMap<>();
        for (int l = li; l < layers.size(); l++) {
            List<BlockPos> L = layers.get(l);
            Map<Item, Integer> tmp = new HashMap<>(m);
            for (int i = (l == li ? qi : 0); i < L.size(); i++) {
                BlockPos rel = L.get(i);
                if (done(w, rel)) continue;
                BlockState s = BuildSession.blocks.get(rel);
                tmp.merge(BuildSession.itemOf(s), BuildSession.countFor(s), Integer::sum);
            }
            if (l != li && stacksOf(tmp) > 28) break;
            m = tmp;
        }
        return m;
    }

    static int stacksOf(Map<Item, Integer> m) {
        int s = 0;
        for (var e : m.entrySet()) { int mx = e.getKey().getMaxCount(); s += (e.getValue() + mx - 1) / mx; }
        return s;
    }

    static int countInv(ClientPlayerEntity p, Item it) {
        int c = 0;
        for (ItemStack s : p.getInventory().getMainStacks()) if (s.isOf(it)) c += s.getCount();
        return c;
    }

    static int findInv(ClientPlayerEntity p, Item it) {
        var inv = p.getInventory().getMainStacks();
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).isOf(it)) return i;
        return -1;
    }

    static int bestTool(ClientPlayerEntity p, BlockState st) {
        var inv = p.getInventory().getMainStacks();
        int best = -1; float bs = 1f;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isIn(ItemTags.PICKAXES) || s.isIn(ItemTags.AXES) || s.isIn(ItemTags.SHOVELS) || s.isIn(ItemTags.HOES)) {
                if (s.isDamageable() && s.getMaxDamage() - s.getDamage() <= 2) continue;     // nearly broken: keep it
                float sp = s.getMiningSpeedMultiplier(st);
                if (sp > bs) { bs = sp; best = i; }
            }
        }
        return best;
    }

    /** Puts inventory index into the hotbar (slot 8 if needed) and selects it. */
    static void selectInv(MinecraftClient mc, ClientPlayerEntity p, int invIdx) {
        if (invIdx < 9) { p.getInventory().setSelectedSlot(invIdx); return; }
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, invIdx, 8, SlotActionType.SWAP, p);
        p.getInventory().setSelectedSlot(8);
    }

    static List<Direction> supports(ClientWorld w, BlockPos t) {
        Direction[] order = { Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP };
        List<Direction> out = new ArrayList<>();
        for (Direction d : order) {
            BlockState s = w.getBlockState(t.offset(d));
            if (!s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty()) out.add(d);
        }
        return out;
    }

    static void openChest(MinecraftClient mc, BlockPos c) {
        setSneak(mc, false);
        if (mc.player.isSneaking()) return;
        Vec3d v = Vec3d.ofCenter(c);
        face(mc.player, v);
        sendLook(mc, mc.player);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(v, Direction.UP, c, false));
        wait = 10;
    }

    static void face(ClientPlayerEntity p, Vec3d t) {
        Vec3d e = p.getEyePos();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f);
        p.setPitch((float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI));
    }

    /** Smart walking (path-finding, temporary bridges / pillars while building). Returns true when in reach. */
    static boolean walkNear(MinecraftClient mc, BlockPos t, double dist) {
        return Nav.goNear(mc, t, dist, mode == Mode.BUILD || mode == Mode.DIG);
    }

    // ------------------------------------------------------------ scaffold / loot helpers
    static int scaffoldSlot(ClientPlayerEntity p) {
        var inv = p.getInventory().getMainStacks();
        for (int i = 0; i < inv.size(); i++)
            if (!inv.get(i).isEmpty() && SCAFFOLD.contains(inv.get(i).getItem())) return i;
        return -1;
    }

    static Item scaffoldItem(ClientPlayerEntity p) {
        int i = scaffoldSlot(p);
        return i < 0 ? null : p.getInventory().getMainStacks().get(i).getItem();
    }

    static boolean hasEmptySlot(ClientPlayerEntity p) {
        for (ItemStack s : p.getInventory().getMainStacks()) if (s.isEmpty()) return true;
        return false;
    }

    static boolean hasScaffold(MinecraftClient mc) {
        return scaffoldSlot(mc.player) >= 0 || (mc.player.isCreative() && hasEmptySlot(mc.player));
    }

    static boolean chestAvailable(ClientPlayerEntity p) {
        return findInv(p, Items.CHEST) >= 0 || (p.isCreative() && hasEmptySlot(p));
    }

    /** Inventory index of the item; in creative it is spawned first (arrives next tick: pendingSpawn). */
    static int findOrSpawn(MinecraftClient mc, Item it, int count) {
        ClientPlayerEntity p = mc.player;
        pendingSpawn = false;
        int s = findInv(p, it);
        if (s >= 0) return s;
        if (!p.isCreative()) return -1;
        var inv = p.getInventory().getMainStacks();
        for (int i = 0; i < 36; i++)
            if (inv.get(i).isEmpty()) {
                mc.interactionManager.clickCreativeStack(new ItemStack(it, count), i < 9 ? 36 + i : i);
                pendingSpawn = true; wait = 4;
                return -1;
            }
        return -1;
    }

    static int ensureScaffold(MinecraftClient mc) {
        int s = scaffoldSlot(mc.player);
        pendingSpawn = false;
        return s >= 0 ? s : findOrSpawn(mc, Items.COBBLESTONE, 64);
    }

    static void breakBlock(MinecraftClient mc, BlockPos tgt) {
        ClientPlayerEntity p = mc.player;
        int tool = bestTool(p, mc.world.getBlockState(tgt));
        if (tool >= 0) selectInv(mc, p, tool);
        face(p, Vec3d.ofCenter(tgt));
        mc.interactionManager.updateBlockBreakingProgress(tgt, Direction.UP);
        p.swingHand(Hand.MAIN_HAND);
    }

    /** Everything the rest of the build still needs (all layers). */
    static Map<Item, Integer> needAll(ClientWorld w) {
        Map<Item, Integer> m = new HashMap<>();
        for (int l = li; l < layers.size(); l++) {
            List<BlockPos> L = layers.get(l);
            for (int i = (l == li ? qi : 0); i < L.size(); i++) {
                BlockPos rel = L.get(i);
                if (done(w, rel)) continue;
                BlockState s = BuildSession.blocks.get(rel);
                Item it = BuildSession.itemOf(s);
                if (it != null) m.merge(it, BuildSession.countFor(s), Integer::sum);
            }
        }
        return m;
    }

    /** All layers placed: remove temporary blocks, pick up the last drops, store the loot. True while busy. */
    static boolean finishing(MinecraftClient mc) {
        if (++finishTicks > 3000) { Nav.abandonAll(); phase = ""; return false; }
        phase = "Removing temporary blocks";
        if (!Nav.cleanup(mc)) return true;
        phase = "Collecting loot";
        if (Loot.collect(mc)) return true;
        phase = "Storing loot";
        if (Loot.run(mc, true)) return true;
        phase = "";
        return false;
    }

    /** Target cannot be reached on foot: get scaffold blocks from the chests once, otherwise skip it. */
    static void unreachable(MinecraftClient mc, BlockPos rel) {
        if (hasScaffold(mc)) scaffoldFetchTried = false;
        else if (!scaffoldFetchTried && !BuildSession.chests.isEmpty()) {
            scaffoldFetchTried = true; wantScaffold = true; fetching = true; chestIdx = 0; stuck = 0;
            priorityItem = Items.COBBLESTONE; Nav.reset();
            msg(mc, "Unreachable spot: fetching scaffold blocks (cobblestone / dirt) from the chests");
            return;
        }
        skip(rel, hasScaffold(mc) ? "unreachable" : "unreachable - no scaffold blocks (cobblestone / dirt)");
        Nav.reset();
    }

    /** Placing a block that touches nothing: first put a temporary support block next to it. True = still working. */
    static boolean tempSupport(MinecraftClient mc, ClientWorld w, BlockPos tgt) {
        if (++supportTries > 700 || !hasScaffold(mc)) return false;
        Direction[] order = { Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
        for (Direction d : order) {
            BlockPos sp = tgt.offset(d);
            BlockState ss = w.getBlockState(sp);
            if ((ss.isAir() || ss.isReplaceable()) && !supports(w, sp).isEmpty()) return placeTemp(mc, sp);
        }
        // nothing solid around: hang next to the target on scaffold, then the spot under the target touches our floor
        Direction[] sides = { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
        BlockPos cell = tgt.offset(sides[supportDir % 4]);
        if (!Nav.cellFree(w, cell)) { if (++supportDir >= 4) { supportDir = 0; return false; } return true; }
        if (Nav.goCell(mc, cell)) return true;
        if (Nav.failed) { Nav.reset(); if (++supportDir >= 4) { supportDir = 0; return false; } }
        return true;
    }

    static boolean placeTemp(MinecraftClient mc, BlockPos sp) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        List<Direction> sups = supports(w, sp);
        if (sups.isEmpty()) return false;
        int slot = ensureScaffold(mc);
        if (slot < 0) return pendingSpawn;
        if (!walkNear(mc, sp, 4.0)) return !Nav.failed;
        if (p.getBoundingBox().intersects(new Box(sp))) {
            setSneak(mc, false);
            mc.options.backKey.setPressed(true);
            return true;
        }
        selectInv(mc, p, slot);
        setSneak(mc, true);
        if (!p.isSneaking()) return true;
        Direction d = sups.get(0);
        BlockPos nb = sp.offset(d);
        Direction side = d.getOpposite();
        Vec3d hit = hitPos(nb, side, 0.5);
        face(p, hit);
        sendLook(mc, p);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, side, nb, false));
        p.swingHand(Hand.MAIN_HAND);
        if (!Nav.scaffold.contains(sp)) Nav.scaffold.add(sp);
        wait = slow ? 8 : 3;
        return true;
    }
}
