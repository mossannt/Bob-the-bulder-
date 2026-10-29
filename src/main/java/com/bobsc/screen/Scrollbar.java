package com.bobsc.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

/** Small draggable scrollbar. Reads the mouse button itself, so it works in every screen without click overrides. */
public class Scrollbar {
    boolean dragging, wasDown;

    /**
     * Draws the bar and returns the new value (0..max). {@code ratio} = visible part / whole content (thumb size).
     * Nothing is drawn when there is nothing to scroll.
     */
    public double draw(DrawContext ctx, MinecraftClient mc, int mx, int my, int x, int top, int bottom,
                       double value, double max, double ratio) {
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (max <= 0 || bottom - top < 20) { dragging = false; wasDown = down; return 0; }
        int h = bottom - top;
        int th = Math.max(16, Math.min(h, (int) (h * Math.max(0.05, Math.min(1.0, ratio)))));
        int travel = Math.max(1, h - th);

        if (!down) dragging = false;
        else if (!wasDown && mx >= x - 3 && mx <= x + 9 && my >= top && my <= bottom) dragging = true;
        wasDown = down;

        if (dragging) {
            double f = (my - top - th / 2.0) / travel;
            value = Math.max(0, Math.min(1, f)) * max;
        }
        value = Math.max(0, Math.min(max, value));
        int ty = top + (int) (travel * (value / max));
        ctx.fill(x, top, x + 6, bottom, 0x90000000);
        ctx.fill(x + 1, ty, x + 5, ty + th, dragging ? 0xFFFFFFFF : 0xFFAAAAAA);
        return value;
    }
}
