package com.bobsc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * Elytra flight for the player bot. Used automatically when the bot HAS an elytra (worn or in the inventory)
 * and the goal is far away (> 45 blocks) or high above it (needs fire-work rockets).
 *  - picks a free standing cell near the goal ("stand") and shows it in the world (cyan box + beam, see HologramRenderer)
 *  - equips the elytra, jumps, opens the wings, steers to the cell, uses fire-work rockets when it has them
 *  - lands, then normal walking / scaffolding continues from there
 */
public class Elytra {
    enum State { NONE, EQUIP, TAKEOFF, GLIDE }
    static State state = State.NONE;

    /** The cell the bot will stand on after the flight (feet cell). Null when no flight is planned. */
    public static BlockPos stand;

    static int ticks, takeoff, airT, lost, lastRocket, equipStep;
    static long retryAt;

    public static void reset() { state = State.NONE; stand = null; retryAt = 0; }

    // ------------------------------------------------------------ inventory
    static boolean usable(ItemStack s) { return s.isOf(Items.ELYTRA) && s.getMaxDamage() - s.getDamage() > 1; }

    static boolean equipped(ClientPlayerEntity p) { return usable(p.getEquippedStack(EquipmentSlot.CHEST)); }

    static int invElytra(ClientPlayerEntity p) {
        var inv = p.getInventory().getMainStacks();
        for (int i = 0; i < inv.size(); i++) if (usable(inv.get(i))) return i;
        return -1;
    }

