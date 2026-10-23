package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
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
        // ClickGUI mit F4 öffnen + Block-Scanner im Hintergrund
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getWindow() == null) return;
            boolean f4Down = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_F4);

            if (f4Down && !f4WasDown && client.screen == null) {
                client.setScreen(new ClickGuiScreen(MODULES));
            }
            f4WasDown = f4Down;

            if (client.level != null && client.player != null) {
                scanTimer++;
                if (scanTimer >= 15) {
                    scanTimer = 0;
                    for (Module m : MODULES) {
                        if (m.isEnabled()) {
                            m.onTick(client);
                        }
                    }
                }
            }
        });

        // Dauerhaftes 3D-ESP im Spiel (auch wenn F4 geschlossen ist!)
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || mc.options.hideGui) return;
            // Nicht doppelt zeichnen, falls ClickGUI gerade offen ist
            if (mc.screen instanceof ClickGuiScreen) return;

            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            for (Module mod : MODULES) {
                if (mod.isEnabled()) {
                    EspRenderer.renderModuleEsp(context, mc, mod, sw, sh);
                }
            }
        });

        // Backup Chat-Befehl: /clickgui
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

        public final ModeSetting mode = new ModeSetting("Mode", "Both", "Outline", "Fill", "Both");
        public final NumberSetting red;
        public final NumberSetting green;
        public final NumberSetting blue;
        public final NumberSetting outlineAlpha = new NumberSetting("Outline Opacity", 255, 0, 255, 1);
        public final NumberSetting fillAlpha = new NumberSetting("Fill Opacity", 70, 0, 255, 1);
        public final BooleanSetting tracers = new BooleanSetting("Tracers", false);

        public Module(String name, double r, double g, double b) {
            this.name = name;
            this.red = new NumberSetting("Red", r, 0, 255, 1);
            this.green = new NumberSetting("Green", g, 0, 255, 1);
            this.blue = new NumberSetting("Blue", b, 0, 255, 1);
        }

        public void toggle() {
            this.enabled = !this.enabled;
            Minecraft mc = Minecraft.getInstance();
            if (this.enabled && mc.level != null) {
                onTick(mc);
            }
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.literal("§d[ESP] §f" + name + ": " + (enabled ? "§aON" : "§cOFF")),
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
        public final BooleanSetting chests = new BooleanSetting("Chests", true);
        public final BooleanSetting shulkers = new BooleanSetting("Shulkers", true);
        public final BooleanSetting droppers = new BooleanSetting("Droppers", true);

        public StorageFinder() {
            super("StorageFinder", 255, 180, 50);
            settings.addAll(List.of(mode, chests, shulkers, droppers, tracers, red, green, blue, outlineAlpha, fillAlpha));
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
        public SpawnerFinder() {
            super("SpawnerFinder", 255, 50, 255);
            settings.addAll(List.of(mode, tracers, red, green, blue, outlineAlpha, fillAlpha));
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
    // ECHTER 3D-BLOCK-PROJEKTIONS-RENDERER
    // ==========================================
    public static class EspRenderer {
        private static final int[][] EDGES = {
            {0,1}, {1,3}, {3,2}, {2,0}, // Unterseite
            {4,5}, {5,7}, {7,6}, {6,4}, // Oberseite
            {0,4}, {1,5}, {2,6}, {3,7}  // Vertikale Kanten
        };

        public static void renderModuleEsp(GuiGraphics context, Minecraft mc, Module mod, int sw, int sh) {
            if (mc.player == null) return;
            Vec3 camPos = mc.player.getEyePosition();
            float yaw = (float) Math.toRadians(mc.player.getYRot());
            float pitch = (float) Math.toRadians(mc.player.getXRot());
            double fov = mc.options.fov().get();
            float f = (float) (1.0 / Math.tan(Math.toRadians(fov) / 2.0));
            float aspect = (float) sw / (float) Math.max(1, sh);

            float cosYaw = (float) Math.cos(yaw + Math.PI);
            float sinYaw = (float) Math.sin(yaw + Math.PI);
            float cosPitch = (float) Math.cos(pitch);
            float sinPitch = (float) Math.sin(pitch);

            int r = mod.red.getValue().intValue();
            int g = mod.green.getValue().intValue();
            int b = mod.blue.getValue().intValue();
            int oA = mod.outlineAlpha.getValue().intValue();
            int fA = mod.fillAlpha.getValue().intValue();

            int outlineColor = ((oA & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            int fillColor = ((fA & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            String renderMode = mod.mode.getValue();

            for (BlockPos pos : mod.foundPositions) {
                int[] sx = new int[8];
                int[] sy = new int[8];
                boolean allVisible = true;
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

                // Alle 8 Ecken des 1x1x1 Blocks in 3D projizieren
                for (int i = 0; i < 8; i++) {
                    float cx = pos.getX() + ((i & 1) != 0 ? 1.0f : 0.0f) - (float) camPos.x;
                    float cy = pos.getY() + ((i & 2) != 0 ? 1.0f : 0.0f) - (float) camPos.y;
                    float cz = pos.getZ() + ((i & 4) != 0 ? 1.0f : 0.0f) - (float) camPos.z;

                    float rx = cx * cosYaw + cz * sinYaw;
                    float rz = -cx * sinYaw + cz * cosYaw;

                    float ry = cy * cosPitch - rz * sinPitch;
                    float finalZ = cy * sinPitch + rz * cosPitch;

                    if (finalZ >= -0.05f) {
                        allVisible = false;
                        break;
                    }

                    float ndcX = (rx * (f / aspect)) / (-finalZ);
                    float ndcY = (ry * f) / (-finalZ);

                    sx[i] = (int) ((ndcX + 1.0f) * 0.5f * sw);
                    sy[i] = (int) ((1.0f - ndcY) * 0.5f * sh);

                    minX = Math.min(minX, sx[i]);
                    minY = Math.min(minY, sy[i]);
                    maxX = Math.max(maxX, sx[i]);
                    maxY = Math.max(maxY, sy[i]);
                }

                if (!allVisible) continue;
                if (maxX < 0 || maxY < 0 || minX > sw || minY > sh) continue;

                // 1. Fill zeichnen (wenn Fill oder Both aktiv)
                if (renderMode.equals("Fill") || renderMode.equals("Both")) {
                    int fx1 = Math.max(0, minX);
                    int fy1 = Math.max(0, minY);
                    int fx2 = Math.min(sw, maxX);
                    int fy2 = Math.min(sh, maxY);
                    if (fx2 > fx1 && fy2 > fy1) {
                        context.fill(fx1, fy1, fx2, fy2, fillColor);
                    }
                }

                // 2. Echte 3D-Kanten (12 Linien des Würfels) zeichnen
                if (renderMode.equals("Outline") || renderMode.equals("Both")) {
                    for (int[] edge : EDGES) {
                        drawLine(context, sx[edge[0]], sy[edge[0]], sx[edge[1]], sy[edge[1]], outlineColor, sw, sh);
                    }
                }

                // 3. Optional: Tracer-Linie von der Bildschirmmitte zum Block
                if (mod.tracers.getValue()) {
                    int centerX = (minX + maxX) / 2;
                    int centerY = (minY + maxY) / 2;
                    drawLine(context, sw / 2, sh / 2, centerX, centerY, outlineColor, sw, sh);
                }
            }
        }

        private static void drawLine(GuiGraphics context, int x1, int y1, int x2, int y2, int color, int sw, int sh) {
            int dx = x2 - x1;
            int dy = y2 - y1;
            int steps = Math.max(Math.abs(dx), Math.abs(dy));
            if (steps == 0 || steps > 2500) return;

            float xInc = dx / (float) steps;
            float yInc = dy / (float) steps;
            float x = x1;
            float y = y1;

            for (int i = 0; i <= steps; i++) {
                int px = (int) x;
                int py = (int) y;
                if (px >= 0 && px < sw && py >= 0 && py < sh) {
                    context.fill(px, py, px + 1, py + 1, color);
                }
                x += xInc;
                y += yInc;
            }
        }
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

        // Überschreibt den starken Hintergrund-Blur in 1.21+, damit man das Spiel beim Einstellen klar sieht
        @Override
        public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
            // Leer lassen -> kein verschwommener Hintergrund!
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

            // ESP auch live im Hintergrund weiterzeichnen, während das Menü offen ist
            for (Module mod : modules) {
                if (mod.isEnabled()) {
                    EspRenderer.renderModuleEsp(context, mc, mod, this.width, this.height);
                }
            }

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
