package com.bobsc.screen;

import com.bobsc.BuildSession;
import com.bobsc.BuilderBot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;

/** Build wizard: seven steps in order. Green tick = done, yellow arrow = do this next. */
public class BuildMenuScreen extends UiScreen {
    static final String[] NAMES = { "", "Choose a building", "Place the building", "See the materials",
            "Put the chests", "Check the chests", "Check my inventory", "START building" };

    public BuildMenuScreen() { super("Build a building"); }

    @Override protected String subtitle() {
        return BuildSession.name.isEmpty() ? "No building chosen yet" : BuildSession.name;
    }

    @Override protected int subtitleColor() { return BuildSession.name.isEmpty() ? YELLOW : GREEN; }

    int next() {
        if (BuildSession.blocks.isEmpty()) return 1;
        if (!BuildSession.ready) return 2;
        if (BuildSession.chests.isEmpty()) return 4;
        if (BuilderBot.found.isEmpty()) return 5;
        return 7;
    }

    void step(int n, boolean done, String desc, Runnable r) {
        String mark = done ? "§a\u2714 §r" : (n == next() ? "§e> §r" : "");
        buttonDesc(mark + n + ". " + NAMES[n], desc, r);
    }

    @Override
    protected void content() {
        text("Next step: " + NAMES[next()], YELLOW);
        gap(4);
        step(1, !BuildSession.blocks.isEmpty(), "Pick the .schem / .litematic file to build.",
                () -> client.setScreen(new SchematicListScreen()));
        step(2, BuildSession.ready, "Move the ghost building to the right place, then press Ready.", () -> {
            if (need()) return;
            client.setScreen(new PlaceScreen());
        });
        step(3, false, "See which blocks are needed. You can swap a block for another one.", () -> {
            if (need()) return;
            client.setScreen(new MaterialsScreen());
        });
        step(4, !BuildSession.chests.isEmpty(),
                "Shows where to put the chests"
                        + (BuildSession.chests.isEmpty() ? "" : " (" + BuildSession.chests.size() + " chests)")
                        + ". Fill them with the blocks.", () -> {
            if (need()) return;
            BuildSession.layoutChests();
            say("Place " + BuildSession.chests.size() + " chests on the marked spots and fill them with the materials.");
            close();
        });
        step(5, false, "The bot looks in the chests and tells you if something is missing.", () -> {
            if (BuildSession.chests.isEmpty()) { say("§cDo step 4 first (put the chests)"); return; }
            BuilderBot.startCheck();
            close();
        });
        step(6, false, "Only tools, food and scaffold blocks may be in your inventory.", () -> {
            String pr = inventoryProblem();
            say(pr == null ? "§aInventory OK" : "§c" + pr);
        });
        step(7, false, "Choose who builds, then the building starts.", () -> {
            if (need()) return;
            if (!BuildSession.ready) { say("§cDo step 2 first (place the building and press Ready)"); return; }
            client.setScreen(new BuildModeScreen(this::inventoryProblem));
        });
    }

    boolean need() {
        if (!BuildSession.blocks.isEmpty()) return false;
        say("§cDo step 1 first (choose a building)");
        return true;
    }

    void say(String s) { BuilderBot.msg(client, s); }

    /** Inventory must contain ONLY axe / pickaxe / shovel, and the tools the blocks need. */
    String inventoryProblem() {
        boolean axe = false, pick = false, shovel = false;
        for (ItemStack s : client.player.getInventory().getMainStacks()) {
            if (s.isEmpty()) continue;
            if (s.isIn(ItemTags.AXES)) axe = true;
            else if (s.isIn(ItemTags.PICKAXES)) pick = true;
            else if (s.isIn(ItemTags.SHOVELS)) shovel = true;
            else if (s.isIn(ItemTags.SWORDS) || s.contains(net.minecraft.component.DataComponentTypes.FOOD)
                    || s.isOf(Items.TOTEM_OF_UNDYING) || s.isOf(Items.MILK_BUCKET) || s.isOf(Items.SHIELD)) { }
            else if (BuilderBot.SCAFFOLD.contains(s.getItem()) || s.isOf(Items.CHEST)) { }
            else return "Empty your inventory (only axe / pickaxe / shovel / sword, food, totem, shield, milk, cobblestone/dirt for scaffolding and chests allowed)";
        }
        StringBuilder miss = new StringBuilder();
        if (BuildSession.needs(BlockTags.PICKAXE_MINEABLE) && !pick) miss.append(" pickaxe");
        if (BuildSession.needs(BlockTags.AXE_MINEABLE) && !axe) miss.append(" axe");
        if (BuildSession.needs(BlockTags.SHOVEL_MINEABLE) && !shovel) miss.append(" shovel");
        return miss.length() == 0 ? null : "Missing tools:" + miss;
    }
}
