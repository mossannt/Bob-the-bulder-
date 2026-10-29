package com.bobsc;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Creative-mode helper: fills the material chests with everything the building needs.
 * Missing chests are placed first (at the spots from BuildSession.layoutChests()).
 * Items are spawned into free inventory slots (creative) and shift-clicked into the chest.
 */
public class CreativeFill {
    enum Step { PREP, OPEN, GUI, CLEAN }

    static boolean active;
    static Step step = Step.PREP;
    static final List<List<ItemStack>> batches = new ArrayList<>();
    static final List<Integer> used = new ArrayList<>();   // inventory indices holding our spawned stacks
    static int idx, wait, openTicks, stuck, chestHotbar = -1, overflow;

    /** Button for the corner of a screen. Greyed out when the player is not in creative. */
    public static ButtonWidget button(MinecraftClient mc, int x, int y, int w) {
        ButtonWidget b = ButtonWidget.builder(Text.literal("Creative: Fill chests"), bt -> {
            mc.setScreen(null);
            start(mc);
        }).dimensions(x, y, w, 20).build();
        b.active = mc.player != null && mc.player.isCreative();
        return b;
    }

    public static boolean busy() { return active; }

    public static void start(MinecraftClient mc) {
        if (mc.player == null) return;
        if (!mc.player.isCreative()) { BuilderBot.msg(mc, "§cCreative mode only"); return; }
        if (BuildSession.blocks.isEmpty()) { BuilderBot.msg(mc, "§cChoose a building first (key B)"); return; }
        if (BuilderBot.busy()) { BuilderBot.msg(mc, "§cStop the bot first"); return; }

        if (BuildSession.chests.size() != BuildSession.chestCount()) BuildSession.layoutChests();

        List<ItemStack> all = new ArrayList<>();
        for (var e : BuildSession.required().entrySet()) {
            int left = e.getValue(), max = e.getKey().getMaxCount();
            while (left > 0) {
                int n = Math.min(left, max);
                all.add(new ItemStack(e.getKey(), n));
                left -= n;
            }
        }
        batches.clear();
        for (int i = 0; i < all.size(); i += 27)
            batches.add(new ArrayList<>(all.subList(i, Math.min(all.size(), i + 27))));
        if (batches.isEmpty()) { BuilderBot.msg(mc, "§cNothing to fill"); return; }

        idx = 0; wait = 0; openTicks = 0; stuck = 0; overflow = 0; chestHotbar = -1;
        used.clear(); step = Step.PREP; active = true;
        BuilderBot.msg(mc, "Filling " + batches.size() + " chest(s)...");
    }

    public static void stop(MinecraftClient mc, String s) {
        if (mc.player != null && mc.interactionManager != null && mc.player.isCreative()) cleanInventory(mc);
        active = false;
        if (mc.player != null) { BuilderBot.release(mc); BuilderBot.setSneak(mc, false); }
        if (s != null) BuilderBot.msg(mc, s);
    }

