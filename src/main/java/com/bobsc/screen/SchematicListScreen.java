package com.bobsc.screen;

import com.bobsc.BobscMod;
import com.bobsc.BuildSession;
import com.bobsc.SchematicLoader;
import net.minecraft.client.gui.screen.Screen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Pick the schematic file to build. */
public class SchematicListScreen extends UiScreen {
    List<Path> files = new ArrayList<>();
    String listError = "", pickError = "";

    public SchematicListScreen() { super("Choose a building"); }

    @Override protected String subtitle() { return "Click a file to load it"; }

    @Override protected Screen backTo() { return new BuildMenuScreen(); }

    @Override
    protected void content() {
        listError = "";
        try { files = SchematicLoader.list(); } catch (Exception e) { listError = String.valueOf(e.getMessage()); files = new ArrayList<>(); }
        if (!pickError.isEmpty()) { text(pickError, RED); gap(4); }
        if (!listError.isEmpty()) { text("§c" + listError, RED); gap(4); }
        if (files.isEmpty()) {
            section("No building files yet");
            text("Put .schem or .litematic files in this folder, then press Reload:", GRAY);
            text(BobscMod.DIR.toString(), YELLOW);
            gap(6);
            button("Reload the list", null, this::clearAndInit);
            return;
        }
        section(files.size() + (files.size() == 1 ? " building" : " buildings"));
        for (Path p : files) button(p.getFileName().toString(), null, () -> pick(p));
    }

    void pick(Path p) {
        try {
            var m = SchematicLoader.load(p);
            BuildSession.load(p.getFileName().toString(), m);
            if (m.size() > 200_000)
                com.bobsc.BuilderBot.msg(client, "§eLarge schematic (" + m.size() + " blocks): the hologram can lag - use Settings -> Ghost building: Outline");
            BuildSession.origin = client.player.getBlockPos().offset(client.player.getHorizontalFacing(), 2);
            pickError = "";
            client.setScreen(new PlaceScreen());
        } catch (Exception e) {
            pickError = "§cFailed to load: " + e.getMessage();
            clearAndInit();
        }
    }
}
