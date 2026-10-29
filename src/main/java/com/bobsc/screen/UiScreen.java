package com.bobsc.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Common look of every BobSC menu: dark header with title + one status line, one centred column of big buttons
 * (each can have a short grey explanation under it), gold section headings, and a Back / Close button that is
 * ALWAYS visible at the bottom. Everything in between scrolls (mouse wheel or the bar on the right).
 * Subclasses only implement {@link #content()} with the helpers below.
 */
public abstract class UiScreen extends ScrollScreen {
    interface Painter { void paint(DrawContext ctx, int sy); }
    record Paint(int y, int h, Painter p) { }

    static final int GOLD = 0xFFFFD24A, GRAY = 0xFFAAAAAA, WHITE = 0xFFFFFFFF, GREEN = 0xFF55FF55,
            RED = 0xFFFF5555, YELLOW = 0xFFFFFF55;

    final List<Paint> paints = new ArrayList<>();
    protected int cy, colX, colW;
    final String heading;

    protected UiScreen(String heading) { super(Text.literal(heading)); this.heading = heading; }

    /** Add the content with the helpers below (cursor {@link #cy} moves down by itself). */
    protected abstract void content();

    /** One line under the title (must not be empty). */
    protected String subtitle() { return " "; }
    protected int subtitleColor() { return GRAY; }
    protected boolean hasBack() { return true; }
    protected Screen backTo() { return new SettingsScreen(); }

    @Override
    protected final void build() {
        paints.clear();
        colW = Math.min(240, width - 30);
        colX = width / 2 - colW / 2;
        headerH = 36;
        footerH = 30;
        cy = headerH + 6;
        content();
        contentEnd = cy;
        pin(mk(hasBack() ? "< Back" : "Close", colX, height - 26, colW, null, () -> {
            if (hasBack()) client.setScreen(backTo()); else close();
        }));
    }

    // ------------------------------------------------------------ widgets
    ButtonWidget mk(String label, int x, int y, int w, String tip, Runnable r) {
        ButtonWidget.Builder bb = ButtonWidget.builder(Text.literal(label), bt -> r.run()).dimensions(x, y, w, 20);
        if (tip != null) bb.tooltip(Tooltip.of(Text.literal(tip)));
        ButtonWidget b = bb.build();
        addDrawableChild(b);
        return b;
    }

    /** Full-width button. */
    protected ButtonWidget button(String label, String tip, Runnable r) {
        ButtonWidget b = mk(label, colX, cy, colW, tip, r);
        cy += 24;
        return b;
    }

    /** Two half-width buttons side by side. */
    protected void buttons(String l1, Runnable r1, String l2, Runnable r2) {
        int w = (colW - 4) / 2;
        mk(l1, colX, cy, w, null, r1);
        mk(l2, colX + w + 4, cy, colW - w - 4, null, r2);
        cy += 24;
    }

    /** Full-width button with a short grey explanation under it. */
    protected void buttonDesc(String label, String desc, Runnable r) {
        mk(label, colX, cy, colW, null, r);
        cy += 22;
        for (String l : wrap(desc, colW - 4)) { paintText(l, colX + 2, cy, GRAY); cy += 10; }
        cy += 6;
    }

    /** Button with the picture of an item on its left. */
    protected void iconRow(Item it, String label, String tip, Runnable r) {
        final int y = cy;
        paints.add(new Paint(y, 20, (c, sy) -> c.drawItem(new ItemStack(it), colX + 1, sy + 2)));
        mk(label, colX + 22, y, colW - 22, tip, r);
        cy += 22;
    }

    /** Buttons that stay at the top while the rest scrolls (call first in content()). */
    protected void pinnedRow(String[] labels, Runnable[] acts) {
        int n = labels.length, w = (colW - 4 * (n - 1)) / n;
        for (int i = 0; i < n; i++) pin(mk(labels[i], colX + i * (w + 4), headerH + 2, w, null, acts[i]));
        headerH += 24;
        cy = headerH + 6;
    }

    // ------------------------------------------------------------ text
    protected void section(String t) {
        cy += 4;
        final int y = cy;
        paints.add(new Paint(y, 13, (c, sy) -> {
            c.drawTextWithShadow(textRenderer, Text.literal(t), colX, sy, GOLD);
            c.fill(colX, sy + 11, colX + colW, sy + 12, 0x66FFD24A);
        }));
        cy += 17;
    }

    protected void text(String s, int color) {
        for (String l : wrap(s, colW)) { paintText(l, colX, cy, color); cy += 11; }
    }

    protected void caption(String s) {
        for (String l : wrap(s, colW - 4)) { paintText(l, colX + 2, cy, GRAY); cy += 10; }
        cy += 4;
    }

    protected void gap(int n) { cy += n; }

    void paintText(String s, int x, int y, int color) {
        paints.add(new Paint(y, 10, (c, sy) -> c.drawTextWithShadow(textRenderer, Text.literal(s), x, sy, color)));
    }

    List<String> wrap(String s, int px) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = cur.length() == 0 ? word : cur + " " + word;
            if (textRenderer.getWidth(t) <= px) { cur = new StringBuilder(t); continue; }
            if (cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(); }
            String w2 = word;
            while (textRenderer.getWidth(w2) > px && w2.length() > 1) {      // a single very long word (a path): cut it
                int k = w2.length();
                while (k > 1 && textRenderer.getWidth(w2.substring(0, k)) > px) k--;
                out.add(w2.substring(0, k));
                w2 = w2.substring(k);
            }
            cur = new StringBuilder(w2);
        }
        if (cur.length() > 0 || out.isEmpty()) out.add(cur.toString());
        return out;
    }

    // ------------------------------------------------------------ drawing
    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        ctx.fill(colX - 10, headerH - 2, colX + colW + 10, height - footerH, 0x66000000);   // content panel
        ctx.fill(0, 0, width, headerH - 2, 0xCC000000);                                       // header
        ctx.fill(0, headerH - 3, width, headerH - 2, 0x88FFD24A);
        ctx.fill(0, height - footerH, width, height, 0xCC000000);                             // footer
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        for (Paint p : paints) if (inView(p.y(), p.h())) p.p().paint(ctx, sy(p.y()));
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§l" + heading), width / 2, 8, WHITE);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(subtitle()), width / 2, 21, subtitleColor());
    }
}