    // ------------------------------------------------------------ tick
    public static void tick(MinecraftClient mc) {
        if (!active) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) { active = false; return; }
        if (!p.isCreative()) { stop(mc, "§cLeft creative mode"); return; }
        BuilderBot.release(mc);
        if (wait > 0) { wait--; return; }
        try { run(mc); } catch (Exception e) { e.printStackTrace(); stop(mc, "Error: " + e); }
    }

    static int slotId(int inv) { return inv < 9 ? 36 + inv : inv; }

    static void cleanInventory(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        for (int inv : used)
            if (!p.getInventory().getMainStacks().get(inv).isEmpty())
                mc.interactionManager.clickCreativeStack(ItemStack.EMPTY, slotId(inv));
        used.clear();
        if (chestHotbar >= 0) {
            mc.interactionManager.clickCreativeStack(ItemStack.EMPTY, slotId(chestHotbar));
            chestHotbar = -1;
        }
    }

    static List<Integer> emptySlots(ClientPlayerEntity p) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 36; i++)
            if (i != chestHotbar && p.getInventory().getMainStacks().get(i).isEmpty()) out.add(i);
        return out;
    }

    static void nextChest() { idx++; stuck = 0; openTicks = 0; step = Step.PREP; wait = 4; }

    static void run(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;

        // ---- chest GUI is open: shift-click our stacks in
        if (mc.currentScreen instanceof GenericContainerScreen gs && step == Step.GUI) {
            if (openTicks++ < 3) return;
            GenericContainerScreenHandler h = gs.getScreenHandler();
            int n = h.getRows() * 9;
            for (int inv : used) {
                int slot = inv >= 9 ? n + (inv - 9) : n + 27 + inv;
                if (!h.getSlot(slot).getStack().isEmpty())
                    mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.QUICK_MOVE, p);
            }
            p.closeHandledScreen();
            openTicks = 0; step = Step.CLEAN; wait = 6;
            return;
        }

        if (step == Step.CLEAN) {
            for (int inv : used) overflow += p.getInventory().getMainStacks().get(inv).isEmpty() ? 0 : 1;
            cleanInventory(mc);
            nextChest();
            return;
        }

        if (idx >= batches.size()) {
            stop(mc, null);
            BuilderBot.msg(mc, overflow == 0 ? "§aChests filled ✔"
                    : "§eDone, but some stacks did not fit (chest full / existing items)");
            return;
        }
        BlockPos c = BuildSession.chests.get(idx);

        if (step == Step.GUI) {                       // waiting for the GUI to open
            if (++stuck > 40) { stuck = 0; step = Step.OPEN; }
            return;
        }

        BlockState cs = w.getBlockState(c);
        boolean chest = cs.getBlock() instanceof ChestBlock;

        if (step == Step.OPEN) {
            if (!chest) { step = Step.PREP; return; }
            if (!BuilderBot.walkNear(mc, c, 3.5)) {
                if (++stuck > 400) { BuilderBot.msg(mc, "§cCan't reach chest " + c.toShortString()); cleanInventory(mc); nextChest(); }
                return;
            }
            stuck = 0; openTicks = 0;
            BuilderBot.openChest(mc, c);
            step = Step.GUI;
            wait = 10;
            return;
        }

        // ---- PREP: make sure a chest exists, then spawn the stacks
        if (chest) {
            BuilderBot.setSneak(mc, false);
            if (chestHotbar >= 0) {
                mc.interactionManager.clickCreativeStack(ItemStack.EMPTY, slotId(chestHotbar));
                chestHotbar = -1;
            }
            List<ItemStack> b = batches.get(idx);
            List<Integer> empty = emptySlots(p);
            if (empty.size() < b.size()) {
                stop(mc, "§cNeed " + b.size() + " free inventory slots (have " + empty.size() + ")");
                return;
            }
            used.clear();
            for (int k = 0; k < b.size(); k++) {
                int inv = empty.get(k);
                mc.interactionManager.clickCreativeStack(b.get(k).copy(), slotId(inv));
                used.add(inv);
            }
            step = Step.OPEN; wait = 6; stuck = 0;
            return;
        }

        // something solid in the way -> break it (creative = instant)
        if (!cs.isAir() && !cs.isReplaceable()) {
            if (!BuilderBot.walkNear(mc, c, 4.0)) {
                if (++stuck > 300) { BuilderBot.msg(mc, "§cCan't reach " + c.toShortString()); nextChest(); }
                return;
            }
            BuilderBot.face(p, Vec3d.ofCenter(c));
            BuilderBot.sendLook(mc, p);
            mc.interactionManager.attackBlock(c, Direction.UP);
            p.swingHand(Hand.MAIN_HAND);
            wait = 4;
            return;
        }

        List<Direction> sups = BuilderBot.supports(w, c);
        if (sups.isEmpty()) {
            BuilderBot.msg(mc, "§cNo block to attach a chest to at " + c.toShortString());
            nextChest();
            return;
        }
        if (!BuilderBot.walkNear(mc, c, 4.0)) {
            if (++stuck > 300) { BuilderBot.msg(mc, "§cCan't reach " + c.toShortString()); nextChest(); }
            return;
        }
        if (p.getBoundingBox().intersects(new Box(c))) {      // don't stand inside the chest spot
            BuilderBot.setSneak(mc, false);
            mc.options.backKey.setPressed(true);
            return;
        }
        if (chestHotbar < 0) {                                  // spawn a chest item in a free hotbar slot
            int h = -1;
            for (int i = 0; i < 9; i++) if (p.getInventory().getMainStacks().get(i).isEmpty()) { h = i; break; }
            if (h < 0) { stop(mc, "§cFree one hotbar slot first"); return; }
            mc.interactionManager.clickCreativeStack(new ItemStack(Items.CHEST), slotId(h));
            chestHotbar = h;
            p.getInventory().setSelectedSlot(h);
            wait = 3;
            return;
        }
        p.getInventory().setSelectedSlot(chestHotbar);
        BuilderBot.setSneak(mc, true);                          // don't open things by accident
        if (!p.isSneaking()) return;
        Direction d = sups.get(0);
        BlockPos nb = c.offset(d);
        Direction side = d.getOpposite();
        Vec3d hit = BuilderBot.hitPos(nb, side, 0.5);
        BuilderBot.face(p, hit);
        BuilderBot.sendLook(mc, p);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                new net.minecraft.util.hit.BlockHitResult(hit, side, nb, false));
        p.swingHand(Hand.MAIN_HAND);
        wait = 5;
        if (++stuck > 12) { BuilderBot.msg(mc, "§cCould not place chest at " + c.toShortString()); cleanInventory(mc); nextChest(); }
    }
}
