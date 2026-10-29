package com.bobsc.screen;

import com.bobsc.BotCharacter;
import com.bobsc.BuildSession;
import com.bobsc.BuilderBot;
import net.minecraft.client.gui.screen.Screen;

import java.util.function.Supplier;

/** Shown by "START building": choose who builds. */
public class BuildModeScreen extends UiScreen {
    final Supplier<String> inventoryProblem;

    public BuildModeScreen(Supplier<String> inventoryProblem) {
        super("Who builds?");
        this.inventoryProblem = inventoryProblem;
    }

    @Override protected String subtitle() { return "Choose one"; }

    @Override protected Screen backTo() { return new BuildMenuScreen(); }

    @Override
    protected void content() {
        section("Pick a builder");
        buttonDesc("§aMy character builds", "Your own character builds it. Do not touch the keyboard while it works.", () -> {
            if (BuildSession.chests.isEmpty()) { BuilderBot.msg(client, "§cDo step 4 first (put the chests)"); close(); return; }
            String pr = inventoryProblem.get();
            if (pr != null) { BuilderBot.msg(client, "§c" + pr); close(); return; }
            BuilderBot.startBuild();
            close();
        });
        buttonDesc("Helper bot builds", "A second character joins your world and builds. Your character stays free (singleplayer).", () -> {
            BotCharacter.summon(client);
            close();
        });
        buttonDesc("I build by hand", "No bot. Follow the ghost: blue = missing block, red = wrong block.", () -> {
            BuilderBot.msg(client, "Manual mode: follow the hologram (blue = missing, red = wrong)");
            close();
        });
    }
}
