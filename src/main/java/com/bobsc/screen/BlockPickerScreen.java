package com.bobsc.screen;

import com.bobsc.BuildSession;
import net.minecraft.block.Block;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** All blocks of the game + search field. Picking one replaces the material {@code from} in the schematic. */
public class BlockPickerScreen extends Screen {
    static List<Block> ALL;

    final Screen parent;
    final Item from;
    String query = "";
    int page = 0;
    TextFieldWidget search;
    final List<Block> shown = new ArrayList<>();
    final List<Block> pageBlocks = new ArrayList<>();
    int cols, rows, gx, gy, pages = 1;
    final Scrollbar bar = new Scrollbar();
    boolean redo;

    public BlockPickerScreen(Screen parent, Item from) {
        super(Text.literal("Choose a block"));
        this.parent = parent;
        this.from = from;
    }

    static List<Block> all() {
        if (ALL == null) {
            List<Block> l = new ArrayList<>();
            for (Block b : Registries.BLOCK) {
                if (b.asItem() == Items.AIR || b instanceof FluidBlock) continue;
                if (!(b.asItem() instanceof BlockItem bi) || bi.getBlock() != b) continue;   // skips wall variants (wall torch, wall sign...)
                l.add(b);
            }
            l.sort(Comparator.comparing((Block b) -> b.getName().getString()));
            ALL = l;
        }
        return ALL;
    }

    @Override
    protected void init() {
        int fw = Math.min(260, width - 40);
        search = new TextFieldWidget(textRenderer, width / 2 - fw / 2, 30, fw, 20, Text.literal("Search"));
        search.setPlaceholder(Text.literal("Search blocks..."));
        search.setMaxLength(64);
        search.setText(query);
        search.setChangedListener(s -> { query = s; page = 0; clearAndInit(); });
        addDrawableChild(search);
        setInitialFocus(search);

        String q = query.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Block b : all()) {
            if (q.isEmpty()
                    || b.getName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || Registries.BLOCK.getId(b).getPath().contains(q.replace(' ', '_')))
                shown.add(b);
        }

        cols = Math.max(1, Math.min(16, (width - 30) / 22));
        gy = 58;
        rows = Math.max(1, (height - gy - 42) / 22);
        int per = cols * rows;
        pages = Math.max(1, (shown.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        gx = width / 2 - cols * 22 / 2;

        pageBlocks.clear();
        for (int i = page * per; i < Math.min(shown.size(), (page + 1) * per); i++) {
            Block b = shown.get(i);
            int k = i - page * per;
            pageBlocks.add(b);
            addDrawableChild(ButtonWidget.builder(Text.empty(), bt -> choose(b))
                    .dimensions(gx + (k % cols) * 22, gy + (k / cols) * 22, 20, 20)
                    .tooltip(Tooltip.of(Text.literal(b.getName().getString())))
                    .build());
        }

        int ny = height - 28, x = width / 2 - 110;
        addDrawableChild(ButtonWidget.builder(Text.literal("< Prev"), bt -> { if (page > 0) { page--; clearAndInit(); } })
                .dimensions(x, ny, 64, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), bt -> close())
                .dimensions(x + 68, ny, 84, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Next >"), bt -> { if (page < pages - 1) { page++; clearAndInit(); } })
                .dimensions(x + 156, ny, 64, 20).build());
    }

    void choose(Block b) {
        if (b.asItem() != from) BuildSession.swap(from, b);
        client.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        int np = Math.max(0, Math.min(pages - 1, page - (int) Math.signum(vAmount)));
        if (np != page) { page = np; clearAndInit(); }
        return true;
    }

    @Override
    public void tick() { if (redo) { redo = false; clearAndInit(); } }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        for (int k = 0; k < pageBlocks.size(); k++)
            ctx.drawItem(new ItemStack(pageBlocks.get(k).asItem()), gx + (k % cols) * 22 + 2, gy + (k / cols) * 22 + 2);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§lPick the NEW block for: " + from.getName().getString()), width / 2, 8, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Click a block. " + shown.size() + " found  -  page " + (page + 1) + "/" + pages), width / 2, 20, 0xFFAAAAAA);
        if (pages > 1) {
            int np = (int) Math.round(bar.draw(ctx, client, mx, my, width - 8, gy, height - 34, page, pages - 1, 1.0 / pages));
            if (np != page) { page = np; redo = true; }
        }
        if (shown.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("No block found - try another word"), width / 2, gy + 10, 0xFFFF5555);
    }
}
