package com.bobsc.screen;

import com.bobsc.BuilderBot;
import com.bobsc.Digger;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Digger: choose corner A and corner B, see how many chests the drops need, collect them or leave them, start. */
public class DiggerScreen extends UiScreen {
    final List<String> lines = new ArrayList<>();

    public DiggerScreen() { super("Dig an area"); }

    @Override protected String subtitle() { return Digger.running() ? "Digging..." : "Dig everything between corner A and corner B"; }

    @Override protected int subtitleColor() { return Digger.running() ? GREEN : GRAY; }

    @Override
    protected void content() {
        section("1. Choose the area");
        caption("Look at a block and press a button. Corner A and corner B make a box.");
        buttons("Corner A: block I look at", () -> { Digger.a = Digger.lookTarget(client); refresh(); },
                "Corner B: block I look at", () -> { Digger.b = Digger.lookTarget(client); refresh(); });
        buttons("A: block under me", () -> { Digger.a = client.player.getBlockPos().down(); refresh(); },
                "B: block under me", () -> { Digger.b = client.player.getBlockPos().down(); refresh(); });
        buttons("Clear the area", () -> { Digger.a = null; Digger.b = null; refresh(); },
                "Recalculate", this::refresh);

        section("2. What happens to the items?");
        button("Items: " + (Digger.collect ? "§aput in chests" : "§7leave on the ground"),
                "Click to change. The bot places chests next to the area when it collects.", () -> {
                    Digger.collect = !Digger.collect;
                    clearAndInit();
                });
        button("Choose what to keep..." + (Digger.unwanted.isEmpty() ? "" : "  (" + Digger.unwanted.size() + " thrown)"),
                "Pick which items are collected and which are thrown away.", () -> client.setScreen(new DigFilterScreen()));

        section("3. Go!");
        buttons("§aStart digging", () -> {
            String err = Digger.start(client);
            if (err != null) BuilderBot.msg(client, "§c" + err); else close();
        }, "§ePause", () -> BuilderBot.stop(client, "Paused - press J to carry on"));

        section("Info");
        fillLines();
        for (String l : lines) text(l, WHITE);
    }

    void refresh() {
        Digger.scan(client);
        clearAndInit();
    }

    static String pos(BlockPos p) { return p == null ? "§7not set" : p.getX() + ", " + p.getY() + ", " + p.getZ(); }

    void fillLines() {
        lines.clear();
        lines.add("Corner A: " + pos(Digger.a));
        lines.add("Corner B: " + pos(Digger.b));
        if (!Digger.ready()) {
            lines.add("§7Set both corners to see the size and the chests needed.");
            return;
        }
        int sx = Math.abs(Digger.a.getX() - Digger.b.getX()) + 1, sy = Math.abs(Digger.a.getY() - Digger.b.getY()) + 1,
            sz = Math.abs(Digger.a.getZ() - Digger.b.getZ()) + 1;
        lines.add("Size: " + sx + " x " + sy + " x " + sz + " = " + (long) sx * sy * sz + " blocks");
        if (!Digger.error.isEmpty()) { lines.add("§c" + Digger.error); return; }
        lines.add("To dig: " + Digger.toDig + " blocks"
                + (Digger.unbreakable > 0 ? "  (§eunbreakable skipped: " + Digger.unbreakable + "§r)" : ""));
        lines.add("Items: " + Digger.drops.size() + " kinds, " + Digger.stacks() + " stacks to keep"
                + (Digger.unwanted.isEmpty() ? "" : "  (§7" + Digger.unwanted.size() + " kinds thrown§r)"));
        if (Digger.collect) {
            int need = Digger.chestsNeeded(), have = Digger.chestsInInventory(client);
            boolean ok = have >= need || client.player.isCreative();
            lines.add((ok ? "§a" : "§c") + "Chests needed: " + need + "   (you have: " + have + ")");
            if (!ok) lines.add("§eThe bot places the chests next to the area. Put " + (need - have) + " more chests in your inventory.");
        } else {
            lines.add("§7The items stay on the ground (nothing is collected).");
        }
        if (Digger.unloaded) lines.add("§eSome of the area is not loaded - go closer and press Recalculate.");
        List<Map.Entry<Item, Integer>> top = new ArrayList<>();
        for (var e : Digger.drops.entrySet()) if (!Digger.unwanted.contains(e.getKey())) top.add(e);
        top.sort((p, q) -> q.getValue() - p.getValue());
        for (int i = 0; i < Math.min(8, top.size()); i++) {
            Item it = top.get(i).getKey();
            int max = Math.max(1, it.getMaxCount()), n = top.get(i).getValue();
            lines.add("  " + it.getName().getString() + "  x" + n + "  (" + (n + max - 1) / max + " stacks)");
        }
        if (top.size() > 8) lines.add("  ... +" + (top.size() - 8) + " more kinds");
    }
}
