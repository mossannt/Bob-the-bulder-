package com.bobsc;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Set;

/**
 * Keeps the player bot alive while it builds / digs (v17).
 *  - fights never step onto unsafe cells and never chase from a scaffold; when no safe step exists they do not freeze:
 *    they strafe, attack, or give up waiting after 3 s and carry on working
 *  - low health with a monster near: totem (off-hand), shield (off-hand, blocks while the sword recharges), golden apple, keep fighting
 *  - creepers: hit, back off 25 ticks (backwards or sideways), a lit fuse also makes it back off; at low health it only flees
 *  - the fight timer only counts ticks WITHOUT a hit; after 30 s of no progress the monster is ignored for 10 s (never when it is adjacent)
 *  - eating is interrupted when a monster comes close; eating is only started when the monster is far (> 8) and not a shooter
 *  - skeletons / blazes noticed from 16 blocks, any other Monster from 6; flying monsters only when they come down
 *  - a container (chest) screen is closed when a monster hits the bot
 *  - fights also work while standing in water
 *  - burning: walks to water within 6 blocks; air < 120: swims up; poison / wither: milk
 */
public class Safety {
    public static boolean enabled = true;

    static final float LOW_HEALTH = 6f;
    static final float BURST_LOSS = 9f;
    static final double NEAR = 6.0, RANGED = 16.0;
    static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
            Items.CHICKEN, Items.CHORUS_FRUIT, Items.ENCHANTED_GOLDEN_APPLE, Items.SUSPICIOUS_STEW);

    static boolean eating, blocking;
    static int eatTicks, fightTicks, retreat, totemCool, waitTicks, ignore, fireTicks;
    static float lastHealth = -1f, lossAcc;

    static void reset() {
        eating = false; blocking = false;
        eatTicks = 0; fightTicks = 0; retreat = 0; totemCool = 0; waitTicks = 0; ignore = 0; fireTicks = 0;
        lastHealth = -1f; lossAcc = 0f;
    }

    /** True when this tick was used (fighting / eating / paused). */
    static boolean tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (!p.isAlive()) {
            reset();
            BuilderBot.stop(mc, "§cThe bot died - progress is saved. Respawn and press J to carry on");
            return true;
        }
        if (!enabled || p.isCreative()) return false;
        Nav.W = mc.world;
        MobEntity mob = nearestThreat(mc, p);

        // ---- a chest is open and something hits the bot: close it and deal with the monster
        if (mc.currentScreen instanceof GenericContainerScreen) {
            if (p.hurtTime > 0 && mob != null && p.distanceTo(mob) < 5) { p.closeHandledScreen(); return true; }
            return false;
        }

        // ---- damage taken recently (decays 1.6 hp / s)
        float hp = p.getHealth();
        if (lastHealth >= 0 && hp < lastHealth) lossAcc += lastHealth - hp;
        lastHealth = hp;
        lossAcc = Math.max(0f, lossAcc - 0.08f);
        boolean low = hp <= LOW_HEALTH || lossAcc >= BURST_LOSS;

        // ---- eating / drinking in progress (interrupted when a monster comes close)
        if (eating) {
            if (mob != null && p.distanceTo(mob) < 3.5) {
                mc.options.useKey.setPressed(false);
                mc.interactionManager.stopUsingItem(p);
                eating = false; eatTicks = 0;
            } else {
                if ((p.isUsingItem() || eatTicks < 3) && ++eatTicks < 70) { mc.options.useKey.setPressed(true); return true; }
                mc.options.useKey.setPressed(false);
                eating = false; eatTicks = 0; BuilderBot.wait = 4;
                return true;
            }
        }

        // ---- running out of air: swim up
        if (p.isSubmergedInWater() && p.getAir() < 120) {
            mc.options.jumpKey.setPressed(true);
            BuilderBot.setSneak(mc, false);
            return true;
        }

        // ---- burning: run into water
        if (p.isOnFire() && !p.isTouchingWater() && !p.hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            if (seekWater(mc, p)) return true;
        } else fireTicks = 0;

        // ---- totem / shield in the off-hand (instant)
        if (totemCool > 0) totemCool--;
        else {
            ItemStack off = p.getOffHandStack();
            int t = off.isOf(Items.TOTEM_OF_UNDYING) ? -1 : find(p, Items.TOTEM_OF_UNDYING);
            if (t < 0 && off.isEmpty()) t = find(p, Items.SHIELD);
            if (t >= 0) {
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, Elytra.slotId(t), 40, SlotActionType.SWAP, p);
                totemCool = 40;
                return true;
            }
        }

        // ---- monsters close by
        boolean engage = mob != null && (p.isOnGround() || p.isTouchingWater());
        if (engage && ignore > 0) { ignore--; if (p.distanceTo(mob) > 3.2) engage = false; }
        if (engage) {
            double d = p.distanceTo(mob);
            if (low && d > 8 && !isRanged(mob) && tryHeal(mc, p)) return true;
            if (++fightTicks > 600) {                                        // 30 s without a hit
                fightTicks = 0;
                if (low) {
                    reset();
                    BuilderBot.stop(mc, "§cLow health and a monster is near - paused. Deal with it, then press J");
                    return true;
                }
                ignore = 200;
            } else if (fight(mc, p, mob, low)) return true;
        } else {
            fightTicks = 0; retreat = 0; waitTicks = 0;
            if (blocking) { mc.options.useKey.setPressed(false); blocking = false; }
        }

        // ---- poison / wither: milk
        if (hp <= 12 && (p.hasStatusEffect(StatusEffects.POISON) || p.hasStatusEffect(StatusEffects.WITHER))) {
            int m = find(p, Items.MILK_BUCKET);
            if (m >= 0) { startUse(mc, p, m); return true; }
        }

        // ---- hunger / health
        int food = p.getHungerManager().getFoodLevel();
        if (food <= 14 || (hp < 14 && food < 20)) {
            int slot = foodSlot(p, false);
            if (slot >= 0) { startUse(mc, p, slot); return true; }
            if (food <= 6) {
                BuilderBot.stop(mc, "§cThe bot is hungry and has no food - paused. Put food in the inventory, then press J");
                return true;
            }
        }
        if (hp <= LOW_HEALTH) {
            if (tryHeal(mc, p)) return true;
            BuilderBot.stop(mc, "§cLow health - paused. Heal, then press J");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ threats / items
    static boolean isRanged(MobEntity e) { return e instanceof AbstractSkeletonEntity || e instanceof BlazeEntity; }

    static boolean isFlying(MobEntity e) { return e instanceof PhantomEntity || e instanceof GhastEntity; }

    static MobEntity nearestThreat(MinecraftClient mc, ClientPlayerEntity p) {
        MobEntity best = null;
        double bd = 1e9;
        for (MobEntity e : mc.world.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(RANGED),
                e -> e.isAlive() && e instanceof Monster && !(e instanceof EndermanEntity)
                        && !(e instanceof ZombifiedPiglinEntity) && !(e instanceof GhastEntity))) {
            double d = p.distanceTo(e);
            if (d > NEAR && !isRanged(e)) continue;
            if (isFlying(e) && d > 4.5) continue;
            if (d < bd && p.canSee(e)) { bd = d; best = e; }
        }
        return best;
    }

    static int find(ClientPlayerEntity p, Item it) {
        var inv = p.getInventory().getMainStacks();
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).isOf(it)) return i;
        return -1;
    }

    static int weapon(ClientPlayerEntity p) {
        var inv = p.getInventory().getMainStacks();
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).isIn(ItemTags.SWORDS)) return i;
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).isIn(ItemTags.AXES)) return i;
        return -1;
    }

    /** emergency = golden apples first (the enchanted one when nothing else is there). */
    static int foodSlot(ClientPlayerEntity p, boolean emergency) {
        var inv = p.getInventory().getMainStacks();
        if (emergency) {
            int g = find(p, Items.GOLDEN_APPLE);
            if (g >= 0) return g;
        }
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty() && s.contains(DataComponentTypes.FOOD) && !BAD_FOOD.contains(s.getItem())) return i;
        }
        if (emergency) return find(p, Items.ENCHANTED_GOLDEN_APPLE);
        return -1;
    }

    static void startUse(MinecraftClient mc, ClientPlayerEntity p, int slot) {
        BuilderBot.selectInv(mc, p, slot);
        mc.options.useKey.setPressed(true);
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        eating = true; eatTicks = 0;
    }

    /** Golden apple (or the best food) when health is low. True = started eating. */
    static boolean tryHeal(MinecraftClient mc, ClientPlayerEntity p) {
        int slot = foodSlot(p, true);
        if (slot < 0) return false;
        ItemStack s = p.getInventory().getStack(slot);
        boolean gold = s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE);
        if (!gold && p.getHungerManager().getFoodLevel() >= 20) return false;     // normal food cannot be eaten when full
        startUse(mc, p, slot);
        return true;
    }

    // ------------------------------------------------------------ safe movement
    /** Is the cell one step from the bot in direction (ux,uz) a safe place to stand on? */
    static boolean safeStep(ClientPlayerEntity p, double ux, double uz) {
        if (Nav.scaffold.contains(p.getBlockPos().down())) return false;    // on a temporary pillar / bridge: do not walk
        int x = MathHelper.floor(p.getX() + ux * 0.9), z = MathHelper.floor(p.getZ() + uz * 0.9), y = p.getBlockY();
        if (!Nav.loaded(x, z)) return false;
        if (Nav.bodyFree(x, y, z)) {
            if (Nav.floorOk(x, y, z)) return true;
            return Nav.free(x, y - 1, z) && Nav.floorOk(x, y - 1, z);       // one block lower is fine
        }
        return Nav.solid(x, y, z) && Nav.bodyFree(x, y + 1, z) && Nav.floorOk(x, y + 1, z) && Nav.free(p.getBlockX(), y + 2, p.getBlockZ());
    }

    /** Away from the monster: backwards, else sideways. False = nowhere safe to go. */
    static boolean moveAway(MinecraftClient mc, ClientPlayerEntity p, double ux, double uz) {
        if (safeStep(p, -ux, -uz)) { mc.options.backKey.setPressed(true); return true; }
        if (safeStep(p, -uz, ux)) { mc.options.rightKey.setPressed(true); return true; }
        if (safeStep(p, uz, -ux)) { mc.options.leftKey.setPressed(true); return true; }
        return false;
    }

    /** Burning: walk to the nearest water (no lava / cliff on the way). */
    static boolean seekWater(MinecraftClient mc, ClientPlayerEntity p) {
        if (++fireTicks > 100) return false;
        BlockPos b = p.getBlockPos(), best = null;
        double bd = 1e9;
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -6; dz <= 6; dz++)
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos q = b.add(dx, dy, dz);
                    if (!mc.world.getBlockState(q).isOf(Blocks.WATER)) continue;
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d < bd) { bd = d; best = q; }
                }
        if (best == null) return false;
        double dx = best.getX() + 0.5 - p.getX(), dz = best.getZ() + 0.5 - p.getZ();
        double len = Math.max(1e-4, Math.hypot(dx, dz));
        int ax = MathHelper.floor(p.getX() + dx / len * 0.9), az = MathHelper.floor(p.getZ() + dz / len * 0.9), y = p.getBlockY();
        if (Nav.hazard(ax, y, az) || Nav.hazard(ax, y - 1, az)) return false;
        if (!(Nav.solid(ax, y - 1, az) || Nav.solid(ax, y - 2, az) || Nav.wet(ax, y, az) || Nav.wet(ax, y - 1, az))) return false;
        p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f);
        BuilderBot.setSneak(mc, false);
        mc.options.forwardKey.setPressed(true);
        if (p.horizontalCollision) mc.options.jumpKey.setPressed(true);
        return true;
    }

    // ------------------------------------------------------------ fight
    /** True = this tick was used. False = nothing sensible to do, carry on working. */
    static boolean fight(MinecraftClient mc, ClientPlayerEntity p, MobEntity e, boolean low) {
        int w = weapon(p);
        if (w >= 0) BuilderBot.selectInv(mc, p, w);
        BuilderBot.setSneak(mc, false);
        double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
        double len = Math.max(1e-4, Math.hypot(dx, dz));
        double ux = dx / len, uz = dz / len;
        p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f);
        p.setPitch((float) -Math.toDegrees(Math.atan2(e.getEyeY() - p.getEyeY(), len)));
        double d = p.distanceTo(e);
        boolean ready = p.getAttackCooldownProgress(0.5f) >= 0.95f;

        // shield up while the weapon recharges (low health, shield in the off-hand)
        boolean wantBlock = low && !ready && d <= 4.0 && p.getOffHandStack().isOf(Items.SHIELD);
        mc.options.useKey.setPressed(wantBlock);
        blocking = wantBlock;

        if (retreat > 0) {
            retreat--;
            if (moveAway(mc, p, ux, uz)) return true;
            retreat = 0;                                                     // nowhere to go: fight on
        }
        if (e instanceof CreeperEntity c) {
            boolean lit = c.isIgnited();
            if (d <= 3.0 && ready && !low) { hit(mc, p, e); retreat = 25; return true; }
            if (d < 3.6 || (lit && d < 5)) {
                if (moveAway(mc, p, ux, uz)) { retreat = lit ? 15 : 12; return true; }
                if (d <= 3.0 && ready) { hit(mc, p, e); return true; }      // cornered: hit it, it dies / is pushed away
                return true;
            }
            if (d <= 7) return ++waitTicks < 60;                             // let it come, do not walk into it
            return false;
        }
        if (d > 2.8) {
            if (isRanged(e) && d > NEAR && !safeStep(p, ux, uz)) return false;   // shooter far away, no safe way: keep working
            if (safeStep(p, ux, uz)) {
                mc.options.forwardKey.setPressed(true);
                if (p.horizontalCollision) mc.options.jumpKey.setPressed(true);
                return true;
            }
            return d <= NEAR && ++waitTicks < 60;                            // unsafe to chase: wait 3 s, then carry on
        }
        if (ready) hit(mc, p, e);
        return true;
    }

    static void hit(MinecraftClient mc, ClientPlayerEntity p, MobEntity e) {
        mc.options.useKey.setPressed(false);
        blocking = false;
        mc.interactionManager.attackEntity(p, e);
        p.swingHand(Hand.MAIN_HAND);
        fightTicks = 0; waitTicks = 0;
    }
}
