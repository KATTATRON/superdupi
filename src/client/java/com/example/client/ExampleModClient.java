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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExampleModClient implements ClientModInitializer {
    public static final Freecam FREECAM = new Freecam();
    public static final ArmorStandESP ARMOR_STAND_ESP = new ArmorStandESP();
    public static final MinecartESP MINECART_ESP = new MinecartESP();
    public static final PvpESP PVP_ESP = new PvpESP();
    public static final StashFinder STASH_FINDER = new StashFinder();

    public static final List<Module> BLOCK_MODULES = List.of(
        new ChestESP(),
        new BarrelESP(),
        new EnderChestESP(),
        new HopperESP(),
        new ShulkerESP(),
        new DropperESP(),
        new PistonESP(),
        new SpawnerESP(),
        new RespawnAnchorESP(),
        ARMOR_STAND_ESP,
        MINECART_ESP,
        new AmethystGeodeESP()
    );

    public static final List<Module> MODULES = new ArrayList<>();
    static {
        MODULES.addAll(BLOCK_MODULES);
        MODULES.add(PVP_ESP);
        MODULES.add(STASH_FINDER);
        MODULES.add(FREECAM);
    }

    private boolean f4WasDown = false;
    private boolean vWasDown = false;
    private int scanTimer = 0;
    private Object lastWorld = null;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.getWindow() != null) {
                long window = client.getWindow().handle();

                boolean f4Down = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_F4)
                              || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F4) == GLFW.GLFW_PRESS;
                if (f4Down && !f4WasDown && client.screen == null) {
                    client.setScreen(new ClickGuiScreen());
                }
                f4WasDown = f4Down;

                boolean vDown = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_V)
                             || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_V) == GLFW.GLFW_PRESS;
                if (vDown && !vWasDown && client.screen == null) {
                    FREECAM.toggle();
                }
                vWasDown = vDown;
            }

            if (client.player == null || client.level == null) return;

            if (FREECAM.isEnabled()) {
                KeyMapping.releaseAll();
                FREECAM.freezePlayerPreTick(client);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getWindow() == null) return;
            long window = client.getWindow().handle();

            if (client.level != lastWorld) {
                lastWorld = client.level;
                for (Module m : MODULES) {
                    m.cache.clear();
                    m.targets.clear();
                }
                STASH_FINDER.alertedChunks.clear();
                STASH_FINDER.stashBoxes.clear();
            }

            if (client.level != null && client.player != null) {
                scanTimer++;
                if (scanTimer >= 12) {
                    scanTimer = 0;
                    BlockScanner.scanAllEnabled(client, BLOCK_MODULES);
                }
                if (ARMOR_STAND_ESP.isEnabled()) {
                    BlockScanner.scanArmorStands(client, ARMOR_STAND_ESP);
                }
                if (MINECART_ESP.isEnabled()) {
                    BlockScanner.scanMinecarts(client, MINECART_ESP);
                }
                if (STASH_FINDER.isEnabled()) {
                    STASH_FINDER.updateStashBoxes(client.player);
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

            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();

            if (!(mc.screen instanceof ClickGuiScreen)) {
                float partialTick = tickCounter.getGameTimeDeltaPartialTick(false);

                for (Module mod : BLOCK_MODULES) {
                    if (mod.isEnabled() && !mod.targets.isEmpty()) {
                        EspRenderer.renderModuleEsp(context, mc, mod, sw, sh, partialTick);
                    }
                }

                if (PVP_ESP.isEnabled()) {
                    EspRenderer.renderPvpEsp(context, mc, PVP_ESP, sw, sh, partialTick);
                }

                if (STASH_FINDER.isEnabled() && !STASH_FINDER.targets.isEmpty()) {
                    EspRenderer.renderModuleEsp(context, mc, STASH_FINDER, sw, sh, partialTick);
                }
            }

            if (STASH_FINDER.isEnabled()) {
                STASH_FINDER.renderBottomPopup(context, mc, sw, sh);
            }

            // Name: FISH Overlay auf dem Bildschirm
            if (PVP_ESP.fishName.getValue()) {
                context.drawString(mc.font, "§d[PvP] §fName: §bFISH", sw - 85, 8, 0xFFFFFFFF, true);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("clickgui")
                .executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new ClickGuiScreen()));
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

    // ==========================================
    // MODULES
    // ==========================================
    public static abstract class Module {
        private final String name;
        private boolean enabled = false;
        private boolean expanded = false;
        protected final List<Setting<?>> settings = new ArrayList<>();
        public final Map<BlockPos, RenderTarget> cache = new HashMap<>();
        public final List<RenderTarget> targets = new ArrayList<>();

        public final ModeSetting mode = new ModeSetting("Mode", "Both", "Outline", "Fill", "Both");
        public final NumberSetting red;
        public final NumberSetting green;
        public final NumberSetting blue;
        public final NumberSetting outlineAlpha = new NumberSetting("Outline Opacity", 255, 0, 255, 1);
        public final NumberSetting fillAlpha = new NumberSetting("Fill Opacity", 65, 0, 255, 1);
        public final BooleanSetting tracers = new BooleanSetting("Tracers", true);

        public Module(String name, double r, double g, double b, boolean includeRenderSettings) {
            this.name = name;
            this.red = new NumberSetting("Red", r, 0, 255, 1);
            this.green = new NumberSetting("Green", g, 0, 255, 1);
            this.blue = new NumberSetting("Blue", b, 0, 255, 1);
            if (includeRenderSettings) {
                settings.addAll(List.of(mode, tracers, red, green, blue, outlineAlpha, fillAlpha));
            }
        }

        public void cacheTarget(RenderTarget t) {
            if (cache.size() < 1500) {
                cache.put(t.pos, t);
            }
        }

        public void toggle() {
            this.enabled = !this.enabled;
            Minecraft mc = Minecraft.getInstance();
            if (!this.enabled) {
                cache.clear();
                targets.clear();
            } else if (mc.level != null && mc.player != null) {
                BlockScanner.scanAllEnabled(mc, BLOCK_MODULES);
                if (this instanceof ArmorStandESP a) {
                    BlockScanner.scanArmorStands(mc, a);
                }
                if (this instanceof MinecartESP m) {
                    BlockScanner.scanMinecarts(mc, m);
                }
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

    public static class RespawnAnchorESP extends Module {
        public RespawnAnchorESP() { super("Respawn Anchors", 255, 215, 0, true); }
    }

    public static class ArmorStandESP extends Module {
        public ArmorStandESP() { super("Armor Stands", 50, 255, 160, true); }
    }

    public static class MinecartESP extends Module {
        public MinecartESP() { super("Minecarts (Chest/Hopper)", 255, 140, 0, true); }
    }

    public static class AmethystGeodeESP extends Module {
        public AmethystGeodeESP() { super("Amethyst Geode", 180, 85, 255, true); }
    }

    public static class PvpESP extends Module {
        public final BooleanSetting showHandItem = new BooleanSetting("Show Hand Item", true);
        public final BooleanSetting showTotems = new BooleanSetting("Show Totem Count", true);
        public final BooleanSetting showArmor = new BooleanSetting("Show Armor & HP", true);
        public final BooleanSetting fishName = new BooleanSetting("Name: FISH", false);

        public PvpESP() {
            super("PvP", 255, 55, 55, true);
            settings.add(1, showHandItem);
            settings.add(2, showTotems);
            settings.add(3, showArmor);
            settings.add(4, fishName);
        }

        public static int countPlayerTotems(Player p) {
            int count = 0;
            try {
                int size = p.getInventory().getContainerSize();
                for (int i = 0; i < size; i++) {
                    ItemStack s = p.getInventory().getItem(i);
                    if (!s.isEmpty() && s.is(Items.TOTEM_OF_UNDYING)) {
                        count += s.getCount();
                    }
                }
            } catch (Exception ignored) {}

            if (count == 0) {
                ItemStack main = p.getMainHandItem();
                ItemStack off = p.getOffhandItem();
                if (!main.isEmpty() && main.is(Items.TOTEM_OF_UNDYING)) count += main.getCount();
                if (!off.isEmpty() && off.is(Items.TOTEM_OF_UNDYING)) count += off.getCount();
            }
            return count;
        }

        public static int calculateArmorPercent(Player p) {
            int totalMax = 0;
            int totalCurrent = 0;
            EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            for (EquipmentSlot slot : armorSlots) {
                ItemStack armor = p.getItemBySlot(slot);
                if (!armor.isEmpty()) {
                    int max = armor.getMaxDamage();
                    if (max > 0) {
                        totalMax += max;
                        int dmg = armor.getDamageValue();
                        totalCurrent += Math.max(0, max - dmg);
                    }
                }
            }
            if (totalMax == 0) return 100;
            return (int) Math.round(((double) totalCurrent / totalMax) * 100.0);
        }
    }

    public static class StashFinder extends Module {
        public final NumberSetting minBlocks = new NumberSetting("Min Containers", 15, 5, 100, 1);
        public final Set<Long> alertedChunks = new HashSet<>();
        public final Map<Long, BlockPos> stashBoxes = new HashMap<>();

        private String popupTitle = "";
        private String popupSub = "";
        private long popupEndTime = 0;

        public StashFinder() {
            super("Stash Finder", 255, 215, 0, true);
            settings.add(minBlocks);
        }

        public void registerStash(long chunkKey, BlockPos centerPos) {
            if (!stashBoxes.containsKey(chunkKey)) {
                stashBoxes.put(chunkKey, centerPos);
            }
        }

        public void updateStashBoxes(Player player) {
            targets.clear();
            Iterator<Map.Entry<Long, BlockPos>> it = stashBoxes.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, BlockPos> entry = it.next();
                BlockPos pos = entry.getValue();

                if (pos.closerThan(player.blockPosition(), 24.0)) {
                    continue;
                }

                targets.add(new RenderTarget(
                    pos,
                    -2.0f, 0.0f, -2.0f,
                    3.0f, 3.0f, 3.0f
                ));
            }
        }

        public void triggerAlert(int x, int z, int totalContainers, int chests, int barrels, int shulkers, int hoppers, int carts) {
            this.popupTitle = "⚠ STASH FOUND AT X: " + x + " Z: " + z + " (" + totalContainers + " Containers)";
            StringBuilder details = new StringBuilder();
            if (chests > 0) details.append("Chests: ").append(chests).append("  ");
            if (barrels > 0) details.append("Barrels: ").append(barrels).append("  ");
            if (shulkers > 0) details.append("Shulkers: ").append(shulkers).append("  ");
            if (hoppers > 0) details.append("Hoppers: ").append(hoppers).append("  ");
            if (carts > 0) details.append("Minecarts: ").append(carts);
            this.popupSub = details.toString().trim();
            this.popupEndTime = System.currentTimeMillis() + 8000L;
        }

        public void renderBottomPopup(GuiGraphics context, Minecraft mc, int sw, int sh) {
            long now = System.currentTimeMillis();
            if (now >= popupEndTime || popupTitle.isEmpty()) return;

            int titleW = mc.font.width(popupTitle);
            int subW = mc.font.width(popupSub);
            int boxW = Math.max(titleW, subW) + 20;
            int boxH = popupSub.isEmpty() ? 20 : 32;

            int bx = (sw - boxW) / 2;
            int by = sh - 68 - boxH;

            context.fill(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, 0xFFFFD700);
            context.fill(bx, by, bx + boxW, by + boxH, 0xEE12121A);

            context.drawString(mc.font, popupTitle, bx + (boxW - titleW) / 2, by + 5, 0xFFFFD700, true);
            if (!popupSub.isEmpty()) {
                context.drawString(mc.font, popupSub, bx + (boxW - subW) / 2, by + 18, 0xFFE0E0E0, true);
            }
        }
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

        private static boolean isStorageMinecart(Entity e) {
            EntityType<?> t = e.getType();
            return t == EntityType.CHEST_MINECART || t == EntityType.HOPPER_MINECART;
        }

        public static void scanArmorStands(Minecraft mc, ArmorStandESP mod) {
            mod.targets.clear();
            if (mc.level == null || mc.player == null) return;

            AABB searchBox = mc.player.getBoundingBox().inflate(256.0);
            List<ArmorStand> stands = mc.level.getEntitiesOfClass(ArmorStand.class, searchBox);
            for (ArmorStand stand : stands) {
                if (stand == FREECAM.getCameraDummy()) continue;

                BlockPos bp = stand.blockPosition();
                float ox = (float) (stand.getX() - bp.getX());
                float oy = (float) (stand.getY() - bp.getY());
                float oz = (float) (stand.getZ() - bp.getZ());

                mod.targets.add(new RenderTarget(
                    bp,
                    ox - 0.25f, oy, oz - 0.25f,
                    ox + 0.25f, oy + 1.975f, oz + 0.25f
                ));
            }
        }

        public static void scanMinecarts(Minecraft mc, MinecartESP mod) {
            mod.targets.clear();
            if (mc.level == null || mc.player == null) return;

            AABB searchBox = mc.player.getBoundingBox().inflate(256.0);
            List<Entity> carts = mc.level.getEntities(mc.player, searchBox, BlockScanner::isStorageMinecart);
            for (Entity cart : carts) {
                BlockPos bp = cart.blockPosition();
                float ox = (float) (cart.getX() - bp.getX());
                float oy = (float) (cart.getY() - bp.getY());
                float oz = (float) (cart.getZ() - bp.getZ());

                mod.targets.add(new RenderTarget(
                    bp,
                    ox - 0.45f, oy, oz - 0.45f,
                    ox + 0.45f, oy + 0.7f, oz + 0.45f
                ));
            }
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
            RespawnAnchorESP anchorMod = null;
            AmethystGeodeESP geodeMod = null;
            boolean wantStash = STASH_FINDER.isEnabled();

            boolean anyEnabled = wantStash;
            BlockPos playerPos = mc.player.blockPosition();

            for (Module m : modules) {
                if (!m.isEnabled()) continue;
                if (m instanceof ChestESP c) { chestMod = c; anyEnabled = true; }
                else if (m instanceof BarrelESP b) { barrelMod = b; anyEnabled = true; }
                else if (m instanceof EnderChestESP e) { enderMod = e; anyEnabled = true; }
                else if (m instanceof HopperESP h) { hopperMod = h; anyEnabled = true; }
                else if (m instanceof ShulkerESP s) { shulkerMod = s; anyEnabled = true; }
                else if (m instanceof DropperESP d) { dropperMod = d; anyEnabled = true; }
                else if (m instanceof PistonESP p) { pistonMod = p; anyEnabled = true; }
                else if (m instanceof SpawnerESP sp) { spawnerMod = sp; anyEnabled = true; }
                else if (m instanceof RespawnAnchorESP ra) { anchorMod = ra; anyEnabled = true; }
                else if (m instanceof AmethystGeodeESP g) { geodeMod = g; anyEnabled = true; }

                if (!(m instanceof AmethystGeodeESP) && !(m instanceof ArmorStandESP) && !(m instanceof MinecartESP) && !(m instanceof StashFinder)) {
                    Iterator<Map.Entry<BlockPos, RenderTarget>> it = m.cache.entrySet().iterator();
                    while (it.hasNext()) {
                        BlockPos pos = it.next().getKey();
                        if (!pos.closerThan(playerPos, 500.0)) {
                            it.remove();
                        } else if (pos.closerThan(playerPos, 24.0) && mc.level.getBlockState(pos).isAir()) {
                            it.remove();
                        }
                    }
                }
            }

            if (!anyEnabled) return;

            Map<Long, Integer> cartsPerChunk = new HashMap<>();
            if (wantStash) {
                AABB searchBox = mc.player.getBoundingBox().inflate(256.0);
                for (Entity cart : mc.level.getEntities(mc.player, searchBox, BlockScanner::isStorageMinecart)) {
                    long cKey = cart.chunkPosition().toLong();
                    cartsPerChunk.put(cKey, cartsPerChunk.getOrDefault(cKey, 0) + 1);
                }
            }

            int renderDistance = mc.options.getEffectiveRenderDistance();
            ChunkPos playerChunk = mc.player.chunkPosition();

            Map<BlockPos, Integer> rawGeodeBlocks = new HashMap<>();

            for (int cx = -renderDistance; cx <= renderDistance; cx++) {
                for (int cz = -renderDistance; cz <= renderDistance; cz++) {
                    LevelChunk chunk = mc.level.getChunkSource().getChunk(playerChunk.x + cx, playerChunk.z + cz, false);
                    if (chunk == null) continue;

                    int cChests = 0, cBarrels = 0, cShulkers = 0, cHoppers = 0;
                    long sumX = 0, sumY = 0, sumZ = 0;
                    int containerCountForCenter = 0;

                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        BlockPos p = be.getBlockPos();
                        sumX += p.getX(); sumY += p.getY(); sumZ += p.getZ();
                        containerCountForCenter++;

                        if (be instanceof ChestBlockEntity) {
                            cChests++;
                            if (chestMod != null) chestMod.cacheTarget(new RenderTarget(p, 0.0625f, 0.0f, 0.0625f, 0.9375f, 0.875f, 0.9375f));
                        } else if (be instanceof BarrelBlockEntity) {
                            cBarrels++;
                            if (barrelMod != null) barrelMod.cacheTarget(new RenderTarget(p));
                        } else if (be instanceof EnderChestBlockEntity) {
                            cChests++;
                            if (enderMod != null) enderMod.cacheTarget(new RenderTarget(p, 0.0625f, 0.0f, 0.0625f, 0.9375f, 0.875f, 0.9375f));
                        } else if (be instanceof HopperBlockEntity) {
                            cHoppers++;
                            if (hopperMod != null) hopperMod.cacheTarget(new RenderTarget(p));
                        } else if (be instanceof ShulkerBoxBlockEntity) {
                            cShulkers++;
                            if (shulkerMod != null) shulkerMod.cacheTarget(new RenderTarget(p));
                        } else if (dropperMod != null && (be instanceof DropperBlockEntity || be instanceof DispenserBlockEntity)) {
                            dropperMod.cacheTarget(new RenderTarget(p));
                        } else if (spawnerMod != null && be instanceof SpawnerBlockEntity) {
                            spawnerMod.cacheTarget(new RenderTarget(p));
                        } else if (pistonMod != null && be instanceof PistonMovingBlockEntity) {
                            pistonMod.cacheTarget(new RenderTarget(p));
                        }
                    }

                    if (wantStash) {
                        long chunkKey = chunk.getPos().toLong();
                        int cCarts = cartsPerChunk.getOrDefault(chunkKey, 0);
                        int total = cChests + cBarrels + cShulkers + cHoppers + cCarts;
                        if (total >= STASH_FINDER.minBlocks.getValue().intValue()) {
                            if (containerCountForCenter > 0) {
                                BlockPos centerPos = new BlockPos((int)(sumX / containerCountForCenter), (int)(sumY / containerCountForCenter), (int)(sumZ / containerCountForCenter));
                                STASH_FINDER.registerStash(chunkKey, centerPos);
                            }
                            if (!STASH_FINDER.alertedChunks.contains(chunkKey)) {
                                STASH_FINDER.alertedChunks.add(chunkKey);
                                int centerX = chunk.getPos().getMiddleBlockX();
                                int centerZ = chunk.getPos().getMiddleBlockZ();
                                STASH_FINDER.triggerAlert(centerX, centerZ, total, cChests, cBarrels, cShulkers, cHoppers, cCarts);
                            }
                        }
                    }

                    if (pistonMod == null && anchorMod == null && geodeMod == null) continue;

                    LevelChunkSection[] sections = chunk.getSections();
                    int minY = getChunkMinY(chunk);
                    int baseX = chunk.getPos().getMinBlockX();
                    int baseZ = chunk.getPos().getMinBlockZ();

                    for (int sIdx = 0; sIdx < sections.length; sIdx++) {
                        LevelChunkSection section = sections[sIdx];
                        if (section == null || section.hasOnlyAir()) continue;

                        boolean wantPistons = pistonMod != null && pistonMod.cache.size() < 600;
                        boolean wantAnchors = anchorMod != null && anchorMod.cache.size() < 400;
                        boolean wantGeodes = geodeMod != null && rawGeodeBlocks.size() < 500;

                        if (wantPistons && section.maybeHas(state -> state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))) {
                            int sectionBaseY = minY + (sIdx << 4);
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    for (int x = 0; x < 16; x++) {
                                        BlockState state = section.getBlockState(x, y, z);
                                        if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) {
                                            pistonMod.cacheTarget(new RenderTarget(new BlockPos(baseX + x, sectionBaseY + y, baseZ + z)));
                                        }
                                    }
                                }
                            }
                        }

                        if (wantAnchors && section.maybeHas(state -> state.is(Blocks.RESPAWN_ANCHOR))) {
                            int sectionBaseY = minY + (sIdx << 4);
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    for (int x = 0; x < 16; x++) {
                                        BlockState state = section.getBlockState(x, y, z);
                                        if (state.is(Blocks.RESPAWN_ANCHOR)) {
                                            anchorMod.cacheTarget(new RenderTarget(new BlockPos(baseX + x, sectionBaseY + y, baseZ + z)));
                                        }
                                    }
                                }
                            }
                        }

                        if (wantGeodes && section.maybeHas(state ->
                                state.is(Blocks.SMALL_AMETHYST_BUD) ||
                                state.is(Blocks.MEDIUM_AMETHYST_BUD) ||
                                state.is(Blocks.LARGE_AMETHYST_BUD) ||
                                state.is(Blocks.AMETHYST_CLUSTER))) {
                            int sectionBaseY = minY + (sIdx << 4);
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    for (int x = 0; x < 16; x++) {
                                        BlockState state = section.getBlockState(x, y, z);
                                        int weight = 0;
                                        if (state.is(Blocks.AMETHYST_CLUSTER)) weight = 5;
                                        else if (state.is(Blocks.LARGE_AMETHYST_BUD)) weight = 3;
                                        else if (state.is(Blocks.MEDIUM_AMETHYST_BUD)) weight = 2;
                                        else if (state.is(Blocks.SMALL_AMETHYST_BUD)) weight = 1;

                                        if (weight > 0) {
                                            rawGeodeBlocks.put(new BlockPos(baseX + x, sectionBaseY + y, baseZ + z), weight);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            for (Module m : modules) {
                if (m.isEnabled() && !(m instanceof AmethystGeodeESP) && !(m instanceof ArmorStandESP) && !(m instanceof MinecartESP) && !(m instanceof StashFinder)) {
                    m.targets.clear();
                    m.targets.addAll(m.cache.values());
                }
            }

            if (geodeMod != null) {
                geodeMod.targets.clear();
                if (!rawGeodeBlocks.isEmpty()) {
                    List<GeodeCluster> geodes = new ArrayList<>();
                    for (Map.Entry<BlockPos, Integer> entry : rawGeodeBlocks.entrySet()) {
                        BlockPos p = entry.getKey();
                        int weight = entry.getValue();

                        GeodeCluster closest = null;
                        for (GeodeCluster g : geodes) {
                            if (g.center.closerThan(p, 16.0)) {
                                closest = g;
                                break;
                            }
                        }
                        if (closest != null) {
                            closest.add(p, weight);
                        } else {
                            geodes.add(new GeodeCluster(p, weight));
                        }
                    }

                    for (GeodeCluster g : geodes) {
                        float sizeScale = Math.min(4.5f, 0.6f + (g.growthScore * 0.12f));
                        geodeMod.targets.add(new RenderTarget(
                            g.center,
                            -sizeScale, -sizeScale, -sizeScale,
                            1.0f + sizeScale, 1.0f + sizeScale, 1.0f + sizeScale
                        ));
                    }
                }
            }
        }
    }

    public static class GeodeCluster {
        public BlockPos center;
        public int count = 0;
        public int growthScore = 0;
        private long sumX = 0, sumY = 0, sumZ = 0;

        public GeodeCluster(BlockPos p, int weight) {
            add(p, weight);
        }

        public void add(BlockPos p, int weight) {
            sumX += p.getX();
            sumY += p.getY();
            sumZ += p.getZ();
            count++;
            growthScore += weight;
            center = new BlockPos((int) (sumX / count), (int) (sumY / count), (int) (sumZ / count));
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
            super("Freecam [V]", 50, 200, 255, false);
            settings.add(speed);
        }

        public ArmorStand getCameraDummy() {
            return cameraDummy;
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

        public static void renderPvpEsp(GuiGraphics context, Minecraft mc, PvpESP mod, int sw, int sh, float partialTick) {
            if (mc.player == null || mc.level == null || mc.gameRenderer == null) return;

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

            mod.targets.clear();

            for (Player targetPlayer : mc.level.players()) {
                if (targetPlayer == mc.player && !Freecam.isFreecamActive) continue;
                mod.targets.add(new RenderTarget(targetPlayer.blockPosition()));

                double px = targetPlayer.xo + (targetPlayer.getX() - targetPlayer.xo) * partialTick;
                double py = targetPlayer.yo + (targetPlayer.getY() - targetPlayer.yo) * partialTick;
                double pz = targetPlayer.zo + (targetPlayer.getZ() - targetPlayer.zo) * partialTick;

                float halfW = 0.35f;
                float height = (float) targetPlayer.getBbHeight();

                float[] sx = new float[8];
                float[] sy = new float[8];
                boolean allVisible = true;
                float minScreenY = Float.MAX_VALUE;

                for (int i = 0; i < 8; i++) {
                    float bx = (i & 1) != 0 ? halfW : -halfW;
                    float by = (i & 2) != 0 ? height : 0.0f;
                    float bz = (i & 4) != 0 ? halfW : -halfW;

                    float wx = (float) (px + bx - camPos.x);
                    float wy = (float) (py + by - camPos.y);
                    float wz = (float) (pz + bz - camPos.z);

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
                    minScreenY = Math.min(minScreenY, sy[i]);
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

                int centerX = Math.round((sx[0] + sx[7]) * 0.5f);
                int centerY = Math.round((sy[0] + sy[7]) * 0.5f);

                if (mod.tracers.getValue()) {
                    drawCrispLine(context, sw / 2, sh / 2, centerX, centerY, outlineColor, sw, sh);
                }

                int tagY = Math.max(12, Math.round(minScreenY) - 24);
                int dist = (int) Math.round(mc.player.distanceTo(targetPlayer));

                String pName = targetPlayer.getName().getString();
                if (mod.fishName.getValue() && mc.player != null && targetPlayer.getUUID().equals(mc.player.getUUID())) {
                    pName = "FISH";
                }
                String nameLine = "§c" + pName + " §7[" + dist + "m]";

                ItemStack mainHand = targetPlayer.getMainHandItem();
                ItemStack offHand = targetPlayer.getOffhandItem();
                String handName = mainHand.isEmpty() ? "Empty" : mainHand.getHoverName().getString();
                if (!offHand.isEmpty() && !offHand.is(Items.TOTEM_OF_UNDYING)) {
                    handName += " + " + offHand.getHoverName().getString();
                }

                int totems = PvpESP.countPlayerTotems(targetPlayer);
                int armorPct = PvpESP.calculateArmorPercent(targetPlayer);
                float hp = targetPlayer.getHealth();

                StringBuilder infoLine = new StringBuilder();
                if (mod.showHandItem.getValue()) {
                    infoLine.append("§fHand: §b").append(handName);
                }
                if (mod.showTotems.getValue()) {
                    if (infoLine.length() > 0) infoLine.append(" §7| ");
                    infoLine.append("§eTotems: §6").append(totems);
                }

                StringBuilder armorLine = new StringBuilder();
                if (mod.showArmor.getValue()) {
                    armorLine.append("§c❤ ").append(String.format("%.1f", hp)).append(" §7| §aArmor: ").append(armorPct).append("%");
                }

                int nameW = mc.font.width(nameLine);
                context.fill(centerX - nameW / 2 - 3, tagY - 2, centerX + nameW / 2 + 3, tagY + 9, 0xAA08080C);
                context.drawString(mc.font, nameLine, centerX - nameW / 2, tagY, 0xFFFFFFFF, true);

                if (infoLine.length() > 0) {
                    String infoStr = infoLine.toString();
                    int infoW = mc.font.width(infoStr);
                    context.fill(centerX - infoW / 2 - 3, tagY + 10, centerX + infoW / 2 + 3, tagY + 21, 0xAA08080C);
                    context.drawString(mc.font, infoStr, centerX - infoW / 2, tagY + 11, 0xFFFFFFFF, true);
                    tagY += 11;
                }

                if (armorLine.length() > 0) {
                    String armorStr = armorLine.toString();
                    int armorW = mc.font.width(armorStr);
                    context.fill(centerX - armorW / 2 - 3, tagY + 10, centerX + armorW / 2 + 3, tagY + 21, 0xAA08080C);
                    context.drawString(mc.font, armorStr, centerX - armorW / 2, tagY + 11, 0xFFFFFFFF, true);
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

    // ==========================================
    // CLICKGUI SCREEN
    // ==========================================
    public static class ClickGuiScreen extends Screen {
        private static boolean blockEspTabExpanded = true;
        private NumberSetting draggingSlider = null;
        private final int col1X = 30, col2X = 250, panelY = 25, panelWidth = 205;
        private boolean leftWasDown = false;
        private boolean rightWasDown = false;

        public ClickGuiScreen() {
            super(Component.literal("ClickGUI"));
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

            for (Module mod : BLOCK_MODULES) {
                if (mod.isEnabled() && !mod.targets.isEmpty()) {
                    EspRenderer.renderModuleEsp(context, mc, mod, this.width, this.height, delta);
                }
            }
            if (PVP_ESP.isEnabled()) {
                EspRenderer.renderPvpEsp(context, mc, PVP_ESP, this.width, this.height, delta);
            }
            if (STASH_FINDER.isEnabled() && !STASH_FINDER.targets.isEmpty()) {
                EspRenderer.renderModuleEsp(context, mc, STASH_FINDER, this.width, this.height, delta);
            }

            boolean headerHovered = isHovered(mouseX, mouseY, col1X, panelY, panelWidth, 22);
            context.fill(col1X, panelY - 2, col1X + panelWidth, panelY, 0xFF8A2BE2);
            context.fill(col1X, panelY, col1X + panelWidth, panelY + 22, headerHovered ? 0xF0221C35 : 0xF014141D);

            long activeBlocks = BLOCK_MODULES.stream().filter(Module::isEnabled).count();
            String blockHeaderTitle = "✦ BLOCK ESP [" + activeBlocks + "/" + BLOCK_MODULES.size() + "]";
            context.drawString(this.font, blockHeaderTitle, col1X + 8, panelY + 7, 0xFFFFFFFF, true);
            context.drawString(this.font, blockEspTabExpanded ? "▾" : "▸", col1X + panelWidth - 14, panelY + 7, 0xFFD8B4FE, true);

            if (blockEspTabExpanded) {
                int currentY = panelY + 22;
                for (Module mod : BLOCK_MODULES) {
                    currentY = renderModuleRow(context, mod, col1X, currentY, mouseX, mouseY, true);
                }
            }

            context.fill(col2X, panelY - 2, col2X + panelWidth, panelY, 0xFFFF3737);
            context.fill(col2X, panelY, col2X + panelWidth, panelY + 22, 0xF014141D);
            context.drawString(this.font, "⚔ PVP & UTILITY", col2X + 8, panelY + 7, 0xFFFFFFFF, true);

            int rightY = panelY + 22;
            rightY = renderModuleRow(context, PVP_ESP, col2X, rightY, mouseX, mouseY, false);
            rightY = renderModuleRow(context, STASH_FINDER, col2X, rightY, mouseX, mouseY, false);
            renderModuleRow(context, FREECAM, col2X, rightY, mouseX, mouseY, false);
        }

        private int renderModuleRow(GuiGraphics context, Module mod, int x, int currentY, int mouseX, int mouseY, boolean isSubItem) {
            boolean hovered = isHovered(mouseX, mouseY, x, currentY, panelWidth, 18);
            int bgColor = mod.isEnabled() ? 0xE6251B38 : (hovered ? 0xE61E1E2A : 0xE6161620);
            context.fill(x, currentY, x + panelWidth, currentY + 18, bgColor);

            if (isSubItem) {
                context.fill(x, currentY, x + 2, currentY + 18, 0x668A2BE2);
            }

            if (mod.isEnabled()) {
                int sideBarColor = (0xFF << 24)
                    | ((mod.red.getValue().intValue() & 0xFF) << 16)
                    | ((mod.green.getValue().intValue() & 0xFF) << 8)
                    | (mod.blue.getValue().intValue() & 0xFF);
                context.fill(x, currentY, x + 3, currentY + 18, sideBarColor);
            }

            int textOffset = isSubItem ? 12 : 8;
            String prefix = isSubItem ? "• " : "";
            boolean showCount = mod.isEnabled() && !(mod instanceof Freecam) && !(mod instanceof StashFinder);
            String label = prefix + mod.getName() + (showCount ? " [" + mod.targets.size() + "]" : "");
            context.drawString(this.font, label, x + textOffset, currentY + 5, mod.isEnabled() ? 0xFFD8B4FE : 0xFFCCCCCC, true);
            context.drawString(this.font, mod.isExpanded() ? "▾" : "▸", x + panelWidth - 12, currentY + 5, 0xFF888888, true);
            currentY += 18;

            if (mod.isExpanded()) {
                for (Setting<?> setting : mod.getSettings()) {
                    context.fill(x, currentY, x + panelWidth, currentY + 16, 0xF0101016);

                    if (setting instanceof BooleanSetting boolSet) {
                        context.drawString(this.font, boolSet.getName(), x + 14, currentY + 4, 0xFFCCCCCC, true);
                        int boxColor = boolSet.getValue() ? 0xFF8A2BE2 : 0xFF2A2A35;
                        context.fill(panelX + panelWidth - 18, currentY + 4, panelX + panelWidth - 10, currentY + 12, boxColor);
                    } else if (setting instanceof ModeSetting modeSet) {
                        context.drawString(this.font, modeSet.getName() + ": " + modeSet.getValue(), x + 14, currentY + 4, 0xFFB39DDB, true);
                    } else if (setting instanceof NumberSetting numSet) {
                        if (draggingSlider == numSet) {
                            double percent = Math.max(0, Math.min(1, (double) (mouseX - (x + 12)) / (panelWidth - 24)));
                            numSet.setClampedValue(numSet.getMin() + (numSet.getMax() - numSet.getMin()) * percent);
                        }
                        double pct = (numSet.getValue() - numSet.getMin()) / (numSet.getMax() - numSet.getMin());
                        int barWidth = (int) ((panelWidth - 24) * pct);

                        context.fill(x + 12, currentY + 12, x + panelWidth - 12, currentY + 14, 0xFF252530);
                        context.fill(x + 12, currentY + 12, x + 12 + barWidth, currentY + 14, 0xFF8A2BE2);
                        String valStr = numSet.step < 1.0
                            ? String.format("%.1f", numSet.getValue())
                            : String.valueOf(numSet.getValue().intValue());
                        context.drawString(this.font, numSet.getName() + ": " + valStr, x + 14, currentY + 2, 0xFFCCCCCC, true);
                    }
                    currentY += 16;
                }
            }
            return currentY;
        }

        private void handleMouseClick(double mouseX, double mouseY, int button) {
            if (isHovered(mouseX, mouseY, col1X, panelY, panelWidth, 22)) {
                blockEspTabExpanded = !blockEspTabExpanded;
                return;
            }

            if (blockEspTabExpanded) {
                if (checkColumnClick(BLOCK_MODULES, col1X, panelY + 22, mouseX, mouseY, button)) return;
            }

            checkColumnClick(List.of(PVP_ESP, STASH_FINDER, FREECAM), col2X, panelY + 22, mouseX, mouseY, button);
        }

        private boolean checkColumnClick(List<Module> colModules, int x, int startY, double mouseX, double mouseY, int button) {
            int currentY = startY;
            for (Module mod : colModules) {
                if (isHovered(mouseX, mouseY, x, currentY, panelWidth, 18)) {
                    if (button == 0) mod.toggle();
                    if (button == 1) mod.setExpanded(!mod.isExpanded());
                    return true;
                }
                currentY += 18;

                if (mod.isExpanded()) {
                    for (Setting<?> setting : mod.getSettings()) {
                        if (isHovered(mouseX, mouseY, x, currentY, panelWidth, 16) && button == 0) {
                            if (setting instanceof BooleanSetting b) b.toggle();
                            else if (setting instanceof ModeSetting m) m.cycle();
                            else if (setting instanceof NumberSetting n) draggingSlider = n;
                            return true;
                        }
                        currentY += 16;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean isPauseScreen() { return false; }

        private boolean isHovered(double mx, double my, int x, int y, int w, int h) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }
}
