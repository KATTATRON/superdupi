package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
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
        // F4 Keybind + Background Block Scanner
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getWindow() == null) return;
            boolean f4Down = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_F4);

            if (f4Down && !f4WasDown && client.screen == null) {
                client.setScreen(new ClickGuiScreen(MODULES));
            }
            f4WasDown = f4Down;

            if (client.level != null && client.player != null) {
                scanTimer++;
                if (scanTimer >= 10) {
                    scanTimer = 0;
                    for (Module m : MODULES) {
                        if (m.isEnabled()) {
                            m.onTick(client);
                        }
                    }
                }
            }
        });

        // Locked-In 3D Camera ESP on HUD
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || mc.options.hideGui) return;
            if (mc.screen instanceof ClickGuiScreen) return;

            float partialTick = tickCounter.getGameTimeDeltaPartialTick(false);
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();

            for (Module mod : MODULES) {
                if (mod.isEnabled()) {
                    EspRenderer.renderModuleEsp(context, mc, mod, sw, sh, partialTick);
                }
            }
        });

        // Backup Chat Command: /clickgui
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
    // TARGET DATA HOLDER
    // ==========================================
    public static class RenderTarget {
        public final BlockPos pos;
        public final float minX, minY, minZ, maxX, maxY, maxZ;
        public final int customColor;

        public RenderTarget(BlockPos pos, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int customColor) {
            this.pos = pos;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
            this.customColor = customColor;
        }
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
        public final List<RenderTarget> targets = new ArrayList<>();

        public final ModeSetting mode = new ModeSetting("Mode", "Both", "Outline", "Fill", "Both");
        public final NumberSetting red;
        public final NumberSetting green;
        public final NumberSetting blue;
        public final NumberSetting outlineAlpha = new NumberSetting("Outline Opacity", 255, 0, 255, 1);
        public final NumberSetting fillAlpha = new NumberSetting("Fill Opacity", 65, 0, 255, 1);
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
        public final BooleanSetting chests = new BooleanSetting("Chests & Barrels", true);
        public final BooleanSetting enderChests = new BooleanSetting("EnderChests", true);
        public final BooleanSetting hoppers = new BooleanSetting("Hoppers", true);
        public final BooleanSetting shulkers = new BooleanSetting("Shulkers", true);
        public final BooleanSetting droppers = new BooleanSetting("Droppers & Dispensers", true);

        public StorageFinder() {
            super("StorageFinder", 255, 180, 40);
            settings.addAll(List.of(
                mode, chests, enderChests, hoppers, shulkers, droppers,
                tracers, red, green, blue, outlineAlpha, fillAlpha
            ));
        }

        @Override
        public void onTick(Minecraft mc) {
            targets.clear();
            for (BlockEntity be : getLoadedBlockEntities(mc)) {
                BlockPos p = be.getBlockPos();

                if (chests.getValue() && be instanceof ChestBlockEntity) {
                    targets.add(new RenderTarget(p, 0.0625f, 0.0f, 0.0625f, 0.9375f, 0.875f, 0.9375f, -1));
                } else if (chests.getValue() && be instanceof BarrelBlockEntity) {
                    targets.add(new RenderTarget(p, 0f, 0f, 0f, 1f, 1f, 1f, -1));
                } else if (enderChests.getValue() && be instanceof EnderChestBlockEntity) {
                    targets.add(new RenderTarget(p, 0.0625f, 0.0f, 0.0625f, 0.9375f, 0.875f, 0.9375f, 0xAA00FF));
                } else if (hoppers.getValue() && be instanceof HopperBlockEntity) {
                    targets.add(new RenderTarget(p, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0x55FFFF));
                } else if (shulkers.getValue() && be instanceof ShulkerBoxBlockEntity) {
                    targets.add(new RenderTarget(p, 0f, 0f, 0f, 1f, 1f, 1f, 0xFF55FF));
                } else if (droppers.getValue() && (be instanceof DropperBlockEntity || be instanceof DispenserBlockEntity)) {
                    targets.add(new RenderTarget(p, 0f, 0f, 0f, 1f, 1f, 1f, -1));
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
            targets.clear();
            for (BlockEntity be : getLoadedBlockEntities(mc)) {
                if (be instanceof SpawnerBlockEntity) {
                    targets.add(new RenderTarget(be.getBlockPos(), 0f, 0f, 0f, 1f, 1f, 1f, -1));
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
    // EXACT CAMERA-LOCKED 3D PROJECTION ENGINE
    // ==========================================
    public static class EspRenderer {
        private static final int[][] EDGES = {
            {0,1}, {1,3}, {3,2}, {2,0}, // Bottom
            {4,5}, {5,7}, {7,6}, {6,4}, // Top
            {0,4}, {1,5}, {2,6}, {3,7}  // Pillars
        };

        private static Method camPosMethod;
        private static Method camRotMethod;
        private static Method getFovMethod;
        private static boolean reflectionInitialized = false;

        private static void initReflection(Camera cam, Object gameRenderer) {
            if (reflectionInitialized) return;
            reflectionInitialized = true;
            // Find Camera Vec3 position method (getPosition vs position)
            for (Method m : cam.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == Vec3.class) {
                    m.setAccessible(true);
                    camPosMethod = m;
                    break;
                }
            }
            // Find Camera Quaternionf rotation method
            for (Method m : cam.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == Quaternionf.class) {
                    m.setAccessible(true);
                    camRotMethod = m;
                    break;
                }
            }
            // Find GameRenderer.getFov(Camera, float, boolean)
            for (Method m : gameRenderer.getClass().getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 3 && params[0] == Camera.class && params[1] == float.class && params[2] == boolean.class) {
                    m.setAccessible(true);
                    getFovMethod = m;
                    break;
                }
            }
        }

        public static void renderModuleEsp(GuiGraphics context, Minecraft mc, Module mod, int sw, int sh, float partialTick) {
            if (mc.player == null || mc.gameRenderer == null) return;

            Camera cam = mc.gameRenderer.getMainCamera();
            initReflection(cam, mc.gameRenderer);

            // 1. Get exact Camera Position (includes view bobbing & third-person)
            Vec3 camPos = mc.player.getEyePosition(partialTick);
            if (camPosMethod != null) {
                try { camPos = (Vec3) camPosMethod.invoke(cam); } catch (Exception ignored) {}
            }

            // 2. Get exact Camera Rotation Quaternion (includes view bobbing roll/pitch/yaw)
            Quaternionf invCamRot = null;
            if (camRotMethod != null) {
                try {
                    Quaternionf q = (Quaternionf) camRotMethod.invoke(cam);
                    invCamRot = new Quaternionf(q).conjugate();
                } catch (Exception ignored) {}
            }

            // 3. Get exact vertical FOV from GameRenderer
            double fov = mc.options.fov().get() * mc.player.getFieldOfViewModifier(true, partialTick);
            if (getFovMethod != null) {
                try {
                    Object res = getFovMethod.invoke(mc.gameRenderer, cam, partialTick, true);
                    if (res instanceof Number num) fov = num.doubleValue();
                } catch (Exception ignored) {}
            }

            float f = (float) (1.0 / Math.tan(Math.toRadians(fov) / 2.0));
            float aspect = (float) sw / (float) Math.max(1, sh);

            float yaw = (float) Math.toRadians(mc.player.getViewYRot(partialTick));
            float pitch = (float) Math.toRadians(mc.player.getViewXRot(partialTick));
            float cosYaw = (float) Math.cos(yaw + Math.PI);
            float sinYaw = (float) Math.sin(yaw + Math.PI);
            float cosPitch = (float) Math.cos(pitch);
            float sinPitch = (float) Math.sin(pitch);

            int baseR = mod.red.getValue().intValue();
            int baseG = mod.green.getValue().intValue();
            int baseB = mod.blue.getValue().intValue();
            int oA = mod.outlineAlpha.getValue().intValue();
            int fA = mod.fillAlpha.getValue().intValue();
            String renderMode = mod.mode.getValue();

            for (RenderTarget target : mod.targets) {
                BlockPos pos = target.pos;
                int r = target.customColor != -1 ? ((target.customColor >> 16) & 0xFF) : baseR;
                int g = target.customColor != -1 ? ((target.customColor >> 8) & 0xFF) : baseG;
                int b = target.customColor != -1 ? (target.customColor & 0xFF) : baseB;

                int outlineColor = ((oA & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
                int fillColor = ((fA & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);

                float[] sx = new float[8];
                float[] sy = new float[8];
                boolean allVisible = true;

                for (int i = 0; i < 8; i++) {
                    float bx = (i & 1) != 0 ? target.maxX : target.minX;
                    float by = (i & 2) != 0 ? target.maxY : target.minY;
                    float bz = (i & 4) != 0 ? target.maxZ : target.minZ;

                    float wx = (float) (pos.getX() + bx - camPos.x);
                    float wy = (float) (pos.getY() + by - camPos.y);
                    float wz = (float) (pos.getZ() + bz - camPos.z);

                    float rx, ry, finalZ;
                    if (invCamRot != null) {
                        // Transform world offset directly by the camera's exact 3D quaternion
                        Vector3f v = new Vector3f(wx, wy, wz).rotate(invCamRot);
                        // In Minecraft's camera quaternion space, -Z is forward, -X is right (or +X), +Y is up
                        rx = v.x;
                        ry = v.y;
                        finalZ = v.z;
                    } else {
                        rx = wx * cosYaw + wz * sinYaw;
                        float rz = -wx * sinYaw + wz * cosYaw;
                        ry = wy * cosPitch - rz * sinPitch;
                        finalZ = wy * sinPitch + rz * cosPitch;
                    }

                    if (finalZ >= -0.05f) {
                        allVisible = false;
                        break;
                    }

                    float ndcX = (rx * (f / aspect)) / (-finalZ);
                    float ndcY = (ry * f) / (-finalZ);

                    sx[i] = (ndcX + 1.0f) * 0.5f * sw;
                    sy[i] = (1.0f - ndcY) * 0.5f * sh;
                }

                if (!allVisible) continue;

                // 1. True 3D Wireframe Fill (interpolated 3D face grid instead of ugly 2D square)
                if (renderMode.equals("Fill") || renderMode.equals("Both")) {
                    draw3DFaceFill(context, sx, sy, fillColor, sw, sh);
                }

                // 2. Crisp 3D Wireframe Edges
                if (renderMode.equals("Outline") || renderMode.equals("Both")) {
                    for (int[] edge : EDGES) {
                        drawCrispLine(context, Math.round(sx[edge[0]]), Math.round(sy[edge[0]]),
                                      Math.round(sx[edge[1]]), Math.round(sy[edge[1]]), outlineColor, sw, sh);
                    }
                }

                // 3. Tracer Line
                if (mod.tracers.getValue()) {
                    int cx = Math.round((sx[0] + sx[7]) * 0.5f);
                    int cy = Math.round((sy[0] + sy[7]) * 0.5f);
                    drawCrispLine(context, sw / 2, sh / 2, cx, cy, outlineColor, sw, sh);
                }
            }
        }

        // Fills the actual 3D faces of the cube so there is no flat 2D square sticking out
        private static void draw3DFaceFill(GuiGraphics context, float[] sx, float[] sy, int color, int sw, int sh) {
            int[][] faces = {
                {0, 1, 3, 2}, // Bottom
                {4, 5, 7, 6}, // Top
                {0, 1, 5, 4}, // North
                {2, 3, 7, 6}, // South
                {0, 2, 6, 4}, // West
                {1, 3, 7, 5}  // East
            };
            int steps = 6;
            for (int[] face : faces) {
                for (int i = 1; i < steps; i++) {
                    float t = i / (float) steps;
                    int x1 = Math.round(sx[face[0]] + (sx[face[3]] - sx[face[0]]) * t);
                    int y1 = Math.round(sy[face[0]] + (sy[face[3]] - sy[face[0]]) * t);
                    int x2 = Math.round(sx[face[1]] + (sx[face[2]] - sx[face[1]]) * t);
                    int y2 = Math.round(sy[face[1]] + (sy[face[2]] - sy[face[1]]) * t);
                    drawCrispLine(context, x1, y1, x2, y2, color, sw, sh);
                }
            }
        }

        private static void drawCrispLine(GuiGraphics context, int x0, int y0, int x1, int y1, int color, int sw, int sh) {
            int dx = Math.abs(x1 - x0);
            int dy = Math.abs(y1 - y0);
            if (dx > 2000 || dy > 2000) return;

            int sx = x0 < x1 ? 1 : -1;
            int sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;

            while (true) {
                if (x0 >= 0 && x0 < sw && y0 >= 0 && y0 < sh) {
                    context.fill(x0, y0, x0 + 1, y0 + 1, color);
                }
                if (x0 == x1 && y0 == y1) break;
                int e2 = 2 * err;
                if (e2 > -dy) {
                    err -= dy;
                    x0 += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    y0 += sy;
                }
            }
        }
    }

    // ==========================================
    // CLICKGUI SCREEN
    // ==========================================
    public static class ClickGuiScreen extends Screen {
        private final List<Module> modules;
        private NumberSetting draggingSlider = null;
        private final int panelX = 40, panelY = 40, panelWidth = 195;
        private boolean leftWasDown = false;
        private boolean rightWasDown = false;

        public ClickGuiScreen(List<Module> modules) {
            super(Component.literal("ClickGUI"));
            this.modules = modules;
        }

        @Override
        public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
            // No blur
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

            for (Module mod : modules) {
                if (mod.isEnabled()) {
                    EspRenderer.renderModuleEsp(context, mc, mod, this.width, this.height, delta);
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

                String label = mod.getName() + (mod.isEnabled() ? " [" + mod.targets.size() + "]" : "");
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
