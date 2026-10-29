package com.bobsc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Boss-bar style progress bar at the top of the screen. */
public class BossHud {
    public static void render(DrawContext ctx) {
        if (!BuilderBot.hudVisible()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int cx = ctx.getScaledWindowWidth() / 2;
        int x = cx - 91, y = 18, w = 182, h = 5;
        ctx.drawCenteredTextWithShadow(mc.textRenderer, Text.literal(BuilderBot.hudText()), cx, y - 10, 0xFFFFFFFF);
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);     // border
        ctx.fill(x, y, x + w, y + h, 0xFF3A3A3A);                       // empty
        int fill = Math.min(w, (int) (BuilderBot.hudProgress() * w));
        if (fill > 0) ctx.fill(x, y, x + fill, y + h, 0xFF55FF55);      // progress
    }
}
