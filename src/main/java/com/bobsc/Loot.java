package com.bobsc;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Loot handling of the player bot: picks up the drops of everything it breaks and stores everything the
 * building does not need (plus tools / scaffold blocks it must keep) in "loot chests".
 * Loot chests stand on the other side of the material chests (z - 2, z - 4 ...). Missing chests are placed
 * (chest item from the inventory, or spawned in creative). Material chests are the fallback.
 */
public class Loot {
    public static final List<BlockPos> chests = new ArrayList<>();
    static final Set<Integer> ignore = new HashSet<>();
    static final Set<BlockPos> full = new HashSet<>();
    static final List<BlockPos> cands = new ArrayList<>();
    public static boolean depositing;
    static boolean warned;
    static int ci, openTicks, stuck, opens, placeTries, cooldown, spotIdx, pickTarget = -1, pickTicks;

    public static void reset() { chests.clear(); spotIdx = 0; softReset(); }

    public static void softReset() {
        ignore.clear(); full.clear(); cands.clear();
        depositing = false; warned = false; cooldown = 0; pickTarget = -1; pickTicks = 0;
    }

    static BlockPos lootSpot(int k) {
        if (BuilderBot.mode == BuilderBot.Mode.DIG) return Digger.chestSpot(k);
        return BuildSession.origin.add(-2 - (k / 4) * 2, 0, -2 - (k % 4) * 2);
    }

