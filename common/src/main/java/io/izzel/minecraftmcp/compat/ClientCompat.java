package io.izzel.minecraftmcp.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.function.Consumer;

//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
//?}

/**
 * Client-side counterpart of {@link MinecraftCompat}: the screen input, screenshot, world and container calls
 * whose shape changed between the supported versions. Only client code paths may call it.
 */
public final class ClientCompat {
    private ClientCompat() {}

    // --- the open screen (26.2 moved it from Minecraft to Gui)

    public static Screen screen(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.screen();
        *///?} else {
        return mc.screen;
        //?}
    }

    /** True while an overlay (the resource loading screen) covers the game and input does not reach the screen. */
    public static boolean loading(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.overlay() != null;
        *///?} else {
        return mc.getOverlay() != null;
        //?}
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        //? if >=26.2 {
        /*mc.gui.setScreen(screen);
        *///?} else {
        mc.setScreen(screen);
        //?}
    }

    // --- screen input (1.21.9 wrapped key, character and mouse input in event records)

    public static boolean keyPressed(Screen screen, int key) {
        //? if >=1.21.9 {
        return screen.keyPressed(new KeyEvent(key, 0, 0));
        //?} else {
        /*return screen.keyPressed(key, 0, 0);
        *///?}
    }

    public static boolean charTyped(Screen screen, int codePoint) {
        //? if >=26.1 {
        /*return screen.charTyped(new CharacterEvent(codePoint));
        *///?} else if >=1.21.9 {
        return screen.charTyped(new CharacterEvent(codePoint, 0));
        //?} else {
        /*return screen.charTyped((char) codePoint, 0);
        *///?}
    }

    public static boolean mouseClicked(Screen screen, double x, double y, int button) {
        //? if >=1.21.9 {
        return screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
        //?} else {
        /*return screen.mouseClicked(x, y, button);
        *///?}
    }

