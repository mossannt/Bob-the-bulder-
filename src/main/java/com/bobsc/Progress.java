package com.bobsc;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Saves the progress of a build and of a dig in <config>/bobsc-progress.json (auto-save every 10 s, on stop, on leaving
 * the world and on quitting the game), so both can be resumed later - also after restarting the game.
 * A build resumes by itself because finished blocks are recognised in the world; a dig because finished cells are air.
 * The dig resource filter is stored in the same file.
 */
public class Progress {
    static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bobsc-progress.json");
    static JsonObject root = new JsonObject();
    static int timer;

    // ------------------------------------------------------------ file
    public static void load() {
        root = new JsonObject();
        try {
            if (Files.exists(FILE)) {
                JsonElement e = JsonParser.parseString(Files.readString(FILE));
                if (e.isJsonObject()) root = e.getAsJsonObject();
            }
        } catch (Exception ex) { ex.printStackTrace(); }
        if (root.has("options")) {
            JsonObject op = root.getAsJsonObject("options");
            if (op.has("safety")) Safety.enabled = op.get("safety").getAsBoolean();
            if (op.has("slow")) BuilderBot.slow = op.get("slow").getAsBoolean();
        }
        Digger.unwanted.clear();
        if (root.has("filter"))
            for (JsonElement e : root.getAsJsonArray("filter")) {
                Identifier id = Identifier.tryParse(e.getAsString());
                if (id == null) continue;
                Item it = Registries.ITEM.get(id);
                if (it != Items.AIR) Digger.unwanted.add(it);
            }
    }

