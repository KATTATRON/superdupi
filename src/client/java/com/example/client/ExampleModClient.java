package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class ExampleModClient implements ClientModInitializer {
    public static final List<Module> MODULES = List.of(
        new StorageFinder(),
        new SpawnerFinder()
    );

    private boolean f4WasDown = false;
    private int scanTimer = 0;

    @Override
    public void onInitializeClient() {
        // ClickGUI mit F4 öffnen + Block-Scanner ausführen
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getWindow() == null) return;
            boolean f4Down = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_F4);

            if (f4Down && !f4WasDown && client.screen == null) {
                client.setScreen(new ClickGuiScreen(MODULES));
            }
            f4WasDown = f4Down;

            // Alle 20 Ticks (1 Sekunde) geladene BlockEntities scannen
            if (client.level != null && client.player != null) {
                scanTimer++;
                if (scanTimer >= 20) {
                    scanTimer = 0;
                    for (Module m : MODULES) {
                        if (m.isEnabled()) {
                            m.onTick(client);
                        }
                    }
                }
            }
        });

        // Alternativer Chat-Befehl: /clickgui
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("clickgui")
                .executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new ClickGuiScreen(MODULES)));
                    return 1;
                }));
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
        public final List<BlockPos> foundPositions = new ArrayList<>();

        public Module(String name) { this.name = name; }
        public void toggle() {
            this.enabled = !this.enabled;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.literal("§d[ClickGUI] §f" + name + ": " + (enabled ? "§aON" : "§cOFF")),
                    true
                );
            }
        }
        public boolean isEnabled() { return enabled; }
        public boolean isExpanded() { return expanded; }
        public void setExpanded(boolean expanded) { this.expanded = expanded; }
        public String getName() { return name; }
        public List<Setting<?>> getSettings() { return settings; }
        public abstract void onTick(Minecraft mc);
    }

    public static class StorageFinder extends Module {
        public final ModeSetting mode = new ModeSetting("Mode", "Both", "Outline", "Fill", "Both");
        public final BooleanSetting chests = new BooleanSetting("Chests", true);
        public final BooleanSetting shulkers = new BooleanSetting("Shulkers", true);
        public final BooleanSetting droppers = new BooleanSetting("Droppers", true);
        public final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1);
        public final NumberSetting green = new NumberSetting("Green", 180, 0, 255, 1);
        public final NumberSetting blue = new NumberSetting("Blue", 50, 0, 255, 1);

        public StorageFinder() {
            super("StorageFinder");
            settings.addAll(List.of(mode, chests, shulkers, droppers, red, green, blue));
        }

        @Override
        public void onTick(Minecraft mc) {
            foundPositions.clear();
            for (BlockEntity be : getLoadedBlockEntities(mc)) {
                boolean valid = (chests.getValue() && (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity || be instanceof BarrelBlockEntity))
                             || (shulkers.getValue() && be instanceof ShulkerBoxBlockEntity)
                             || (droppers.getValue() && (be instanceof DropperBlockEntity || be instanceof DispenserBlockEntity));
                if (valid) {
                    foundPositions.add(be.getBlockPos());
                }
            }
        }
    }

    public static class SpawnerFinder extends Module {
        public final ModeSetting mode = new ModeSetting("Mode", "Both", "Outline", "Fill", "Both");
        public final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1);
        public final NumberSetting green = new NumberSetting("Green", 50, 0, 255, 1);
        public final NumberSetting blue = new NumberSetting("Blue", 255, 0, 255, 1);

        public SpawnerFinder() {
            super("SpawnerFinder");
            settings.addAll(List.of(mode, red, green, blue));
        }

        @Override
        public void onTick(Minecraft mc) {
            foundPositions.clear();
            for (BlockEntity be : getLoadedBlockEntities(mc)) {
                if (be instanceof SpawnerBlockEntity) {
                    foundPositions.add(be.getBlockPos());
                }
            }
        }
    }

    public static List<BlockEntity> getLoadedBlockEntities(Minecraft mc) {
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

    // ==========================================
    // CLICKGUI SCREEN
    // ==========================================
    public static class ClickGuiScreen extends Screen {
        private final List<Module> modules;
        private NumberSetting draggingSlider = null;
        private final int panelX = 40, panelY = 40, panelWidth = 190;
        private boolean leftWasDown = false;
        private boolean rightWasDown = false;

        public ClickGuiScreen(List<Module> modules) {
            super(Component.literal("ClickGUI"));
            this.modules = modules;
        }

        @Override
        public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
            Minecraft mc = Minecraft.getInstance();
            long windowHandle = mc.getWindow().handle();
            boolean leftDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean rightDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

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

            // Zeichne gefundene Blöcke als ESP-Boxen auf den Bildschirm
            for (Module mod : modules) {
                if (mod.isEnabled()) {
                    mod.onTick(mc);
                    drawTargetsOnScreen(context, mc, mod);
                }
            }

            context.fillGradient(0, 0, this.width, this.height, 0x5008080C, 0x7008080C);
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

                String label = mod.getName() + (mod.isEnabled() ? " [" + mod.foundPositions.size() + "]" : "");
                context.drawString(this.font, label, panelX + 8, currentY + 5, mod.isEnabled() ? 0xFFD8B4FE : 0xFFAAAAAA, true);
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

        private void drawTargetsOnScreen(GuiGraphics context, Minecraft mc, Module mod) {
            if (mc.player == null) return;
            Vec3 camPos = mc.player.getEyePosition();
            float yaw = (float) Math.toRadians(mc.player.getYRot());
            float pitch = (float) Math.toRadians(mc.player.getXRot());
            double fov = mc.options.fov().get();
            float f = (float) (1.0 / Math.tan(Math.toRadians(fov) / 2.0));
            float aspect = (float) this.width / (float) Math.max(1, this.height);

            float cosYaw = (float) Math.cos(yaw + Math.PI);
            float sinYaw = (float) Math.sin(yaw + Math.PI);
            float cosPitch = (float) Math.cos(pitch);
            float sinPitch = (float) Math.sin(pitch);

            int color = mod instanceof SpawnerFinder ? 0xFFFF32FF : 0xFFFFB432;

            for (BlockPos pos : mod.foundPositions) {
                float dx = (float) (pos.getX() + 0.5 - camPos.x);
                float dy = (float) (pos.getY() + 0.5 - camPos.y);
                float dz = (float) (pos.getZ() + 0.5 - camPos.z);

                float rx = dx * cosYaw + dz * sinYaw;
                float rz = -dx * sinYaw + dz * cosYaw;

                float ry = dy * cosPitch - rz * sinPitch;
                float finalZ = dy * sinPitch + rz * cosPitch;

                if (finalZ >= -0.1f) continue;

                float ndcX = (rx * (f / aspect)) / (-finalZ);
                float ndcY = (ry * f) / (-finalZ);

                int sx = (int) ((ndcX + 1.0f) * 0.5f * this.width);
                int sy = (int) ((1.0f - ndcY) * 0.5f * this.height);

                int dist = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
                int boxSize = Math.max(4, Math.min(24, (int) (120.0f / Math.max(1, dist))));

                if (sx >= 0 && sx <= this.width && sy >= 0 && sy <= this.height) {
                    context.fill(sx - boxSize, sy - boxSize, sx + boxSize, sy + boxSize, 0x55FFB432);
                    context.fill(sx - boxSize, sy - boxSize, sx + boxSize, sy - boxSize + 1, color);
                    context.fill(sx - boxSize, sy + boxSize - 1, sx + boxSize, sy + boxSize, color);
                    context.fill(sx - boxSize, sy - boxSize, sx - boxSize + 1, sy + boxSize, color);
                    context.fill(sx + boxSize - 1, sy - boxSize, sx + boxSize, sy + boxSize, color);
                    context.drawString(this.font, dist + "m", sx - 6, sy - boxSize - 10, 0xFFFFFFFF, true);
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