    static int freeSlots(ClientPlayerEntity p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getMainStacks()) if (s.isEmpty()) n++;
        return n;
    }

    static boolean isTool(ItemStack s) {
        return s.isIn(ItemTags.PICKAXES) || s.isIn(ItemTags.AXES) || s.isIn(ItemTags.SHOVELS) || s.isIn(ItemTags.HOES)
                || s.isIn(ItemTags.SWORDS) || s.contains(net.minecraft.component.DataComponentTypes.FOOD)
                || s.isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING) || s.isOf(net.minecraft.item.Items.MILK_BUCKET) || s.isOf(net.minecraft.item.Items.SHIELD);
    }

    /** Items we hold that the rest of the build does not need. */
    static Map<Item, Integer> surplus(ClientPlayerEntity p, ClientWorld w) {
        Map<Item, Integer> need = BuilderBot.needAll(w);
        Item sc = BuilderBot.scaffoldItem(p);
        Map<Item, Integer> have = new HashMap<>();
        for (ItemStack s : p.getInventory().getMainStacks()) {
            if (s.isEmpty() || isTool(s)) continue;
            if (BuilderBot.mode == BuilderBot.Mode.DIG && s.getItem() != sc && Digger.unwanted.contains(s.getItem())) continue;   // trash, not loot
            have.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        Map<Item, Integer> out = new HashMap<>();
        for (var e : have.entrySet()) {
            Item it = e.getKey();
            int keep = need.getOrDefault(it, 0);
            if (it == sc) keep = Math.max(keep, 64);
            if (it == Items.CHEST) keep = Math.max(keep, BuilderBot.mode == BuilderBot.Mode.DIG ? 4096 : 2);
            int sur = e.getValue() - keep;
            if (sur > 0) out.put(it, sur);
        }
        return out;
    }

    // ------------------------------------------------------------ pick up drops
    /** Walks to dropped items near the bot. Returns true while busy. */
    public static boolean collect(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (freeSlots(p) == 0) return false;
        Box box = p.getBoundingBox().expand(10, 6, 10);
        ItemEntity best = null;
        double bd = 1e18;
        for (ItemEntity e : w.getEntitiesByClass(ItemEntity.class, box, e -> !e.isRemoved() && !ignore.contains(e.getId())
                && !(BuilderBot.mode == BuilderBot.Mode.DIG && Digger.unwanted.contains(e.getStack().getItem())))) {
            double dx = e.getX() - p.getX(), dy = e.getY() - p.getY(), dz = e.getZ() - p.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd) { bd = d; best = e; }
        }
        if (best == null) { pickTarget = -1; return false; }
        if (best.getId() != pickTarget) { pickTarget = best.getId(); pickTicks = 0; }
        if (++pickTicks > 160) { ignore.add(best.getId()); pickTarget = -1; return false; }
        Nav.goFeet(mc, best.getX(), best.getY(), best.getZ(), 1.3);
        if (Nav.failed) { ignore.add(best.getId()); pickTarget = -1; Nav.reset(); return false; }
        return true;
    }

    // ------------------------------------------------------------ deposit
    static void buildCands(MinecraftClient mc) {
        cands.clear();
        ClientWorld w = mc.world;
        for (BlockPos c : chests)
            if (w.getBlockState(c).getBlock() instanceof ChestBlock && !full.contains(c)) cands.add(c);
        if (BuilderBot.chestAvailable(mc.player)) {
            while (w.getBlockState(lootSpot(spotIdx)).getBlock() instanceof ChestBlock) spotIdx++;
            cands.add(lootSpot(spotIdx));
        }
        if (BuilderBot.mode != BuilderBot.Mode.DIG)
            for (BlockPos c : BuildSession.chests)
                if (w.getBlockState(c).getBlock() instanceof ChestBlock && !full.contains(c)) cands.add(c);
    }

    static void endDeposit(MinecraftClient mc, boolean ok) {
        depositing = false;
        cooldown = ok ? 100 : 400;
        if (!ok && !warned) {
            warned = true;
            BuilderBot.msg(mc, "§eLoot could not be stored (no free chest). Put chests into the inventory or use more material chests.");
        }
    }

    /** Returns true while the deposit is running. {@code force}: also when the inventory is not nearly full. */
    public static boolean run(MinecraftClient mc, boolean force) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (!depositing) {
            if (cooldown > 0) { cooldown--; return false; }
            if (!force && freeSlots(p) > 4) return false;
            if (surplus(p, w).isEmpty()) { cooldown = 200; return false; }
            buildCands(mc);
            if (cands.isEmpty()) { endDeposit(mc, false); return false; }
            depositing = true; ci = 0; stuck = 0; openTicks = 0; opens = 0; placeTries = 0;
        }

        if (mc.currentScreen instanceof GenericContainerScreen gs) {          // chest is open: move the surplus in
            if (openTicks++ < 3) return true;
            GenericContainerScreenHandler h = gs.getScreenHandler();
            int n = h.getRows() * 9;
            Map<Item, Integer> sur = surplus(p, w);
            for (int inv = 0; inv < 36; inv++) {
                ItemStack s = p.getInventory().getMainStacks().get(inv);
                if (s.isEmpty()) continue;
                int left = sur.getOrDefault(s.getItem(), 0);
                if (left >= s.getCount()) {
                    int slot = inv >= 9 ? n + (inv - 9) : n + 27 + inv;
                    int cnt = s.getCount();
                    mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.QUICK_MOVE, p);
                    sur.put(s.getItem(), left - cnt);
                }
            }
            p.closeHandledScreen();
            BuilderBot.wait = 6;
            openTicks = 0;
            if (surplus(p, w).isEmpty()) { endDeposit(mc, true); return false; }
            if (ci < cands.size()) full.add(cands.get(ci));
            ci++; stuck = 0; opens = 0; placeTries = 0;
            return true;
        }
        openTicks = 0;

        if (ci >= cands.size()) { endDeposit(mc, false); return false; }
        BlockPos c = cands.get(ci);
        if (!(w.getBlockState(c).getBlock() instanceof ChestBlock)) {
            if (!placeChest(mc, c)) { ci++; stuck = 0; opens = 0; placeTries = 0; }
            return true;
        }
        if (!chests.contains(c) && !BuildSession.chests.contains(c)) { chests.add(c); spotIdx++; }
        if (!BuilderBot.walkNear(mc, c, 3.5)) {
            if (Nav.failed || ++stuck > 500) { ci++; stuck = 0; opens = 0; }
            return true;
        }
        if (++opens > 6) { ci++; stuck = 0; opens = 0; return true; }       // chest will not open (blocked above?)
        BuilderBot.openChest(mc, c);
        return true;
    }

    /** One step of placing a chest at c. Returns false when it is impossible. */
    static boolean placeChest(MinecraftClient mc, BlockPos c) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        int slot = BuilderBot.findOrSpawn(mc, Items.CHEST, 8);
        if (slot < 0) return BuilderBot.pendingSpawn;
        BlockState cs = w.getBlockState(c);
        if (!cs.isAir() && !cs.isReplaceable()) {                              // something in the way: break it
            if (!BuilderBot.walkNear(mc, c, 4.0)) return !Nav.failed;
            BuilderBot.setSneak(mc, false);
            BuilderBot.breakBlock(mc, c);
            return true;
        }
        List<Direction> sups = BuilderBot.supports(w, c);
        if (sups.isEmpty()) return false;
        if (!BuilderBot.walkNear(mc, c, 4.0)) return !Nav.failed;
        if (p.getBoundingBox().intersects(new Box(c))) {
            BuilderBot.setSneak(mc, false);
            mc.options.backKey.setPressed(true);
            return true;
        }
        BuilderBot.selectInv(mc, p, slot);
        BuilderBot.setSneak(mc, true);
        if (!p.isSneaking()) return true;
        Direction d = sups.get(0);
        BlockPos nb = c.offset(d);
        Direction side = d.getOpposite();
        Vec3d hit = BuilderBot.hitPos(nb, side, 0.5);
        BuilderBot.face(p, hit);
        BuilderBot.sendLook(mc, p);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, side, nb, false));
        p.swingHand(Hand.MAIN_HAND);
        BuilderBot.wait = 5;
        return ++placeTries <= 12;
    }
}
