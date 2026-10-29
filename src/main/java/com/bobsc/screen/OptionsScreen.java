package com.bobsc.screen;

import com.bobsc.BuilderBot;
import com.bobsc.CreativeFill;
import com.bobsc.HologramRenderer;
import com.bobsc.Progress;
import com.bobsc.Safety;

/** Settings: ghost look, safety, slow mode, creative fill. */
public class OptionsScreen extends UiScreen {
    public OptionsScreen() { super("Settings"); }

    static String modeName() {
        return switch (HologramRenderer.mode) { case 0 -> "Ghost"; case 1 -> "Outline"; default -> "Ghost + Outline"; };
    }

    static String onOff(boolean b) { return b ? "§aON" : "§cOFF"; }

    @Override protected String subtitle() { return "Click a button to change it"; }

    @Override
    protected void content() {
        section("Look");
        buttonDesc("Ghost building: " + modeName(), "How the building is shown before it is built. If the game lags, use Outline.", () -> {
            HologramRenderer.mode = (HologramRenderer.mode + 1) % 3;
            clearAndInit();
        });
        section("Bot");
        buttonDesc("Safety: " + onOff(Safety.enabled), "The bot eats, fights monsters and pauses when it is in danger.", () -> {
            Safety.enabled = !Safety.enabled;
            Progress.saveOptions();
            clearAndInit();
        });
        buttonDesc("Slow mode: " + onOff(BuilderBot.slow), "Longer pauses between blocks. Can help on servers.", () -> {
            BuilderBot.slow = !BuilderBot.slow;
            Progress.saveOptions();
            clearAndInit();
        });
        section("Creative mode");
        addDrawableChild(CreativeFill.button(client, colX, cy, colW));
        cy += 22;
        caption("Fills the chests with the needed blocks. Only works in creative mode.");
    }
}
