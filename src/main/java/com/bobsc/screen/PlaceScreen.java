package com.bobsc.screen;

import com.bobsc.BuildSession;
import com.bobsc.CreativeFill;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Move the ghost building (relative to where you look) with the buttons, then press Ready. */
public class PlaceScreen extends Screen {
    public PlaceScreen() { super(Text.literal("Place the building")); }

    @Override public boolean shouldPause() { return false; }

    int padY() { return height - 110; }

    // no dark/blur background, so the ghost building stays visible - only a small panel behind the buttons
    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        int cx = width / 2, y = padY();
        ctx.fill(cx - 135, y - 42, cx + 135, y + 74, 0xA0000000);
        ctx.fill(0, 0, width, 24, 0x90000000);
    }

    void move(Direction d) { BuildSession.origin = BuildSession.origin.offset(d); }
    Direction f() { return client.player.getHorizontalFacing(); }

    @Override
    protected void init() {
        int cx = width / 2, y = padY();
        btn("Cancel", 6, 4, 60, this::close);
        addDrawableChild(CreativeFill.button(client, width - 124, 4, 118));
        btn("Forward", cx - 40, y, 80, () -> move(f()));
        btn("Left", cx - 125, y + 24, 80, () -> move(f().rotateYCounterclockwise()));
        btn("Back", cx - 40, y + 24, 80, () -> move(f().getOpposite()));
        btn("Right", cx + 45, y + 24, 80, () -> move(f().rotateYClockwise()));
        btn("Up", cx - 125, y, 80, () -> move(Direction.UP));
        btn("Down", cx + 45, y, 80, () -> move(Direction.DOWN));
        btn("Move to me", cx - 125, y + 48, 120, () -> BuildSession.origin = client.player.getBlockPos());
        addDrawableChild(ButtonWidget.builder(Text.literal("§a\u2714 READY"), b -> {
            BuildSession.ready = true;
            com.bobsc.Progress.saveBuild(client);
            close();
        }).dimensions(cx + 5, y + 48, 120, 20).build());
    }

    void btn(String t, int x, int y, int w, Runnable r) {
        addDrawableChild(ButtonWidget.builder(Text.literal(t), b -> r.run()).dimensions(x, y, w, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        BlockPos o = BuildSession.origin;
        int cx = width / 2, y = padY();
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(BuildSession.name + "  @ " + o.getX() + ", " + o.getY() + ", " + o.getZ()),
                cx, 8, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§lMove the ghost building"), cx, y - 36, 0xFFFFD24A);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Use the buttons, then press READY"), cx, y - 24, 0xFFAAAAAA);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("(Esc = cancel)"), cx, y - 13, 0xFF777777);
    }
}