    static int rockets(ClientPlayerEntity p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getMainStacks()) if (s.isOf(Items.FIREWORK_ROCKET)) n += s.getCount();
        return n;
    }

    public static boolean has(ClientPlayerEntity p) { return equipped(p) || invElytra(p) >= 0; }

    static int slotId(int inv) { return inv < 9 ? 36 + inv : inv; }

    /** One step towards wearing the elytra. False = impossible. */
    static boolean equipStep(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        int i = invElytra(p);
        if (i < 0) return false;
        int syncId = p.playerScreenHandler.syncId;
        if (p.getEquippedStack(EquipmentSlot.CHEST).isEmpty()) {
            mc.interactionManager.clickSlot(syncId, slotId(i), 0, SlotActionType.QUICK_MOVE, p);   // shift-click = wear it
        } else if (i >= 9) {                                                                       // chest-plate is worn: swap via hotbar
            mc.interactionManager.clickSlot(syncId, i, 8, SlotActionType.SWAP, p);
        } else {
            mc.interactionManager.clickSlot(syncId, 6, i, SlotActionType.SWAP, p);
        }
        BuilderBot.wait = 3;
        return ++equipStep < 8;
    }

    // ------------------------------------------------------------ start
    /** Called by Nav before it plans a walk. True = a flight was started (caller then calls tick). */
    static boolean tryStart(MinecraftClient mc, Nav.Goal g) {
        if (state != State.NONE || System.currentTimeMillis() < retryAt) return false;
        ClientPlayerEntity p = mc.player;
        double hd = Math.hypot(g.c().x - p.getX(), g.c().z - p.getZ());
        double dy = g.c().y - p.getY();
        if (!(hd > 45 || dy > 12)) return false;
        if (!has(p) || !p.isOnGround()) return false;
        if (Safety.enabled && !p.isCreative() && p.getHealth() < 10f) return false;     // a crash landing could kill a weak bot
        int rk = rockets(p);
        if (rk == 0 && p.getY() - g.c().y < hd / 7.0) return false;      // glide only: needs enough height for the distance
        if (rk > 0 && dy > 12 && rk < 1) return false;
        BlockPos s = findStand(mc, g);
        if (s == null) { retryAt = System.currentTimeMillis() + 5000; return false; }
        stand = s; state = State.EQUIP; ticks = 0; takeoff = 0; airT = 0; lost = 0; equipStep = 0; lastRocket = -100;
        BuilderBot.phase = "Flying (elytra) to " + s.getX() + " " + s.getY() + " " + s.getZ();
        BuilderBot.msg(mc, "Elytra: flying to the standing point " + s.toShortString());
        return true;
    }

    /** Free cell with a floor, inside the goal's reach, closest to the bot; outside the building when possible. */
    static BlockPos findStand(MinecraftClient mc, Nav.Goal g) {
        Nav.W = mc.world;
        if (g.exact() != null) {
            BlockPos e = g.exact();
            return Nav.bodyFree(e.getX(), e.getY(), e.getZ()) && Nav.floorOk(e.getX(), e.getY(), e.getZ()) ? e : null;
        }
        ClientPlayerEntity p = mc.player;
        int r = (int) Math.ceil(g.dist()) + 1;
        int bx = MathHelper.floor(g.c().x), by = MathHelper.floor(g.c().y), bz = MathHelper.floor(g.c().z);
        BlockPos best = null;
        double bd = 1e18;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int x = bx + dx, z = bz + dz;
                if (!Nav.loaded(x, z)) continue;
                for (int y = by - r - 1; y <= by + r; y++) {
                    if (!g.ok(x, y, z) || !Nav.bodyFree(x, y, z) || !Nav.floorOk(x, y, z)) continue;
                    double d = Math.hypot(x + 0.5 - p.getX(), z + 0.5 - p.getZ()) + 6 * Nav.footprint(new BlockPos(x, y, z));
                    if (d < bd) { bd = d; best = new BlockPos(x, y, z); }
                }
            }
        return best;
    }

    // ------------------------------------------------------------ flight
    static void end(MinecraftClient mc, String why) {
        state = State.NONE; stand = null;
        BuilderBot.phase = "";
        retryAt = System.currentTimeMillis() + (why == null ? 1000 : 6000);
        if (why != null) BuilderBot.msg(mc, "Elytra stopped: " + why);
        Nav.reset();
    }

    static void aim(ClientPlayerEntity p, double dx, double dz, float pitch) {
        p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f);
        p.setPitch(pitch);
    }

    /** One tick of flight control. Returns true while it controls the bot, false when the flight is over. */
    static boolean tick(MinecraftClient mc) {
        if (state == State.NONE || stand == null) return false;
        ClientPlayerEntity p = mc.player;
        double dx = stand.getX() + 0.5 - p.getX(), dz = stand.getZ() + 0.5 - p.getZ();
        BuilderBot.setSneak(mc, false);
        if (++ticks > 1500) { end(mc, "took too long"); return false; }

        if (state == State.EQUIP) {
                if (equipped(p)) { state = State.TAKEOFF; return true; }
                if (!equipStep(mc)) { end(mc, "could not wear the elytra"); return false; }
            return true;
        }
        if (state == State.TAKEOFF) {
                if (p.isGliding()) { state = State.GLIDE; lost = 0; return true; }
                if (++takeoff > 80) { end(mc, "could not take off"); return false; }
                aim(p, dx, dz, -10f);
                if (p.isOnGround()) { mc.options.jumpKey.setPressed(true); airT = 0; }
                else if (++airT % 2 == 0 && p.getVelocity().y < 0.1) mc.options.jumpKey.setPressed(true);   // second press opens the wings
            return true;
        }
        {
                if (!p.isGliding()) {
                    if (p.isOnGround()) { end(mc, null); return false; }                 // landed
                    if (++lost > 25) { end(mc, "wings did not open"); return false; }
                    if (++airT % 2 == 0) mc.options.jumpKey.setPressed(true);
                    return true;
                }
                lost = 0;
                double hd = Math.hypot(dx, dz), h = p.getY() - stand.getY();
                float pitch;
                if (hd < 14) pitch = h > 6 ? 35f : h > 2 ? 15f : -8f;                    // come down, flare at the end
                else pitch = (float) MathHelper.clamp(Math.toDegrees(Math.atan2(h, hd)) * 1.2, -35, 45);
                if (h < 10 && p.getVelocity().length() > 0.9) pitch = -15f;               // brake before the ground
                aim(p, dx, dz, pitch);

                int rk = rockets(p);
                double speed = p.getVelocity().length();
                if (rk > 0 && ticks - lastRocket > 30 && hd > 15 && (speed < 0.9 || h < -3)) {
                    int slot = BuilderBot.findInv(p, Items.FIREWORK_ROCKET);
                    if (slot >= 0) {
                        BuilderBot.selectInv(mc, p, slot);
                        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
                        p.swingHand(Hand.MAIN_HAND);
                        lastRocket = ticks;
                    }
                }
                return true;
        }
    }
}
