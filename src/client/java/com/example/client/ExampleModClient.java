package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class ExampleModClient implements ClientModInitializer {
    public static final List<Module> MODULES = List.of(
        new StorageFinder(),
        new SpawnerFinder()
    );

    private static KeyMapping clickGuiKey;
    private boolean f4WasDown = false;

    @Override
    public void onInitializeClient() {
        // 1. Register Keybind properly for 1.21.11 Controls menu
        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("example", "general")
        );
        clickGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "ClickGUI",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F4,
            category
        ));

        // 2. Open ClickGUI via Keybind (or F4 fallback)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (clickGuiKey.consumeClick()) {
                client.setScreen(new ClickGuiScreen(MODULES));
            }
            if (client.getWindow() != null) {
                boolean f4Down = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_F4);
                if (f4Down && !f4WasDown && client.screen == null) {
                    client.setScreen(new ClickGuiScreen(MODULES));
                }
                f4WasDown = f4Down;
            }
        });

        // 3. Backup Chat Command: /clickgui
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("clickgui")
                .executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new ClickGuiScreen(MODULES)));
                    return 1;
                }));
        });

        // 4. Through-Wall ESP Overlay Renderer (1.21.11 compatible)
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || mc.options.hideGui) return;

            for (Module mod : MODULES) {
                if (mod.isEnabled()) {
                    mod.onRenderOverlay(context);
                }
            }
        });
    }

    // ==========================================
    // SETTINGS SYSTEM
    // ==========================================
    public static abstract class Setting<T> {
        private final String name;
        protected T value;
        public Setting(String name, T defaultValue) { this.name = name; this.value = defaultValue; }
        public String getName() { return name; }
        public T getValue() { return value; }
    }

    public static class BooleanSetting extends Setting<Boolean> {
        public BooleanSetting(String name, boolean defaultValue) { super(name, defaultValue); }
        public void toggle() { this.value = !this.value; }
    }

    public static class ModeSetting extends Setting<String> {
        private final List<String> modes;
        private int index;
        public ModeSetting(String name, String defaultMode, String... modes) {
            super(name, defaultMode);
            this.modes = List.of(modes);
            this.index = this.modes.indexOf(defaultMode);
        }
        public void cycle() {
            index = (index + 1) % modes.size();
            this.value = modes.get(index);
        }
    }

    public static class NumberSetting extends Setting<Double> {
        private final double min, max, step;
        public NumberSetting(String name, double defaultValue, double min, double max, double step) {
            super(name, defaultValue);
            this.min = min; this.max = max; this.step = step;
        }
        public void setClampedValue(double newVal) {
            double stepped = Math.round(newVal / step) * step;
            this.value = Math.max(min, Math.min(max, stepped));
        }
        public double getMin() { return min; }
        public double getMax() { return max; }
    }

    // ==========================================
    // MODULES
    // ==========================================
    public static abstract class Module {
        private final String name;
        private boolean enabled = false;
        private boolean expanded = false;
        protected final List<Setting<?>> settings = new ArrayList<>();

        public Module(String name) { this.name = name; }
        public void toggle() { this.enabled = !this.enabled; }
        public boolean isEnabled() { return enabled; }
        public boolean isExpanded() { return expanded; }
        public void setExpanded(boolean expanded) { this.expanded = expanded; }
        public String getName() { return name; }
        public List<Setting<?>> getSettings() { return settings; }
        public abstract void onRenderOverlay(GuiGraphics context);
    }

    public static class StorageFinder extends Module {
        public final ModeSetting mode = new ModeSetting("Mode", "Both", "Outline", "Fill", "Both");
        public final BooleanSetting chests = new BooleanSetting("Chests", true);
        public final BooleanSetting shulkers = new BooleanSetting("Shulkers", true);
        public final BooleanSetting droppers = new BooleanSetting("Droppers", true);
        public final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1);
        public final NumberSetting green = new NumberSetting("Green", 180, 0, 255, 1);
        public final NumberSetting blue = new NumberSetting("Blue", 50, 0, 255, 1);
        public final NumberSetting outlineAlpha = new NumberSetting("Outline Opacity", 255, 0, 255, 1);
        public final NumberSetting fillAlpha = new NumberSetting("Fill Opacity", 60, 0, 255, 1);

        public StorageFinder() {
            super("StorageFinder");
            settings.addAll(List.of(mode, chests, shulkers, droppers, red, green, blue, outlineAlpha, fillAlpha));
        }

        @Override
        public void onRenderOverlay(GuiGraphics context) {
            int r = red.getValue().intValue();
            int g = green.getValue().intValue();
            int b = blue.getValue().intValue();
            int oA = outlineAlpha.getValue().intValue();
            int fA = fillAlpha.getValue().intValue();

            for (BlockEntity be : RenderUtils.getLoadedBlockEntities()) {
                boolean valid = (chests.getValue() && (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity || be instanceof BarrelBlockEntity))
                             || (shulkers.getValue() && be instanceof ShulkerBoxBlockEntity)
                             || (droppers.getValue() && (be instanceof DropperBlockEntity || be instanceof DispenserBlockEntity));
                if (valid) {
                    RenderUtils.drawProjectedBox(context, be.getBlockPos(), mode.getValue(), r, g, b, oA, fA);
                }
            }
        }
    }

    public static class SpawnerFinder extends Module {
        public final ModeSetting mode = new ModeSetting("Mode", "Both", "Outline", "Fill", "Both");
        public final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1);
        public final NumberSetting green = new NumberSetting("Green", 50, 0, 255, 1);
        public final NumberSetting blue = new NumberSetting("Blue", 255, 0, 255, 1);
        public final NumberSetting outlineAlpha = new NumberSetting("Outline Opacity", 255, 0, 255, 1);
        public final NumberSetting fillAlpha = new NumberSetting("Fill Opacity", 75, 0, 255, 1);

        public SpawnerFinder() {
            super("SpawnerFinder");
            settings.addAll(List.of(mode, red, green, blue, outlineAlpha, fillAlpha));
        }

        @Override
        public void onRenderOverlay(GuiGraphics context) {
            int r = red.getValue().intValue();
            int g = green.getValue().intValue();
            int b = blue.getValue().intValue();
            int oA = outlineAlpha.getValue().intValue();
            int fA = fillAlpha.getValue().intValue();

            for (BlockEntity be : RenderUtils.getLoadedBlockEntities()) {
                if (be instanceof SpawnerBlockEntity) {
                    RenderUtils.drawProjectedBox(context, be.getBlockPos(), mode.getValue(), r, g, b, oA, fA);
                }
            }
        }
    }

    // ==========================================
    // THROUGH-WALL PROJECTION ENGINE (1.21.11)
    // ==========================================
    public static class RenderUtils {
        private static final Minecraft mc = Minecraft.getInstance();

        public static List<BlockEntity> getLoadedBlockEntities() {
            List<BlockEntity> list = new ArrayList<>();
            if (mc.level == null || mc.player == null) return list;
            int renderDistance = mc.options.getEffectiveRenderDistance();
            ChunkPos playerChunk = mc.player.chunkPosition();

            for (int x = -renderDistance; x <= renderDistance; x++) {
                for (int z = -renderDistance; z <= renderDistance; z++) {
                    LevelChunk chunk = mc.level.getChunkSource().getChunk(playerChunk.x + x, playerChunk.z + z, false);
                    if (chunk != null) {
                        list.addAll(chunk.getBlockEntities().values());
                    }
                }
            }
            return list;
        }

        public static void drawProjectedBox(GuiGraphics context, BlockPos pos, String mode, int r, int g, int b, int oA, int fA) {
            Camera cam = mc.gameRenderer.getMainCamera();
            Vec3 camPos = cam.getPosition();

            double dx = (pos.getX() + 0.5) - camPos.x;
            double dy = (pos.getY() + 0.5) - camPos.y;
            double dz = (pos.getZ() + 0.5) - camPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > 16384) return; // Max 128 blocks

            float yaw = (float) Math.toRadians(cam.getYRot());
            float pitch = (float) Math.toRadians(cam.getXRot());

            Matrix4f view = new Matrix4f()
                .rotationX(pitch)
                .rotateY((float) (yaw + Math.PI));

            double fov = mc.options.fov().get();
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            float aspect = (float) sw / (float) Math.max(1, sh);
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, 1000.0f);

            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            int visibleCorners = 0;

            for (int cx = 0; cx <= 1; cx++) {
                for (int cy = 0; cy <= 1; cy++) {
                    for (int cz = 0; cz <= 1; cz++) {
                        float wx = (float) (pos.getX() + cx - camPos.x);
                        float wy = (float) (pos.getY() + cy - camPos.y);
                        float wz = (float) (pos.getZ() + cz - camPos.z);

                        Vector4f v = new Vector4f(wx, wy, wz, 1.0f);
                        v.mul(view);
                        if (v.z >= -0.05f) continue; // Behind camera
                        v.mul(proj);

                        if (v.w <= 0.0f) continue;
                        float ndcX = v.x / v.w;
                        float ndcY = v.y / v.w;

                        float sx = (ndcX + 1.0f) * 0.5f * sw;
                        float sy = (1.0f - ndcY) * 0.5f * sh;

                        minX = Math.min(minX, sx);
                        minY = Math.min(minY, sy);
                        maxX = Math.max(maxX, sx);
                        maxY = Math.max(maxY, sy);
                        visibleCorners++;
                    }
                }
            }

            if (visibleCorners == 0) return;

            int x1 = Math.max(-20, (int) minX);
            int y1 = Math.max(-20, (int) minY);
            int x2 = Math.min(sw + 20, (int) maxX);
            int y2 = Math.min(sh + 20, (int) maxY);

            if (x2 <= x1 || y2 <= y1 || x2 < 0 || y2 < 0 || x1 > sw || y1 > sh) return;

            int fillColor = ((fA & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            int outColor = ((oA & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);

            if (mode.equals("Fill") || mode.equals("Both")) {
                context.fill(x1, y1, x2, y2, fillColor);
            }
            if (mode.equals("Outline") || mode.equals("Both")) {
                context.fill(x1, y1, x2, y1 + 1, outColor);
                context.fill(x1, y2 - 1, x2, y2, outColor);
                context.fill(x1, y1, x1 + 1, y2, outColor);
                context.fill(x2 - 1, y1, x2, y2, outColor);
            }
        }
    }

    // ==========================================
    // CLICKGUI SCREEN
    // ==========================================
    public static class ClickGuiScreen extends Screen {
        private final List<Module> modules;
        private NumberSetting draggingSlider = null;
        private final int panelX = 40, panelY = 40, panelWidth = 170;
        private boolean leftWasDown = false;
        private boolean rightWasDown = false;

        public ClickGuiScreen(List<Module> modules) {
            super(Component.literal("ClickGUI"));
            this.modules = modules;
        }

        @Override
        public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
            // Reliable mouse button polling using Minecraft's active MouseHandler
            boolean leftDown = Minecraft.getInstance().mouseHandler.isLeftPressed();
            boolean rightDown = Minecraft.getInstance().mouseHandler.isRightPressed();

            if (!leftDown) {
                draggingSlider = null;
            } else if (!leftWasDown) {
                handleMouseClick(mouseX, mouseY, 0);
            }

            if (rightDown && !rightWasDown) {
                handleMouseClick(mouseX, mouseY, 1);
            }

            leftWasDown = leftDown;
            rightWasDown = rightDown;

            context.fillGradient(0, 0, this.width, this.height, 0x7008080C, 0x9008080C);
            context.fill(panelX, panelY - 2, panelX + panelWidth, panelY, 0xFF8A2BE2);
            context.fill(panelX, panelY, panelX + panelWidth, panelY + 20, 0xF014141D);
            context.drawString(this.font, "✦ RENDER MODULES", panelX + 8, panelY + 6, 0xFFFFFFFF, true);

            int currentY = panelY + 20;
            for (Module mod : modules) {
                boolean hovered = isHovered(mouseX, mouseY, panelX, currentY, panelWidth, 18);
                int bgColor = mod.isEnabled() ? 0xE6251B38 : (hovered ? 0xE61E1E2A : 0xE6161620);
                context.fill(panelX, currentY, panelX + panelWidth, currentY + 18, bgColor);

                if (mod.isEnabled()) {
                    context.fill(panelX, currentY, panelX + 3, currentY + 18, 0xFF8A2BE2);
                }

                context.drawString(this.font, mod.getName(), panelX + 8, currentY + 5, mod.isEnabled() ? 0xFFD8B4FE : 0xFFAAAAAA, true);
                context.drawString(this.font, mod.isExpanded() ? "▾" : "▸", panelX + panelWidth - 12, currentY + 5, 0xFF888888, true);
                currentY += 18;

                if (mod.isExpanded()) {
                    for (Setting<?> setting : mod.getSettings()) {
                        context.fill(panelX, currentY, panelX + panelWidth, currentY + 16, 0xF0101016);

                        if (setting instanceof BooleanSetting boolSet) {
                            context.drawString(this.font, boolSet.getName(), panelX + 12, currentY + 4, 0xFFCCCCCC, true);
                            int boxColor = boolSet.getValue() ? 0xFF8A2BE2 : 0xFF2A2A35;
                            context.fill(panelX + panelWidth - 18, currentY + 4, panelX + panelWidth - 10, currentY + 12, boxColor);
                        } else if (setting instanceof ModeSetting modeSet) {
                            context.drawString(this.font, modeSet.getName() + ": " + modeSet.getValue(), panelX + 12, currentY + 4, 0xFFB39DDB, true);
                        } else if (setting instanceof NumberSetting numSet) {
                            if (draggingSlider == numSet) {
                                double percent = Math.max(0, Math.min(1, (double) (mouseX - (panelX + 10)) / (panelWidth - 20)));
                                numSet.setClampedValue(numSet.getMin() + (numSet.getMax() - numSet.getMin()) * percent);
                            }
                            double pct = (numSet.getValue() - numSet.getMin()) / (numSet.getMax() - numSet.getMin());
                            int barWidth = (int) ((panelWidth - 20) * pct);

                            context.fill(panelX + 10, currentY + 12, panelX + panelWidth - 10, currentY + 14, 0xFF252530);
                            context.fill(panelX + 10, currentY + 12, panelX + 10 + barWidth, currentY + 14, 0xFF8A2BE2);
                            context.drawString(this.font, numSet.getName() + ": " + numSet.getValue().intValue(), panelX + 12, currentY + 2, 0xFFCCCCCC, true);
                        }
                        currentY += 16;
                    }
                }
            }
        }

        private void handleMouseClick(double mouseX, double mouseY, int button) {
            int currentY = panelY + 20;
            for (Module mod : modules) {
                if (isHovered(mouseX, mouseY, panelX, currentY, panelWidth, 18)) {
                    if (button == 0) mod.toggle();
                    if (button == 1) mod.setExpanded(!mod.isExpanded());
                    return;
                }
                currentY += 18;

                if (mod.isExpanded()) {
                    for (Setting<?> setting : mod.getSettings()) {
                        if (isHovered(mouseX, mouseY, panelX, currentY, panelWidth, 16) && button == 0) {
                            if (setting instanceof BooleanSetting b) b.toggle();
                            else if (setting instanceof ModeSetting m) m.cycle();
                            else if (setting instanceof NumberSetting n) draggingSlider = n;
                            return;
                        }
                        currentY += 16;
                    }
                }
            }
        }

        @Override
        public boolean isPauseScreen() { return false; }

        private boolean isHovered(double mx, double my, int x, int y, int w, int h) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }
}
