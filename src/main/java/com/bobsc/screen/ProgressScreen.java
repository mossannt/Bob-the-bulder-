package com.bobsc.screen;

import com.bobsc.BuilderBot;
import com.bobsc.Progress;

/** Saved progress of the last build and of the last dig: resume or delete. */
public class ProgressScreen extends UiScreen {
    public ProgressScreen() { super("Saved jobs"); }

    @Override protected String subtitle() { return "Saved automatically - also after restarting the game"; }

    @Override
    protected void content() {
        section("Last build");
        if (Progress.hasBuild()) {
            text(Progress.buildInfo(), WHITE);
            gap(2);
            buttons("§aResume build", () -> {
                String err = Progress.resumeBuild(client);
                if (err != null) { BuilderBot.msg(client, "§c" + err); return; }
                BuilderBot.msg(client, "Build loaded. Press Continue (J): finished blocks are skipped.");
                client.setScreen(new SettingsScreen());
            }, "§cDelete", () -> { Progress.clearBuild(); clearAndInit(); });
        } else {
            text("Nothing saved.", GRAY);
        }
        section("Last dig");
        if (Progress.hasDig()) {
            text(Progress.digInfo(), WHITE);
            gap(2);
            buttons("§aResume dig", () -> {
                String err = Progress.resumeDig(client);
                if (err != null) BuilderBot.msg(client, "§c" + err); else close();
            }, "§cDelete", () -> { Progress.clearDig(); clearAndInit(); });
        } else {
            text("Nothing saved.", GRAY);
        }
    }
}
