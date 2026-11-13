package red.jackf.chesttracker.test;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import red.jackf.chesttracker.api.memory.CommonKeys;
import red.jackf.chesttracker.impl.gui.screen.ChestTrackerScreen;
import red.jackf.chesttracker.impl.gui.widget.ContainerTagListWidget;
import red.jackf.chesttracker.impl.gui.widget.ItemListWidget;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.scan.ScanReceiver;
import red.jackf.chesttracker.impl.waypoint.Waypoints;

import java.nio.file.Path;
import java.util.List;

/**
 * Screenshots for the Modrinth gallery, taken when the CTM_GALLERY environment variable names an output folder.
 * Not a test: it only stages scenes.
 */
final class GalleryShots {
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

    private GalleryShots() {
    }

    static void run(ClientGameTestContext context, Path output) {
        context.runOnClient(mc -> {
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc.options.chatVisibility().set(ChatVisiblity.HIDDEN);
        });

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            TestServerContext server = world.getServer();
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("gamerule doDaylightCycle false");

            int ground = server.computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 4));
            BlockPos blocksA = new BlockPos(0, ground, 4);
            BlockPos blocksB = new BlockPos(2, ground, 4);
            BlockPos food = new BlockPos(4, ground, 4);
            BlockPos oresA = new BlockPos(6, ground, 4);
            BlockPos oresB = new BlockPos(8, ground, 4);
            BlockPos buried = new BlockPos(20, ground, 4);

            server.runOnServer(s -> {
                ServerLevel level = s.overworld();
                place(level, blocksA, Blocks.CHEST.defaultBlockState(), "Blocks", Items.COBBLESTONE, 64);
                place(level, blocksB, Blocks.CHEST.defaultBlockState(), "Blocks", Items.COBBLESTONE, 48);
                place(level, food, Blocks.BARREL.defaultBlockState(), "Food", Items.BREAD, 32);
                place(level, oresA, Blocks.CHEST.defaultBlockState(), "Ores", Items.DIAMOND, 12);
                place(level, oresB, Blocks.CHEST.defaultBlockState(), "Ores", Items.GOLD_INGOT, 40);
                place(level, buried, Blocks.CHEST.defaultBlockState(), "Ores", Items.EMERALD, 9);
            });
            // bury one chest under a small stone hill
            server.runCommand("fill 16 %d 0 24 %d 8 minecraft:stone replace minecraft:air".formatted(ground, ground + 6));
            server.runCommand("setblock 20 %d 4 minecraft:air".formatted(ground + 1));

            context.runOnClient(mc -> MemoryBankAccessImpl.INSTANCE.loadOrCreate("gallery", "gallery"));
            int before = ScanReceiver.finishedScans;
            context.runOnClient(mc -> mc.getConnection().sendCommand("chesttracker scan new"));
            context.waitFor(mc -> ScanReceiver.finishedScans > before, 2400);
            context.waitTicks(20);

            // 1. the Containers panel, with a group selected and the distance in the tooltip
            server.runCommand("tp @p 4 %d -6 0 20".formatted(ground));
            context.waitTicks(20);
            context.setScreen(() -> new ChestTrackerScreen(null));
            context.waitForScreen(ChestTrackerScreen.class);
            context.waitTicks(5);
            ContainerTagListWidget tags = context.computeOnClient(mc -> mc.gui.screen().children().stream()
                    .filter(ContainerTagListWidget.class::isInstance).map(ContainerTagListWidget.class::cast).findFirst().orElseThrow());
            context.runOnClient(mc -> tags.select("ores"));
            context.waitTicks(5);
            hoverFirstItem(context);
            screenShot(context, output, "1-containers-panel");

            // 2. the right-click menu
            context.runOnClient(mc -> {
                ItemListWidget list = itemList(mc);
                mc.gui.screen().mouseClicked(new MouseButtonEvent(list.getX() + 9, list.getY() + 9,
                        new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0)), false);
            });
            context.waitTicks(3);
            screenShot(context, output, "2-waypoint-menu");
            context.setScreen(() -> null);
            context.waitTicks(5);

            context.runOnClient(mc -> hideHud(mc, true));

            // 3. a waypoint beam over a chest
            context.runOnClient(mc -> Waypoints.add(CommonKeys.OVERWORLD, oresA, List.of()));
            server.runCommand("tp @p 2 %d -5".formatted(ground));
            context.waitTicks(10);
            context.getInput().lookAt(oresA);
            context.runOnClient(mc -> mc.player.setXRot(mc.player.getXRot() - 22f));
            context.waitTicks(10);
            shot(context, output, "3-waypoint-beam");
            context.runOnClient(mc -> Waypoints.clear());

            // 4. a buried chest, seen through the hill
            context.runOnClient(mc -> Waypoints.add(CommonKeys.OVERWORLD, buried, List.of()));
            server.runCommand("tp @p 20 %d -9".formatted(ground));
            context.waitTicks(10);
            context.getInput().lookAt(buried);
            context.runOnClient(mc -> mc.player.setXRot(mc.player.getXRot() - 18f));
            context.waitTicks(10);
            shot(context, output, "4-buried-chest-waypoint");
            context.runOnClient(mc -> Waypoints.clear());

            // 5. a left click lights up every chest of the group
            context.runOnClient(mc -> hideHud(mc, false));
            server.runCommand("tp @p 4 %d -7".formatted(ground));
            context.waitTicks(10);
            context.setScreen(() -> new ChestTrackerScreen(null));
            context.waitForScreen(ChestTrackerScreen.class);
            context.waitTicks(5);
            context.runOnClient(mc -> tags.select(null));
            ContainerTagListWidget freshTags = context.computeOnClient(mc -> mc.gui.screen().children().stream()
                    .filter(ContainerTagListWidget.class::isInstance).map(ContainerTagListWidget.class::cast).findFirst().orElseThrow());
            context.runOnClient(mc -> freshTags.select("blocks"));
            context.waitTicks(5);
            context.runOnClient(mc -> {
                ItemListWidget list = itemList(mc);
                // every item of the group lights up its containers; click both
                mc.gui.screen().mouseClicked(new MouseButtonEvent(list.getX() + 9, list.getY() + 9,
                        new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            });
            context.setScreen(() -> null);
            context.runOnClient(mc -> hideHud(mc, true));
            context.waitTicks(5);
            context.getInput().lookAt(blocksB);
            context.runOnClient(mc -> mc.player.setXRot(mc.player.getXRot() - 10f));
            context.waitTicks(5);
            shot(context, output, "5-left-click-highlight");
            context.runOnClient(mc -> hideHud(mc, false));
        }
    }

    private static void hideHud(net.minecraft.client.Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
    }

    private static void hoverFirstItem(ClientGameTestContext context) {
        double[] cursor = context.computeOnClient(mc -> {
            ItemListWidget list = itemList(mc);
            double scale = mc.getWindow().getGuiScale();
            return new double[]{(list.getX() + 9) * scale, (list.getY() + 9) * scale};
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.waitTicks(3);
    }

    private static ItemListWidget itemList(net.minecraft.client.Minecraft mc) {
        return mc.gui.screen().children().stream().filter(ItemListWidget.class::isInstance).map(ItemListWidget.class::cast).findFirst().orElseThrow();
    }

    /** Screens are laid out for the window, so they are captured at its own size. */
    private static void screenShot(ClientGameTestContext context, Path output, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withDestinationDir(output).disableCounterPrefix());
    }

    private static void shot(ClientGameTestContext context, Path output, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(WIDTH, HEIGHT).withDestinationDir(output).disableCounterPrefix());
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state, String name, Item item, int count) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        var blockEntity = (BaseContainerBlockEntity) level.getBlockEntity(pos);
        blockEntity.applyComponents(DataComponentMap.builder().set(DataComponents.CUSTOM_NAME, Component.literal(name)).build(), DataComponentPatch.EMPTY);
        ((Container) blockEntity).setItem(0, new ItemStack(item, count));
        blockEntity.setChanged();
    }
}
