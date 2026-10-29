package com.bobsc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BobscMod implements ClientModInitializer {
    public static Path DIR;
    public static KeyBinding OPEN_LIST, OPEN_SETTINGS, BOT_BUILD, OPEN_DIGGER, PAUSE_BOT;

    @Override
    public void onInitializeClient() {
        // <game dir>/bobsc  -> put .schem / .litematic files here
        DIR = FabricLoader.getInstance().getGameDir().resolve("bobsc");
        try { Files.createDirectories(DIR); } catch (IOException e) { e.printStackTrace(); }

        Progress.load();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(Progress::saveActive);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (BuilderBot.busy()) BuilderBot.stop(client, null);
        });

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("bobsc", "main"));
        OPEN_LIST = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bobsc.list", GLFW.GLFW_KEY_B, cat));
        OPEN_SETTINGS = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bobsc.settings", GLFW.GLFW_KEY_N, cat));

        OPEN_DIGGER = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bobsc.digger", GLFW.GLFW_KEY_G, cat));
        PAUSE_BOT = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bobsc.pause", GLFW.GLFW_KEY_J, cat));
        BOT_BUILD = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bobsc.botbuild", GLFW.GLFW_KEY_V, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.world == null) return;
            while (OPEN_LIST.wasPressed()) mc.setScreen(new com.bobsc.screen.SchematicListScreen());
            while (OPEN_SETTINGS.wasPressed()) mc.setScreen(new com.bobsc.screen.SettingsScreen());
            while (OPEN_DIGGER.wasPressed()) mc.setScreen(new com.bobsc.screen.DiggerScreen());
            while (PAUSE_BOT.wasPressed()) Progress.togglePause(mc);
            Progress.idleTick(mc);
            BuilderBot.tick(mc);
            CreativeFill.tick(mc);
            while (BOT_BUILD.wasPressed()) BotCharacter.startBuild(mc);
            BotCharacter.tick(mc);
        });

        WorldRenderEvents.AFTER_ENTITIES.register(HologramRenderer::render);
        WorldRenderEvents.AFTER_ENTITIES.register(BotCharacter::render);
        ClientSendMessageEvents.ALLOW_CHAT.register(BotCharacter::chat);   // !build  !stop  !leave  !come
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
                Identifier.of("bobsc", "progress"), (ctx, tickCounter) -> BossHud.render(ctx));
    }
}
