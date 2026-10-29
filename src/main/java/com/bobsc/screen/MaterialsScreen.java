package com.bobsc.screen;

import com.bobsc.BuildSession;
import com.bobsc.BuilderBot;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.Map;

/** Materials the building needs. The small button on the left swaps a block for another one. */
public class MaterialsScreen extends UiScreen {
    Map<Item, Integer> req;

    public MaterialsScreen() { super("Materials"); }

    @Override protected String subtitle() {
        return BuildSession.totalStacks() + " stacks, " + BuildSession.chestCount() + " chests";
    }

    @Override protected Screen backTo() { return new BuildMenuScreen(); }

    @Override
    protected void content() {
        req = BuildSession.required();
        caption(BuilderBot.found.isEmpty()
                ? "The small button on the left swaps a block for another block."
                : "§aGreen§7 = enough in the chests, §cred§7 = missing. The small button swaps a block.");
        for (var e : req.entrySet()) {
            Item it = e.getKey();
            int n = e.getValue(), max = Math.max(1, it.getMaxCount()), stacks = (n + max - 1) / max;
            String line = it.getName().getString() + "  x" + n + "  (" + stacks + " stacks)";
            int col = WHITE;
            if (!BuilderBot.found.isEmpty()) {
                int have = BuilderBot.found.getOrDefault(it, 0);
                line += "  [chests: " + have + "]";
                col = have >= n ? GREEN : RED;
            }
            final String shown = line;
            final int color = col;
            final int y = cy;
            mk("\u21C4", colX, y, 20, "Switch to another block", () -> pick(it));
            paints.add(new Paint(y, 20, (c, sy) -> {
                c.drawItem(new ItemStack(it), colX + 24, sy + 2);
                c.drawTextWithShadow(textRenderer, Text.literal(shown), colX + 44, sy + 6, color);
            }));
            cy += 22;
        }
        if (BuildSession.swapped) {
            gap(6);
            button("Reset all blocks", "Go back to the original building", () -> {
                BuildSession.resetSwaps();
                clearAndInit();
            });
        }
    }

    void pick(Item it) {
        if (BuildSession.building) { BuilderBot.msg(client, "\u00a7cCannot switch blocks while building"); return; }
        client.setScreen(new BlockPickerScreen(this, it));
    }
}
