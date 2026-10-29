package com.bobsc.screen;

import com.bobsc.Digger;
import com.bobsc.Progress;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Digger resource filter: Keep = collected (counted for the chests), Throw = not picked up and thrown away. */
public class DigFilterScreen extends UiScreen {
    public DigFilterScreen() { super("What to keep?"); }

    @Override protected String subtitle() { return "Keep = collect it    Throw = leave it behind"; }

    @Override protected Screen backTo() { return new DiggerScreen(); }

    @Override
    protected void content() {
        pinnedRow(new String[] { "Keep all", "Only ores", "Throw blocks" },
                new Runnable[] { () -> { Digger.keepAll(); changed(); },
                                 () -> { Digger.oresOnly(); changed(); },
                                 () -> { Digger.throwCommon(); changed(); } });
        List<Map.Entry<Item, Integer>> rows = new ArrayList<>(Digger.drops.entrySet());
        rows.sort((p, q) -> q.getValue() - p.getValue());
        if (rows.isEmpty()) {
            text("Set corner A and B in the Digger first. The items are listed here.", YELLOW);
            return;
        }
        for (var e : rows) {
            Item it = e.getKey();
            boolean thrown = Digger.unwanted.contains(it);
            iconRow(it, (thrown ? "§cThrow§r  " : "§aKeep§r   ") + it.getName().getString() + "  x" + e.getValue(),
                    "Click to switch between Keep and Throw", () -> {
                        if (!Digger.unwanted.remove(it)) Digger.unwanted.add(it);
                        changed();
                    });
        }
    }

    void changed() {
        Progress.saveFilter();
        clearAndInit();
    }
}
