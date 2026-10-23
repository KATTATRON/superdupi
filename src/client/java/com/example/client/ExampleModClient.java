package com.example;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class ExampleModClient implements ClientModInitializer {
    public static final List<Module> MODULES = List.of(
        new StorageFinder(),
        new SpawnerFinder()
    );

    private static KeyMapping clickGuiKey;

    @Override
    public void onInitializeClient() {
        clickGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.example.clickgui",
            GLFW.GLFW_KEY_F4,
            "category.example.client"
        ));

        // ClickGUI mit F4 öffnen
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (clickGuiKey.consumeClick()) {
                client.setScreen(new ClickGuiScreen(MODULES));
            }
        });

        // Alternativer Befehl: /clickgui
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("clickgui")
                .executes(c -> {
                    Minecraft.getInstance().tell(() -> Minecraft.getInstance().setScreen(new ClickGuiScreen(MODULES)));
                    return 1;
                }));
        });

        // ESP Rendering durch Wände
        WorldRenderEvents.LAST.register(context -> {
            for (Module mod : MODULES) {
                if (mod.isEnabled()) {
                    mod.onWorldRender(context.matrixStack());
                }
            }
        });
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

        public Module(String name) { this.name = name; }
        public void toggle() { this.enabled = !this.enabled; }
        public boolean isEnabled() { return enabled; }
        public boolean isExpanded() { return expanded; }
        public void setExpanded(boolean expanded) { this.expanded = expanded; }
        public String getName() { return name; }
        public List<Setting<?>> getSettings() { return settings; }
        public abstract void onWorldRender(PoseStack matrices);
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
        public void onWorldRender(PoseStack matrices) {
            float r = red.getValue().floatValue() / 255f;
            float g = green.getValue().floatValue() / 255f;
            float b = blue.getValue().floatValue() / 255f;
            float oA = outlineAlpha.getValue().floatValue() / 255f;
            float fA = fillAlpha.getValue().floatValue() / 255f;

            for (BlockEntity be : RenderUtils.getLoadedBlockEntities()) {
                boolean valid = (chests.getValue() && (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity || be instanceof BarrelBlockEntity))
                             || (shulkers.getValue() && be instanceof ShulkerBoxBlockEntity)
                             || (droppers.getValue() && (be instanceof DropperBlockEntity || be instanceof DispenserBlockEntity));
                if (valid) {
                    RenderUtils.drawEspBox(matrices, new AABB(be.getBlockPos()), mode.getValue(), r, g, b, oA, fA);
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
        public void onWorldRender(PoseStack matrices) {
            float r = red.getValue().floatValue() / 255f;
            float g = green.getValue().floatValue() / 255f;
            float b = blue.getValue().floatValue() / 255f;
            float oA = outlineAlpha.getValue().floatValue() / 255f;
            float fA = fillAlpha.getValue().floatValue() / 255f;

            for (BlockEntity be : RenderUtils.getLoadedBlockEntities()) {
                if (be instanceof SpawnerBlockEntity) {
                    RenderUtils.drawEspBox(matrices, new AABB(be.getBlockPos()), mode.getValue(), r, g, b, oA, fA);
                }
            }
        }
    }

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

        public static void drawEspBox(PoseStack matrices, AABB box, String mode, float r, float g, float b, float outlineAlpha, float fillAlpha) {
            Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
            AABB shifted = box.move(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            matrices.pushPose();
            Matrix4f matrix = matrices.last().pose();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            if (mode.equals("Fill") || mode.equals("Both")) {
                drawFilledBox(matrix, shifted, r, g, b, fillAlpha);
            }
            if (mode.equals("Outline") || mode.equals("Both")) {
                drawBoxOutline(matrix, shifted, r, g, b, outlineAlpha);
            }

            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            matrices.popPose();
        }

        private static void drawFilledBox(Matrix4f matrix, AABB b, float red, float green, float blue, float alpha) {
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            float minX = (float) b.minX, minY = (float) b.minY, minZ = (float) b.minZ;
            float maxX = (float) b.maxX, maxY = (float) b.maxY, maxZ = (float) b.maxZ;

            buffer.addVertex(matrix, minX, minY, minZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, minX, minY, maxZ).setColor(red, green, blue, alpha);

            buffer.addVertex(matrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);

            buffer.addVertex(matrix, minX, minY, minZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, minY, minZ).setColor(red, green, blue, alpha);

            buffer.addVertex(matrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);

            buffer.addVertex(matrix, minX, minY, minZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

            buffer.addVertex(matrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
            buffer.addVertex(matrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);

            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }

        private static void drawBoxOutline(Matrix4f matrix, AABB b, float red, float green, float blue, float alpha) {
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            float minX = (float) b.minX, minY = (float) b.minY, minZ = (float) b.minZ;
            float maxX = (float) b.maxX, maxY = (float) b.maxY, maxZ = (float) b.maxZ;

            line(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, red, green, blue, alpha);
            line(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue, alpha);
            line(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
            line(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, red, green, blue, alpha);

            line(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
            line(buffer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue, alpha);
            line(buffer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
            line(buffer, matrix, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue, alpha);

            line(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, red, green, blue, alpha);
            line(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
            line(buffer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
            line(buffer, matrix, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);

            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }

        private static void line(BufferBuilder buf, Matrix4f mat, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
            buf.addVertex(mat, x1, y1, z1).setColor(r, g, b, a);
            buf.addVertex(mat, x2, y2, z2).setColor(r, g, b, a);
        }
    }

    public static class ClickGuiScreen extends Screen {
        private final List<Module> modules;
        private NumberSetting draggingSlider = null;
        private final int panelX = 40, panelY = 40, panelWidth = 170;

        public ClickGuiScreen(List<Module> modules) {
            super(Component.literal("ClickGUI"));
            this.modules = modules;
        }

        @Override
        public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
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

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int currentY = panelY + 20;
            for (Module mod : modules) {
                if (isHovered(mouseX, mouseY, panelX, currentY, panelWidth, 18)) {
                    if (button == 0) mod.toggle();
                    if (button == 1) mod.setExpanded(!mod.isExpanded());
                    return true;
                }
                currentY += 18;

                if (mod.isExpanded()) {
                    for (Setting<?> setting : mod.getSettings()) {
                        if (isHovered(mouseX, mouseY, panelX, currentY, panelWidth, 16) && button == 0) {
                            if (setting instanceof BooleanSetting b) b.toggle();
                            else if (setting instanceof ModeSetting m) m.cycle();
                            else if (setting instanceof NumberSetting n) draggingSlider = n;
                            return true;
                        }
                        currentY += 16;
                    }
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            draggingSlider = null;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean isPauseScreen() { return false; }

        private boolean isHovered(double mx, double my, int x, int y, int w, int h) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }
}