    public static boolean mouseReleased(Screen screen, double x, double y, int button) {
        //? if >=1.21.9 {
        return screen.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)));
        //?} else {
        /*return screen.mouseReleased(x, y, button);
        *///?}
    }

    /** The real mouse cursor in GUI (scaled) coordinates. */
    public static double[] cursor(Minecraft mc) {
        var window = mc.getWindow();
        double scaleX = (double) window.getGuiScaledWidth() / Math.max(1, window.getScreenWidth());
        double scaleY = (double) window.getGuiScaledHeight() / Math.max(1, window.getScreenHeight());
        return new double[] {mc.mouseHandler.xpos() * scaleX, mc.mouseHandler.ypos() * scaleY};
    }

    /**
     * Reads the main framebuffer; call on the client thread. From 1.21.5 the read-back is asynchronous and
     * {@code done} runs on a later frame, so never block the client thread waiting for it.
     */
    public static void takeScreenshot(Minecraft mc, Consumer<NativeImage> done) {
        //? if >=26.2 {
        /*net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), done);
        *///?} else if >=1.21.5 {
        net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), done);
        //?} else {
        /*done.accept(net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget()));
        *///?}
    }

    /** RGBA bytes of a screenshot image, top row first (1.21.8 exposed the native pointer; 1.21.2 renamed the raw ints). */
    public static byte[] rgba(NativeImage image) {
        int size = image.getWidth() * image.getHeight() * 4;
        byte[] bytes = new byte[size];
        //? if >=1.21.8 {
        org.lwjgl.system.MemoryUtil.memByteBuffer(image.getPointer(), size).get(bytes);
        //?} else if >=1.21.2 {
        /*java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(image.getPixelsABGR());
        *///?} else {
        /*java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(image.getPixelsRGBA());
        *///?}
        return bytes;
    }

    // --- sound (1.21.11 renamed ResourceLocation to Identifier)

    /** The sound event id, e.g. minecraft:entity.pig.ambient. */
    public static String soundId(net.minecraft.client.resources.sounds.SoundInstance sound) {
        //? if >=1.21.11 {
        return sound.getIdentifier().toString();
        //?} else {
        /*return sound.getLocation().toString();
        *///?}
    }

    /** The background frame rate cap (1.21.2+), or null where the option does not exist. */
    public static net.minecraft.client.OptionInstance<?> inactivityFpsLimit(Minecraft mc) {
        //? if >=1.21.2 {
        return mc.options.inactivityFpsLimit();
        //?} else {
        /*return null;
        *///?}
    }

    // --- player actions (26.3 made the swing animation explicit and moved dropping to the game mode)

    public static void swing(Minecraft mc, net.minecraft.world.InteractionHand hand) {
        //? if >=26.3 {
        /*mc.player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        *///?} else {
        mc.player.swing(hand);
        //?}
    }

    /** Right-clicks an entity the way vanilla's use key does (26.1 merged interactAt into interact). */
    public static net.minecraft.world.InteractionResult interactEntity(Minecraft mc, net.minecraft.world.entity.Entity entity,
                                                                       net.minecraft.world.phys.EntityHitResult hit,
                                                                       net.minecraft.world.InteractionHand hand) {
        //? if >=26.1 {
        /*return mc.gameMode.interact(mc.player, entity, hit, hand);
        *///?} else {
        var result = mc.gameMode.interactAt(mc.player, entity, hit, hand);
        return result.consumesAction() ? result : mc.gameMode.interact(mc.player, entity, hand);
        //?}
    }

    /** Drops the selected stack, or one item of it; false when the hand was empty. */
    public static boolean dropSelected(Minecraft mc, boolean all) {
        //? if >=26.3 {
        /*boolean holding = !mc.player.getMainHandItem().isEmpty();
        mc.gameMode.dropItem(mc.player, all);
        return holding;
        *///?} else {
        return mc.player.drop(all);
        //?}
    }

    // --- leaving a world or a server

    /** Drops the current connection before connecting somewhere else. */
    public static void disconnect(Minecraft mc) {
        //? if >=1.21.5 {
        if (mc.level != null) mc.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
        mc.disconnectWithProgressScreen();
        //?} else {
        /*if (mc.level != null) mc.level.disconnect();
        mc.disconnect();
        *///?}
    }

    /**
     * Leaves the current world to the title screen in the pause menu's order: closing the level connection
     * first is what stops an integrated server; without it disconnect() waits for a server that never stops.
     */
    public static void leaveWorld(Minecraft mc) {
        //? if >=1.21.5 {
        mc.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
        mc.disconnectWithSavingScreen();
        //?} else {
        /*mc.level.disconnect();
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        *///?}
        setScreen(mc, new TitleScreen());
    }

    // --- worlds

    /** Creates and joins a creative, peaceful, cheats-enabled singleplayer world. */
    public static void createTestWorld(Minecraft mc, String levelName, long seed, boolean flat) {
        //? if >=26.1 {
        /*LevelSettings settings = new LevelSettings(levelName, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        *///?} else if >=1.21.11 {
        LevelSettings settings = new LevelSettings(levelName, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new net.minecraft.world.level.gamerules.GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
        //?} else if >=1.21.2 {
        /*LevelSettings settings = new LevelSettings(levelName, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new net.minecraft.world.level.GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
        *///?} else {
        /*LevelSettings settings = new LevelSettings(levelName, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new net.minecraft.world.level.GameRules(), WorldDataConfiguration.DEFAULT);
        *///?}
        Screen parent = screen(mc) == null ? new GenericMessageScreen(Component.literal("Minecraft MCP")) : screen(mc);
        //? if >=1.21.2 {
        mc.createWorldOpenFlows().createFreshLevel(levelName, settings, new WorldOptions(seed, false, false), registries -> flat
                ? registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
                : WorldPresets.createNormalWorldDimensions(registries), parent);
        //?} else {
        /*mc.createWorldOpenFlows().createFreshLevel(levelName, settings, new WorldOptions(seed, false, false), registries -> flat
                ? registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
                : WorldPresets.createNormalWorldDimensions(registries), parent);
        *///?}
    }

    // --- containers

    /** A slot click through the normal client path; {@code clickType} is the enum constant name (PICKUP, ...). */
    public static void clickSlot(Minecraft mc, int containerId, int slot, int button, String clickType) {
        //? if >=26.1 {
        /*mc.gameMode.handleContainerInput(containerId, slot, button,
                net.minecraft.world.inventory.ContainerInput.valueOf(clickType), mc.player);
        *///?} else {
        mc.gameMode.handleInventoryMouseClick(containerId, slot, button,
                net.minecraft.world.inventory.ClickType.valueOf(clickType), mc.player);
        //?}
    }

    // --- window

    /** Shows or hides the game window; a hidden one keeps rendering, so screenshots still work. */
    // 26.3 moved the window from GLFW to SDL3.
    public static void setWindowVisible(Minecraft mc, boolean visible) {
        //? if >=26.3 {
        /*if (visible) org.lwjgl.sdl.SDLVideo.SDL_ShowWindow(windowHandle(mc));
        else org.lwjgl.sdl.SDLVideo.SDL_HideWindow(windowHandle(mc));
        *///?} else {
        if (visible) org.lwjgl.glfw.GLFW.glfwShowWindow(windowHandle(mc));
        else org.lwjgl.glfw.GLFW.glfwHideWindow(windowHandle(mc));
        //?}
    }

    public static boolean windowVisible(Minecraft mc) {
        //? if >=26.3 {
        /*return (org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(windowHandle(mc)) & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN) == 0;
        *///?} else {
        return org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(windowHandle(mc), org.lwjgl.glfw.GLFW.GLFW_VISIBLE) != 0;
        //?}
    }

    private static long windowHandle(Minecraft mc) {
        //? if >=1.21.9 {
        return mc.getWindow().handle();
        //?} else {
        /*return mc.getWindow().getWindow();
        *///?}
    }
}
