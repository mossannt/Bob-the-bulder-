package com.bobsc.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Base for the BobSC screens: everything added in {@link #build()} scrolls (mouse wheel + scrollbar) when it does not
 * fit on the screen. Widgets passed to {@link #pin} stay where they are. Texts drawn by a subclass use {@link #sy}.
 */
public abstract class ScrollScreen extends Screen {
    protected int scroll = 0;
    protected int headerH = 28, footerH = 0, contentEnd = 0;
    final Map<ClickableWidget, Integer> baseY = new HashMap<>();
    final Set<ClickableWidget> pinned = new HashSet<>();
    final Scrollbar bar = new Scrollbar();
    int maxScroll;

    protected ScrollScreen(Text title) { super(title); }

    /** Add the widgets here (instead of init()). */
    protected abstract void build();

    @Override
    protected final void init() {
        baseY.clear(); pinned.clear();
        headerH = 28; footerH = 0; contentEnd = 0;
        build();
        int bottom = contentEnd;
        for (Element e : children())
            if (e instanceof ClickableWidget w && !pinned.contains(w)) {
                baseY.put(w, w.getY());
                bottom = Math.max(bottom, w.getY() + w.getHeight());
            }
        contentEnd = bottom;
        recalc();
    }

    void recalc() {
        maxScroll = Math.max(0, contentEnd + 6 - (height - footerH));
        scroll = Math.max(0, Math.min(maxScroll, scroll));
    }

    protected <T extends ClickableWidget> T pin(T w) { pinned.add(w); return w; }

    /** Screen y of a content y. */
    protected int sy(int y) { return y - scroll; }

    protected boolean inView(int y, int h) { return y - scroll >= headerH - 2 && y - scroll + h <= height - footerH; }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (vAmount * 20)));
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        recalc();
        for (var e : baseY.entrySet()) {
            ClickableWidget w = e.getKey();
            w.setY(e.getValue() - scroll);
            w.visible = w.getY() >= headerH - 2 && w.getY() + w.getHeight() <= height - footerH;
        }
        super.render(ctx, mx, my, delta);
        if (maxScroll > 0) {
            int top = headerH, bot = height - footerH - 2;
            double v = bar.draw(ctx, client, mx, my, width - 8, top, bot, scroll, maxScroll,
                    (double) (bot - top) / (contentEnd + 6 - headerH));
            scroll = (int) Math.round(v);
        }
    }
}
