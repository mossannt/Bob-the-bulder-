package com.bobsc.screen;

import com.bobsc.BotCharacter;
import com.bobsc.BuilderBot;
import com.bobsc.CreativeFill;
import com.bobsc.Progress;

/** Main menu (key N): what the bot is doing + one big button per job. */
public class SettingsScreen extends UiScreen {
    public SettingsScreen() { super("Bob the Builder"); }

    @Override protected boolean hasBack() { return false; }

    @Override protected String subtitle() { return "Bot: " + BuilderBot.status(); }

    @Override protected int subtitleColor() { return BuilderBot.busy() ? GREEN : YELLOW; }

    @Override
    protected void content() {
        if (BuilderBot.busy()) {
            section("The bot is working");
            buttons("§ePause  (J)", () -> {
                BuilderBot.stop(client, "Paused - press J (or Bob the Builder menu -> Continue) to carry on");
                close();
            }, "§cStop", () -> {
                BuilderBot.stop(client, "Stopped");
                CreativeFill.stop(client, null);
                BotCharacter.stopBuild(client);
                close();
            });
        } else if (Progress.canContinue()) {
            section("The bot is paused");
            button("§a> Continue  (J)", "Carry on where the bot stopped", () -> {
                String err = Progress.continueBot(client);
                if (err != null) BuilderBot.msg(client, "§c" + err); else close();
            });
        }

        section("What do you want to do?");
        buttonDesc("Build a building", "Pick a schematic file and let the bot build it for you.",
                () -> client.setScreen(new BuildMenuScreen()));
        buttonDesc("Dig an area", "Choose two corners. The bot digs everything between them.",
                () -> client.setScreen(new DiggerScreen()));
        buttonDesc("Helper bot", "A second character that joins your world and builds.",
                () -> client.setScreen(new BotScreen()));
        buttonDesc("Saved jobs", "Carry on an unfinished build or dig, even after restarting the game.",
                () -> client.setScreen(new ProgressScreen()));
        buttonDesc("Settings", "Ghost look, safety, slow mode.",
                () -> client.setScreen(new OptionsScreen()));

        section("Keys");
        text("B  pick a building", GRAY);
        text("N  this menu", GRAY);
        text("G  dig an area", GRAY);
        text("J  pause / continue the bot", GRAY);
        text("V  start the helper bot", GRAY);
    }
}