    static void write() {
        try { Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(root)); }
        catch (IOException e) { e.printStackTrace(); }
    }

    static JsonArray arr(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX()); a.add(p.getY()); a.add(p.getZ());
        return a;
    }

    static JsonArray arrs(List<BlockPos> l) {
        JsonArray a = new JsonArray();
        for (BlockPos p : l) a.add(arr(p));
        return a;
    }

    static BlockPos pos(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    /** Last known world / server and dimension (still valid while the game is leaving the world). */
    static String knownWorld = "", knownDim = "", autoKey = "";
    static int idleT;

    /** "sp:<world name>" or "mp:<server address>"; empty when unknown. */
    static String world(MinecraftClient mc) {
        try {
            if (mc.getCurrentServerEntry() != null) return "mp:" + mc.getCurrentServerEntry().address;
            if (mc.getServer() != null) return "sp:" + mc.getServer().getSaveProperties().getLevelName();
        } catch (Exception e) { /* unknown */ }
        return "";
    }

    static void refresh(MinecraftClient mc) {
        if (mc == null || mc.world == null) return;
        knownDim = mc.world.getRegistryKey().getValue().toString();
        String w = world(mc);
        if (!w.isEmpty()) knownWorld = w;
    }

    /** Error text when a saved job belongs to another world / server / dimension, otherwise null. */
    static String mismatch(MinecraftClient mc, JsonObject o) {
        refresh(mc);
        String sw = o.has("world") ? o.get("world").getAsString() : "";
        String sd = o.has("dim") ? o.get("dim").getAsString() : "";
        if (!sw.isEmpty() && !knownWorld.isEmpty() && !sw.equals(knownWorld))
            return "This was saved in another world / server (" + sw.substring(Math.min(3, sw.length())) + ")";
        if (!sd.isEmpty() && !knownDim.isEmpty() && !sd.equals(knownDim))
            return "This was saved in another dimension (" + sd + ")";
        return null;
    }

    public static void saveOptions() {
        JsonObject op = new JsonObject();
        op.addProperty("safety", Safety.enabled);
        op.addProperty("slow", BuilderBot.slow);
        root.add("options", op);
        write();
    }

    /** Temporary pillars / bridges that are still standing: they are removed after the bot continues. */
    static void saveScaffold(JsonObject o) { o.add("scaffold", arrs(Nav.scaffold)); }

    static void loadScaffold(JsonObject o) {
        if (!o.has("scaffold")) return;
        for (JsonElement e : o.getAsJsonArray("scaffold")) {
            BlockPos p = pos(e);
            if (!Nav.scaffold.contains(p)) Nav.scaffold.add(p);
        }
    }

    // ------------------------------------------------------------ filter
    public static void saveFilter() {
        JsonArray a = new JsonArray();
        for (Item i : Digger.unwanted) a.add(Registries.ITEM.getId(i).toString());
        root.add("filter", a);
        write();
    }

    // ------------------------------------------------------------ save
    public static void saveBuild(MinecraftClient mc) {
        if (BuildSession.blocks.isEmpty() || BuildSession.name.isEmpty()) return;
        boolean run = BuilderBot.mode == BuilderBot.Mode.BUILD;
        JsonObject o = new JsonObject();
        o.addProperty("schematic", BuildSession.name);
        o.add("origin", arr(BuildSession.origin));
        o.addProperty("ready", BuildSession.ready);
        o.add("chests", arrs(BuildSession.chests));
        o.add("loot", arrs(Loot.chests));
        saveScaffold(o);
        JsonArray sw = new JsonArray();
        for (String[] s : BuildSession.swapLog) { JsonArray p = new JsonArray(); p.add(s[0]); p.add(s[1]); sw.add(p); }
        o.add("swaps", sw);
        o.addProperty("completed", run ? BuilderBot.completed : 0);
        o.addProperty("total", run ? BuilderBot.total : 0);
        refresh(mc);
        o.addProperty("dim", knownDim);
        o.addProperty("world", knownWorld);
        o.addProperty("time", System.currentTimeMillis());
        if (run) root.addProperty("last", "build");
        root.add("build", o);
        write();
    }

    public static void saveDig(MinecraftClient mc) {
        if (!Digger.ready()) return;
        JsonObject o = new JsonObject();
        o.add("a", arr(Digger.a));
        o.add("b", arr(Digger.b));
        o.addProperty("collect", Digger.collect);
        saveScaffold(o);
        o.addProperty("done", Digger.done + Digger.skippedCount);
        o.addProperty("total", Digger.total);
        refresh(mc);
        o.addProperty("dim", knownDim);
        o.addProperty("world", knownWorld);
        o.addProperty("time", System.currentTimeMillis());
        root.addProperty("last", "dig");
        root.add("dig", o);
        write();
    }

    /** Saves whatever is running (used on quit / disconnect / auto-save). */
    public static void saveActive(MinecraftClient mc) {
        if (BuilderBot.mode == BuilderBot.Mode.BUILD) saveBuild(mc);
        else if (BuilderBot.mode == BuilderBot.Mode.DIG) saveDig(mc);
    }

    public static void tick(MinecraftClient mc) {
        if (++timer % 200 == 0) saveActive(mc);
    }

    public static void clearBuild() { if (root.has("build")) { root.remove("build"); write(); } }
    public static void clearDig() { if (root.has("dig")) { root.remove("dig"); write(); } }

    // ------------------------------------------------------------ info
    public static boolean hasBuild() { return root.has("build"); }
    public static boolean hasDig() { return root.has("dig"); }

    public static String buildInfo() {
        JsonObject o = root.getAsJsonObject("build");
        int t = o.get("total").getAsInt(), c = o.get("completed").getAsInt();
        BlockPos p = pos(o.get("origin"));
        return o.get("schematic").getAsString() + " - " + (t <= 0 ? "not started" : (c * 100 / t) + "% (" + c + "/" + t + ")")
                + "  @ " + p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    public static String digInfo() {
        JsonObject o = root.getAsJsonObject("dig");
        int t = o.get("total").getAsInt(), d = o.get("done").getAsInt();
        BlockPos a = pos(o.get("a")), b = pos(o.get("b"));
        return (t <= 0 ? "not started" : Math.min(100, d * 100 / t) + "% (" + d + "/" + t + ")")
                + "  " + a.toShortString() + " -> " + b.toShortString();
    }

    // ------------------------------------------------------------ resume
    /** Loads the schematic, the block switches, the position and the chests. Returns an error text or null. */
    public static String resumeBuild(MinecraftClient mc) {
        if (!hasBuild()) return "No saved build";
        JsonObject o = root.getAsJsonObject("build");
        if (BuilderBot.busy()) return "Stop the bot first";
        String mm = mismatch(mc, o);
        if (mm != null) return mm;
        String name = o.get("schematic").getAsString();
        Path p = BobscMod.DIR.resolve(name);
        if (!Files.exists(p)) return "Schematic file not found: " + name;
        try {
            BuildSession.load(name, SchematicLoader.load(p));
        } catch (Exception e) { return "Could not load " + name + ": " + e.getMessage(); }
        for (JsonElement e : o.getAsJsonArray("swaps")) {
            JsonArray s = e.getAsJsonArray();
            Identifier fi = Identifier.tryParse(s.get(0).getAsString()), bi = Identifier.tryParse(s.get(1).getAsString());
            if (fi == null || bi == null) continue;
            Item from = Registries.ITEM.get(fi);
            Block to = Registries.BLOCK.get(bi);
            if (from != Items.AIR) BuildSession.swap(from, to);
        }
        BuildSession.origin = pos(o.get("origin"));
        BuildSession.ready = o.get("ready").getAsBoolean();
        for (JsonElement e : o.getAsJsonArray("chests")) BuildSession.chests.add(pos(e));
        for (JsonElement e : o.getAsJsonArray("loot")) Loot.chests.add(pos(e));
        loadScaffold(o);
        return null;
    }

    /** Restores area, mode and starts digging again (finished cells are air, so it continues where it stopped). */
    public static String resumeDig(MinecraftClient mc) {
        if (!hasDig()) return "No saved dig";
        JsonObject o = root.getAsJsonObject("dig");
        String mm = mismatch(mc, o);
        if (mm != null) return mm;
        Digger.a = pos(o.get("a"));
        Digger.b = pos(o.get("b"));
        Digger.collect = o.get("collect").getAsBoolean();
        loadScaffold(o);
        String err = Digger.start(mc);
        if (err != null) return err;
        int t = o.get("total").getAsInt();
        if (t > Digger.toDig) { Digger.total = t; Digger.done = t - Digger.toDig; }
        return null;
    }

    // ------------------------------------------------------------ pause / continue
    /** A paused (stopped) build or dig can be carried on. */
    public static boolean canContinue() {
        if (hasDig()) return true;
        return hasBuild() && root.getAsJsonObject("build").get("total").getAsInt() > 0;
    }

    /** Carries on with the last build / dig. Returns an error text or null. */
    public static String continueBot(MinecraftClient mc) {
        if (mc.world == null) return "No world";
        if (BuilderBot.busy()) return "The bot is already running";
        String last = root.has("last") ? root.get("last").getAsString() : (hasBuild() ? "build" : "dig");
        if (last.equals("dig") && hasDig()) return resumeDig(mc);
        if (!hasBuild()) return hasDig() ? resumeDig(mc) : "Nothing to continue";
        JsonObject o = root.getAsJsonObject("build");
        String err = mismatch(mc, o);
        if (err != null) return err;
        if (BuildSession.blocks.isEmpty() || !BuildSession.name.equals(o.get("schematic").getAsString())) {
            err = resumeBuild(mc);
            if (err != null) return err;
        }
        if (BuildSession.chests.isEmpty()) return "Put the chests first (Build a building -> step 4)";
        BuilderBot.startBuild();
        BuilderBot.msg(mc, "§aContinuing the build");
        return null;
    }

    /** Key J / button: pause the running bot, or continue the paused one. */
    public static void togglePause(MinecraftClient mc) {
        if (BuilderBot.busy()) { BuilderBot.stop(mc, "Paused - press J (or Bob the Builder menu -> Continue) to carry on"); return; }
        String err = continueBot(mc);
        if (err != null) BuilderBot.msg(mc, "§c" + err);
    }

    // ------------------------------------------------------------ automatic restore after restarting the game
    /** Every 2 s: after joining a world / server (or changing dimension) bring back the saved building / dig area. */
    public static void idleTick(MinecraftClient mc) {
        if (++idleT % 40 != 0 || mc.world == null) return;
        refresh(mc);
        if (BuilderBot.busy()) return;
        String key = knownWorld + "|" + knownDim;
        if (key.equals(autoKey)) return;
        autoKey = key;
        if (BuildSession.blocks.isEmpty() && hasBuild() && resumeBuild(mc) == null)
            BuilderBot.msg(mc, "§aBuilding restored: " + BuildSession.name
                    + (canContinue() ? " - press J (or Bob the Builder menu -> Continue) to carry on" : ""));
        if (!Digger.ready() && hasDig()) {
            JsonObject o = root.getAsJsonObject("dig");
            if (mismatch(mc, o) == null) {
                Digger.a = pos(o.get("a"));
                Digger.b = pos(o.get("b"));
                Digger.collect = o.get("collect").getAsBoolean();
                Digger.scan(mc);
                BuilderBot.msg(mc, "§aDig area restored - press J to carry on");
            }
        }
    }
}
