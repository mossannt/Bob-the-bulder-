package com.bobsc.screen;

import com.bobsc.BotCharacter;

/** Helper bot control panel. */
public class BotScreen extends UiScreen {
    public BotScreen() { super("Helper bot"); }

    @Override protected String subtitle() { return "Status: " + BotCharacter.status(); }

    @Override
    protected void content() {
        section("1. Bring it");
        buttonDesc("Spawn / Respawn next to me", "Makes the helper appear next to you.", () -> BotCharacter.respawn(client));
        section("2. Move it");
        buttons("Come to me", () -> BotCharacter.comeToMe(client),
                "Go to the building", () -> BotCharacter.goToCorner(client));
        section("3. Let it work");
        buttons("§aBuild  (V)", () -> { BotCharacter.startBuild(client); close(); },
                "§cStop", () -> BotCharacter.stopBuild(client));
        section("Extra");
        button("Speed: " + BotCharacter.speedName() + "  (click to change)", "Slow / Normal / Fast / Turbo", () -> {
            BotCharacter.cycleSpeed();
            clearAndInit();
        });
        button("Send the helper away", "The helper leaves the world.", () -> BotCharacter.leave(client));
    }
}
