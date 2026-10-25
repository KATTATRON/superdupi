package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExampleModClient implements ClientModInitializer {
    public static final Freecam FREECAM = new Freecam();

    public static final List<Module> MODULES = List.of(
        new ChestESP(),
        new BarrelESP(),
        new EnderChestESP(),
        new HopperESP(),
        new ShulkerESP(),
        new DropperESP(),
        new PistonESP(),
        new SpawnerESP(),
        new AmethystGeodeESP(),
        FREECAM
    );

    private boolean f4WasDown = false;
    private boolean f6WasDown = false;
    private int scanTimer = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;

            if (FREECAM.isEnabled()) {
                KeyMapping.releaseAll();
                FREECAM.freezePlayerPreTick(client);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getWindow() == null) return;
            long window = client.getWindow().handle();

            boolean f4Down = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_F4);
            if (f4Down && !f4WasDown && client.screen == null) {
                client.setScreen(new ClickGuiScreen(MODULES));
            }
            f4WasDown = f4Down;

            boolean f6Pressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F6) == GLFW.GLFW_PRESS;
            if (f6Pressed && !f6WasDown && client.screen == null) {
                FREECAM.toggle();
            }
            f6WasDown = f6Pressed;

            if (client.level != null && client.player != null) {
                scanTimer++;
                if (scanTimer >= 15) {
                    scanTimer = 0;
                    BlockScanner.scanAllEnabled(client, MODULES);
                }
            }

            if (FREECAM.isEnabled()) {
                FREECAM.updateFreecamPostTick(client, window);
            }
        });

        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || mc.options.hideGui) return;

            if (FREECAM.isEnabled()) {
                FREECAM.syncRotationFrame(mc);
            }

            if (mc.screen instanceof ClickGuiScreen) return;

            float partialTick = tickCounter.getGameTimeDeltaPartialTick(false);
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();

            for (Module mod : MODULES) {
                if (mod.isEnabled() && !mod.targets.isEmpty()) {
                    EspRenderer.renderModuleEsp(context, mc, mod, sw, sh, partialTick);
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("clickgui")
                .executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new ClickGuiScreen(MODULES)));
                    return 1;
                }));
        });
    }

    public static class RenderTarget {
        public final BlockPos pos;
        public final float minX, minY, minZ, maxX, maxY, maxZ;

        public RenderTarget(BlockPos pos, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.pos = pos;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }

        public RenderTarget(BlockPos pos) {
            this(pos, 0f, 0f, 0f, 1f, 1f, 1f);
        }
    }

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

        public Module(String name, double r, double g, double b, boolean includeRenderSettings) {
            this.name = name;
            this.red = new NumberSetting("Red", r, 0, 255, 1);
            this.green = new NumberSetting("Green", g, 0, 255, 1);
            this.blue = new NumberSetting("Blue", b, 0, 255, 1);
            if (includeRenderSettings) {
                settings.addAll(List.of(mode, tracers, red, green, blue, outlineAlpha, fillAlpha));
            }
        }

        public void toggle() {
            this.enabled = !this.enabled;
            Minecraft mc = Minecraft.getInstance();
            if (!this.enabled) {
                targets.clear();
            } else if (mc.level != null && mc.player != null) {
                BlockScanner.scanAllEnabled(mc, MODULES);
            }
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.literal("§d[Client] §f" + name + ": " + (enabled ? "§aON" : "§cOFF")),
                    true
                );
            }
        }

        public boolean isEnabled() { return enabled; }
        public boolean isExpanded() { return expanded; }
        public void setExpanded(boolean expanded) { this.expanded = expanded; }
        public String getName() { return name; }
        public List<Setting<?>> getSettings() { return settings; }
    }

    public static class ChestESP extends Module {
        public ChestESP() { super("Chests", 255, 185, 35, true); }
    }

    public static class BarrelESP extends Module {
        public BarrelESP() { super("Barrels", 215, 130, 60, true); }
    }

    public static class EnderChestESP extends Module {
        public EnderChestESP() { super("EnderChests", 170, 0, 255, true); }
    }

    public static class HopperESP extends Module {
        public HopperESP() { super("Hoppers", 85, 255, 255, true); }
    }

    public static class ShulkerESP extends Module {
        public ShulkerESP() { super("Shulkers", 255, 85, 255, true); }
    }

    public static class DropperESP extends Module {
        public DropperESP() { super("Droppers", 150, 180, 255, true); }
    }

    public static class PistonESP extends Module {
        public PistonESP() { super("Pistons", 100, 255, 85, true); }
    }

    public static class SpawnerESP extends Module {
        public SpawnerESP() { super("Spawners", 255, 50, 120, true); }
    }

    public static class AmethystGeodeESP extends Module {
        public AmethystGeodeESP() { super("Amethyst Geode", 180, 85, 255, true); }
    }

    public static class BlockScanner {
        private static Method minYMethod = null;
        private static boolean minYChecked = false;

        private static int getChunkMinY(LevelChunk chunk) {
            if (!minYChecked) {
                minYChecked = true;
                for (String name : List.of("getMinY", "getMinBuildHeight")) {
                    try {
                        minYMethod = chunk.getClass().getMethod(name);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (minYMethod != null) {
                try {
                    Object res = minYMethod.invoke(chunk);
                    if (res instanceof Number n) return n.intValue();
                } catch (Exception ignored) {}
            }
            return -64;
        }

        public static void scanAllEnabled(Minecraft mc, List<Module> modules) {
            if (mc.level == null || mc.player == null) return;

            ChestESP chestMod = null;
            BarrelESP barrelMod = null;
            EnderChestESP enderMod = null;
            HopperESP hopperMod = null;
            ShulkerESP shulkerMod = null;
            DropperESP dropperMod = null;
            PistonESP pistonMod = null;
            SpawnerESP spawnerMod = null;
            AmethystGeodeESP geodeMod = null;

            boolean anyEnabled = false;
            for (Module m : modules) {
                m.targets.clear();
                if (!m.isEnabled()) continue;
                if (m instanceof ChestESP c) { chestMod = c; anyEnabled = true; }
                else if (m instanceof BarrelESP b) { barrelMod = b; anyEnabled = true; }
                else if (m instanceof EnderChestESP e) { enderMod = e; anyEnabled = true; }
                else if (m instanceof HopperESP h) { hopperMod = h; anyEnabled = true; }
                else if (m instanceof ShulkerESP s) { shulkerMod = s; anyEnabled = true; }
                else if (m instanceof DropperESP d) { dropperMod = d; anyEnabled = true; }
                else if (m instanceof PistonESP p) { pistonMod = p; anyEnabled = true; }
                else if (m instanceof SpawnerESP sp) { spawnerMod = sp; anyEnabled = true; }
                else if (m instanceof AmethystGeodeESP g) { geodeMod = g; anyEnabled = true; }
            }

            if (!anyEnabled) return;

            int renderDistance = mc.options.getEffectiveRenderDistance();
            ChunkPos playerChunk = mc.player.chunkPosition();

            // Temporary list to collect raw geode crystal positions for clustering
            List<BlockPos> rawClusters = new ArrayList<>();
            int totalClustersFound = 0;

            for (int cx = -renderDistance; cx <= renderDistance; cx++) {
                for (int cz = -renderDistance; cz <= renderDistance; cz++) {
                    LevelChunk chunk = mc.level.getChunkSource().getChunk(playerChunk.x + cx, playerChunk.z + cz, false);
                    if (chunk == null) continue;

                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        BlockPos p = be.getBlockPos();
                        if (chestMod != null && be instanceof ChestBlockEntity) {
                            chestMod.targets.add(new RenderTarget(p, 0.0625f, 0.0f, 0.0625f, 0.9375f, 0.875f, 0.9375f));
                        } else if (barrelMod != null && be instanceof BarrelBlockEntity) {
                            barrelMod.targets.add(new RenderTarget(p));
                        } else if (enderMod != null && be instanceof EnderChestBlockEntity) {
                            enderMod.targets.add(new RenderTarget(p, 0.0625f, 0.0f, 0.0625f, 0.9375f, 0.875f, 0.9375f));
                        } else if (hopperMod != null && be instanceof HopperBlockEntity) {
                            hopperMod.targets.add(new RenderTarget(p));
                        } else if (shulkerMod != null && be instanceof ShulkerBoxBlockEntity) {
                            shulkerMod.targets.add(new RenderTarget(p));
                        } else if (dropperMod != null && (be instanceof DropperBlockEntity || be instanceof DispenserBlockEntity)) {
                            dropperMod.targets.add(new RenderTarget(p));
                        } else if (spawnerMod != null && be instanceof SpawnerBlockEntity) {
                            spawnerMod.targets.add(new RenderTarget(p));
                        } else if (pistonMod != null && be instanceof PistonMovingBlockEntity) {
                            pistonMod.targets.add(new RenderTarget(p));
                        }
                    }

                    LevelChunkSection[] sections = chunk.getSections();
                    int minY = getChunkMinY(chunk);
                    int baseX = chunk.getPos().getMinBlockX();
                    int baseZ = chunk.getPos().getMinBlockZ();

                    for (int sIdx = 0; sIdx < sections.length; sIdx++) {
                        LevelChunkSection section = sections[sIdx];
                        if (section == null || section.hasOnlyAir()) continue;

                        boolean wantPistons = pistonMod != null && pistonMod.targets.size() < 600;
                        boolean wantGeodes = geodeMod != null && totalClustersFound < 300;

                        if (wantPistons && section.maybeHas(state -> state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))) {
                            int sectionBaseY = minY + (sIdx << 4);
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    for (int x = 0; x < 16; x++) {
                                        BlockState state = section.getBlockState(x, y, z);
                                        if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) {
                                            pistonMod.targets.add(new RenderTarget(new BlockPos(baseX + x, sectionBaseY + y, baseZ + z)));
                                        }
                                    }
                                }
                            }
                        }

                        // Collect grown amethyst clusters to group into single geode boxes
                        if (wantGeodes && section.maybeHas(state -> state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER))) {
                            int sectionBaseY = minY + (sIdx << 4);
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    for (int x = 0; x < 16; x++) {
                                        BlockState state = section.getBlockState(x, y, z);
                                        if (state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER)) {
                                            rawClusters.add(new BlockPos(baseX + x, sectionBaseY + y, baseZ + z));
                                            totalClustersFound++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Cluster raw amethyst points into distinct geodes (1 box per geode cavern)
            if (geodeMod != null && !rawClusters.isEmpty()) {
                List<GeodeCluster> geodes = new ArrayList<>();
                for (BlockPos p : rawClusters) {
                    GeodeCluster closest = null;
                    for (GeodeCluster g : geodes) {
                        if (g.center.closerThan(p, 14.0)) {
                            closest = g;
                            break;
                        }
                    }
                    if (closest != null) {
                        closest.add(p);
                    } else {
                        geodes.add(new GeodeCluster(p));
                    }
                }

                // Turn each geode cluster into a dynamic target whose size scales with maturity
                for (GeodeCluster g : geodes) {
                    float sizeScale = Math.min(3.5f, 1.0f + (g.count * 0.15f));
                    geodeMod.targets.add(new RenderTarget(
                        g.center,
                        -sizeScale, -sizeScale, -sizeScale,
                        1.0f + sizeScale, 1.0f + sizeScale, 1.0f + sizeScale
                    ));
                }
            }
        }
    }

    public static class GeodeCluster {
        public BlockPos center;
        public int count = 0;
        private long sumX = 0, sumY = 0, sumZ = 0;

        public GeodeCluster(BlockPos p) {
            add(p);
        }

        public void add(BlockPos p) {
            sumX += p.getX();
            sumY += p.getY();
            sumZ += p.getZ();
            count++;
            center = new BlockPos((int)(sumX / count), (int)(sumY / count), (int)(sumZ / count));
        }
    }

    public static class Freecam extends Module {
        public final NumberSetting speed = new NumberSetting("Speed", 1.0, 0.1, 5.0, 0.1);

        private double savedX, savedY, savedZ;
        private float savedYaw, savedPitch;
        private float camYaw, camPitch;
        private ArmorStand cameraDummy = null;

        public static double camX, camY, camZ;
        public static boolean isFreecamActive = false;

        public Freecam() {
            super("Freecam [F6]", 50, 200, 255, false);
            settings.add(speed);
        }

        @Override
        public void toggle() {
            super.toggle();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                isFreecamActive = false;
                return;
            }

            isFreecamActive = isEnabled();
            if (isFreecamActive) {
                savedX = mc.player.getX();
                savedY = mc.player.getY();
                savedZ = mc.player.getZ();
                savedYaw = mc.player.getYRot();
                savedPitch = mc.player.getXRot();

                camX = savedX;
                camY = savedY;
                camZ = savedZ;
                camYaw = savedYaw;
                camPitch = savedPitch;

                KeyMapping.releaseAll();
                mc.smartCull = false;

                cameraDummy = new ArmorStand(mc.level, camX, camY, camZ);
                cameraDummy.setInvisible(true);
                cameraDummy.setNoGravity(true);
                cameraDummy.noPhysics = true;
                applyDummyRotation();

                mc.setCameraEntity(cameraDummy);
            } else {
                mc.smartCull = true;
                mc.setCameraEntity(mc.player);
                cameraDummy = null;
                mc.player.setPos(savedX, savedY, savedZ);
                mc.player.setDeltaMovement(0, 0, 0);
                mc.player.setYRot(savedYaw);
                mc.player.setXRot(savedPitch);
            }
        }

        public void freezePlayerPreTick(Minecraft mc) {
            if (mc.player == null) return;
            mc.smartCull = false;

            float deltaYaw = mc.player.getYRot() - savedYaw;
            float deltaPitch = mc.player.getXRot() - savedPitch;
            if (deltaYaw != 0 || deltaPitch != 0) {
                camYaw += deltaYaw;
                camPitch = Math.max(-90.0f, Math.min(90.0f, camPitch + deltaPitch));
            }

            mc.player.setYRot(savedYaw);
            mc.player.yRotO = savedYaw;
            mc.player.setYHeadRot(savedYaw);
            mc.player.yHeadRotO = savedYaw;
            mc.player.setXRot(savedPitch);
            mc.player.xRotO = savedPitch;

            mc.player.setPos(savedX, savedY, savedZ);
            mc.player.xo = savedX;
            mc.player.yo = savedY;
            mc.player.zo = savedZ;
            mc.player.setDeltaMovement(0, 0, 0);
            mc.player.setSprinting(false);
        }

        public void updateFreecamPostTick(Minecraft mc, long window) {
            if (mc.player == null || mc.level == null) {
                isFreecamActive = false;
                return;
            }

            mc.smartCull = false;

            if (cameraDummy == null) {
                cameraDummy = new ArmorStand(mc.level, camX, camY, camZ);
                cameraDummy.setInvisible(true);
                cameraDummy.setNoGravity(true);
                cameraDummy.noPhysics = true;
            }

            if (mc.getCameraEntity() != cameraDummy) {
                mc.setCameraEntity(cameraDummy);
            }

            mc.player.setPos(savedX, savedY, savedZ);
            mc.player.setDeltaMovement(0, 0, 0);

            double forward = 0, strafe = 0, vertical = 0;
            if (mc.screen == null) {
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) forward += 1;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) forward -= 1;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) strafe += 1;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) strafe -= 1;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS) vertical += 1;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS) vertical -= 1;
            }

            double spd = speed.getValue();
            float yawRad = (float) Math.toRadians(camYaw);

            double dx = (-Math.sin(yawRad) * forward - Math.cos(yawRad) * strafe) * spd;
            double dz = (Math.cos(yawRad) * forward - Math.sin(yawRad) * strafe) * spd;
            double dy = vertical * spd;

            cameraDummy.xo = camX;
            cameraDummy.yo = camY;
            cameraDummy.zo = camZ;

            camX += dx;
            camY += dy;
            camZ += dz;

            cameraDummy.setPos(camX, camY, camZ);
            applyDummyRotation();
        }

        public void syncRotationFrame(Minecraft mc) {
            if (cameraDummy == null || mc.player == null) return;

            float deltaYaw = mc.player.getYRot() - savedYaw;
            float deltaPitch = mc.player.getXRot() - savedPitch;
            if (deltaYaw != 0 || deltaPitch != 0) {
                camYaw += deltaYaw;
                camPitch = Math.max(-90.0f, Math.min(90.0f, camPitch + deltaPitch));
                mc.player.setYRot(savedYaw);
                mc.player.yRotO = savedYaw;
                mc.player.setXRot(savedPitch);
                mc.player.xRotO = savedPitch;
            }

            applyDummyRotation();
        }

        private void applyDummyRotation() {
            if (cameraDummy == null) return;
            cameraDummy.setYRot(camYaw);
            cameraDummy.yRotO = camYaw;
            cameraDummy.setYHeadRot(camYaw);
            cameraDummy.yHeadRotO = camYaw;
            cameraDummy.setYBodyRot(camYaw);
            cameraDummy.yBodyRotO = camYaw;
            cameraDummy.setXRot(camPitch);
            cameraDummy.xRotO = camPitch;
        }
    }

    public static class EspRenderer {
        private static final int[][] EDGES = {
            {0,1}, {1,3}, {3,2}, {2,0},
            {4,5}, {5,7}, {7,6}, {6,4},
            {0,4}, {1,5}, {2,6}, {3,7}
        };

        private static Method camPosMethod;
        private static Method camRotMethod;
        private static Method getFovMethod;
        private static boolean reflectionInitialized = false;

        private static void initReflection(Camera cam, Object gameRenderer) {
            if (reflectionInitialized) return;
            reflectionInitialized = true;
            for (Method m : cam.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == Vec3.class) {
                    m.setAccessible(true);
                    camPosMethod = m;
                    break;
                }
            }
            for (Method m : cam.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == Quaternionf.class) {
                    m.setAccessible(true);
                    camRotMethod = m;
                    break;
                }
            }
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
            if (mc.player == null || mc.gameRenderer == null || mod.targets.isEmpty()) return;

            Camera cam = mc.gameRenderer.getMainCamera();
            initReflection(cam, mc.gameRenderer);

            Vec3 camPos = mc.player.getEyePosition(partialTick);
            if (camPosMethod != null) {
                try { camPos = (Vec3) camPosMethod.invoke(cam); } catch (Exception ignored) {}
            }

            Quaternionf invCamRot = null;
            if (camRotMethod != null) {
                try {
                    Quaternionf q = (Quaternionf) camRotMethod.invoke(cam);
                    invCamRot = new Quaternionf(q).conjugate();
                } catch (Exception ignored) {}
            }

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

            int r = mod.red.getValue().intValue();
            int g = mod.green.getValue().intValue();
            int b = mod.blue.getValue().intValue();
            int oA = mod.outlineAlpha.getValue().intValue();
            int fA = mod.fillAlpha.getValue().intValue();
            String renderMode = mod.mode.getValue();

            int outlineColor = ((oA & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            int fillColor = ((fA & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);

            for (RenderTarget target : mod.targets) {
                BlockPos pos = target.pos;

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
                        Vector3f v = new Vector3f(wx, wy, wz).rotate(invCamRot);
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

                if (renderMode.equals("Fill") || renderMode.equals("Both")) {
                    draw3DFaceFill(context, sx, sy, fillColor, sw, sh);
                }

                if (renderMode.equals("Outline") || renderMode.equals("Both")) {
                    for (int[] edge : EDGES) {
                        drawCrispLine(context, Math.round(sx[edge[0]]), Math.round(sy[edge[0]]),
                                      Math.round(sx[edge[1]]), Math.round(sy[edge[1]]), outlineColor, sw, sh);
                    }
                }

                if (mod.tracers.getValue()) {
                    int cx = Math.round((sx[0] + sx[7]) * 0.5f);
                    int cy = Math.round((sy[0] + sy[7]) * 0.5f);
                    drawCrispLine(context, sw / 2, sh / 2, cx, cy, outlineColor, sw, sh);
                }
            }
        }

        private static void draw3DFaceFill(GuiGraphics context, float[] sx, float[] sy, int color, int sw, int sh) {
            int[][] faces = {
                {0, 1, 3, 2},
                {4, 5, 7, 6},
                {0, 1, 5, 4},
                {2, 3, 7, 6},
                {0, 2, 6, 4},
                {1, 3, 7, 5}
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

    public static class ClickGuiScreen extends Screen {
        private final List<Module> modules;
        private NumberSetting draggingSlider = null;
        private final int panelX = 35, panelY = 30, panelWidth = 195;
        private boolean leftWasDown = false;
        private boolean rightWasDown = false;

        public ClickGuiScreen(List<Module> modules) {
            super(Component.literal("ClickGUI"));
            this.modules = modules;
        }

        @Override
        public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {}

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
                if (mod.isEnabled() && !mod.targets.isEmpty()) {
                    EspRenderer.renderModuleEsp(context, mc, mod, this.width, this.height, delta);
                }
            }

            context.fill(panelX, panelY - 2, panelX + panelWidth, panelY, 0xFF8A2BE2);
            context.fill(panelX, panelY, panelX + panelWidth, panelY + 20, 0xF014141D);
            context.drawString(this.font, "✦ BLOCK ESP & CLIENT", panelX + 8, panelY + 6, 0xFFFFFFFF, true);

            int currentY = panelY + 20;
            for (Module mod : modules) {
                boolean hovered = isHovered(mouseX, mouseY, panelX, currentY, panelWidth, 18);
                int bgColor = mod.isEnabled() ? 0xE6251B38 : (hovered ? 0xE61E1E2A : 0xE6161620);
                context.fill(panelX, currentY, panelX + panelWidth, currentY + 18, bgColor);

                if (mod.isEnabled()) {
                    int sideBarColor = (0xFF << 24)
                        | ((mod.red.getValue().intValue() & 0xFF) << 16)
                        | ((mod.green.getValue().intValue() & 0xFF) << 8)
                        | (mod.blue.getValue().intValue() & 0xFF);
                    context.fill(panelX, currentY, panelX + 3, currentY + 18, sideBarColor);
                }

                String label = mod.getName() + (mod.isEnabled() && !(mod instanceof Freecam) ? " [" + mod.targets.size() + "]" : "");
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
                            String valStr = numSet.step < 1.0
                                ? String.format("%.1f", numSet.getValue())
                                : String.valueOf(numSet.getValue().intValue());
                            context.drawString(this.font, numSet.getName() + ": " + valStr, panelX + 12, currentY + 2, 0xFFCCCCCC, true);
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
