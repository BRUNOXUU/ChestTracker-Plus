package red.jackf.chesttracker.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import com.mojang.blaze3d.platform.InputConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.CommonKeys;
import red.jackf.chesttracker.api.providers.MemoryBuilder;
import red.jackf.chesttracker.impl.gui.screen.ChestTrackerScreen;
import red.jackf.chesttracker.impl.gui.widget.ContainerTagListWidget;
import red.jackf.chesttracker.impl.gui.widget.ItemListWidget;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.MemoryKeyImpl;
import red.jackf.chesttracker.impl.memory.metadata.IntegritySettings;
import red.jackf.chesttracker.impl.scan.ScanReceiver;
import red.jackf.chesttracker.impl.waypoint.WaypointRenderer;
import red.jackf.chesttracker.impl.waypoint.Waypoints;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ScanClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("chesttracker-test");
    private static final int SCAN_TIMEOUT_TICKS = 2400;
    private static final int PAUSE_WHEN_EMPTY_SECONDS = 2;
    private static final Identifier NETHER = Level.NETHER.identifier();
    private static final Identifier END = Level.END.identifier();

    @Override
    public void runTest(ClientGameTestContext context) {
        // test windows stay silent; this only touches the options of the test run folder
        context.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0));
        if (System.getenv("CTM_GALLERY") != null) {
            GalleryShots.run(context, Path.of(System.getenv("CTM_GALLERY")));
            return;
        }
        LOGGER.info("RESULT singleplayer start");
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runOnServer(ScanClientTest::placeContainers);
            prepareBank(context, "ctm-test-singleplayer");

            verifyConfigFile();
            scanAndWait(context, "chesttracker scan");
            verifyMemories(context);
            verifyScreen(context);
            verifyWaypoints(context, world.getServer());
            verifySavedFileFlow(context, world.getServer());
        }
        LOGGER.info("RESULT singleplayer OK");

        LOGGER.info("RESULT dedicated server start");
        Properties serverProperties = new Properties();
        // pause soon after the player leaves, to check that the scheduled scan runs on an empty server
        serverProperties.setProperty("pause-when-empty-seconds", String.valueOf(PAUSE_WHEN_EMPTY_SECONDS));
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(serverProperties)) {
            server.runOnServer(ScanClientTest::placeContainers);
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                context.waitTicks(20);
                prepareBank(context, "ctm-test-server");

                // A regular player can neither read the world directly nor make the plain command do it.
                assertNothingHappens(context, "chesttracker scan new");
                assertNothingHappens(context, "chesttracker scan");

                setOperator(context, server, true);
                scanAndWait(context, "chesttracker scan new");
                verifyMemories(context);
                verifySavedFileFlow(context, server);

                // Once the file exists, a regular player can load it.
                setOperator(context, server, false);
                scanAndWait(context, "chesttracker scan");
                LOGGER.info("RESULT regular player loaded the file");

                verifyScheduledScan(context, server);
            }

            verifyScheduledScanWhileEmpty(context, server);
        }
        LOGGER.info("RESULT dedicated server OK");
    }

    private static void assertNothingHappens(ClientGameTestContext context, String command) {
        int before = ScanReceiver.finishedScans;
        context.runOnClient(mc -> mc.getConnection().sendCommand(command));
        context.waitTicks(40);
        check(ScanReceiver.finishedScans == before, "'" + command + "' should have been refused");
    }

    private static void setOperator(ClientGameTestContext context, TestServerContext server, boolean operator) {
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            if (operator) s.getPlayerList().op(player.nameAndId());
            else s.getPlayerList().deop(player.nameAndId());
        });
        context.waitTicks(20);
    }

    private static void prepareBank(ClientGameTestContext context, String id) {
        context.runOnClient(mc -> {
            MemoryBankAccessImpl.INSTANCE.loadOrCreate(id, id);
            // Banks created by earlier versions still hold the old 12 hour default.
            MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow().getMetadata().getIntegritySettings().memoryLifetime = IntegritySettings.MemoryLifetime.TWELVE_HOURS;
        });
    }

    /** With nobody online the server stops ticking; the scheduled scan must still update the saved file. */
    private static void verifyScheduledScanWhileEmpty(ClientGameTestContext context, TestDedicatedServerContext server) {
        BlockPos scheduled = new BlockPos(26, 70, 2);
        server.runOnServer(s -> place(s.overworld(), scheduled, Blocks.CHEST.defaultBlockState(), null, Items.PRISMARINE_SHARD, 7));

        Path savedFile = server.computeOnServer(s -> s.getWorldPath(LevelResource.ROOT).resolve("chesttrackermelhor").resolve("containers.dat"));
        long before = lastModified(savedFile);
        // well past the pause, so the scan has to run on a paused server
        context.waitTicks((PAUSE_WHEN_EMPTY_SECONDS + 3) * 20);

        String original = scheduleScanSoon();
        context.waitFor(mc -> lastModified(savedFile) > before, SCAN_TIMEOUT_TICKS * 2);
        restoreSettings(original);
        LOGGER.info("RESULT empty server wrote the saved file");

        try (TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            context.waitTicks(20);
            prepareBank(context, "ctm-test-server");
            scanAndWait(context, "chesttracker scan");
            context.runOnClient(mc -> {
                MemoryKeyImpl overworld = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow().getKeyInternal(CommonKeys.OVERWORLD).orElseThrow();
                expect(overworld, scheduled, null, Map.of(Items.PRISMARINE_SHARD, 7));
            });
        }
        LOGGER.info("RESULT scheduled scan while empty OK");
    }

    private static long lastModified(Path file) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Sets the schedule to the next two minutes. @return the settings to put back */
    private static String scheduleScanSoon() {
        Path config = FabricLoader.getInstance().getConfigDir().resolve("chesttrackermelhor").resolve("servidor.properties");
        try {
            String original = Files.readString(config);
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
            DateTimeFormatter format = DateTimeFormatter.ofPattern("HH:mm");
            String times = "horarios_scan=" + format.format(now.plusMinutes(1)) + "," + format.format(now.plusMinutes(2));
            Files.writeString(config, original.replaceFirst("(?m)^horarios_scan=.*$", times));
            return original;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void restoreSettings(String original) {
        try {
            Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("chesttrackermelhor").resolve("servidor.properties"), original);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Runs with no operator online: the server starts this scan by itself and updates whoever is connected. */
    private static void verifyScheduledScan(ClientGameTestContext context, TestServerContext server) {
        BlockPos scheduled = new BlockPos(24, 70, 2);
        server.runOnServer(s -> place(s.overworld(), scheduled, Blocks.CHEST.defaultBlockState(), null, Items.LAPIS_LAZULI, 12));

        Path config = FabricLoader.getInstance().getConfigDir().resolve("chesttrackermelhor").resolve("servidor.properties");
        String original;
        try {
            original = Files.readString(config);
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
            DateTimeFormatter format = DateTimeFormatter.ofPattern("HH:mm");
            String times = "horarios_scan=" + format.format(now.plusMinutes(1)) + "," + format.format(now.plusMinutes(2));
            Files.writeString(config, original.replaceFirst("(?m)^horarios_scan=.*$", times));
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        int before = ScanReceiver.finishedScans;
        context.waitFor(mc -> ScanReceiver.finishedScans > before, SCAN_TIMEOUT_TICKS * 2);

        try {
            Files.writeString(config, original);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        context.waitTicks(5);
        context.runOnClient(mc -> {
            MemoryKeyImpl overworld = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow().getKeyInternal(CommonKeys.OVERWORLD).orElseThrow();
            expect(overworld, scheduled, null, Map.of(Items.LAPIS_LAZULI, 12));
        });
        LOGGER.info("RESULT scheduled scan OK");
    }

    private static void verifyConfigFile() {
        Path config = FabricLoader.getInstance().getConfigDir().resolve("chesttrackermelhor").resolve("servidor.properties");
        check(Files.exists(config), "config file missing: " + config);
        try {
            String settings = Files.readString(config);
            check(settings.contains("nucleos=2"), "default should be 2 cores");
            check(settings.contains("horarios_scan="), "the schedule setting should be added to existing files");
            check(settings.contains("horarios_scan=03:00"), "new settings should scan at 3 in the morning");
            check(settings.contains("fuso_horario=America/Sao_Paulo"), "the time zone setting should be added to existing files");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Runs after a full scan of the containers from {@link #placeContainers}. Changes the world, so it goes last. */
    private static void verifySavedFileFlow(ClientGameTestContext context, TestServerContext server) {
        BlockPos barrel = new BlockPos(8, 70, 2);
        BlockPos cobblestone = new BlockPos(2, 70, 2);
        BlockPos added = new BlockPos(20, 70, 2);

        check(server.computeOnServer(s -> Files.exists(s.getWorldPath(LevelResource.ROOT).resolve("chesttrackermelhor").resolve("containers.dat"))), "saved file missing");

        // The world changes, but the plain command must answer from the file.
        server.runOnServer(s -> {
            ServerLevel level = s.overworld();
            level.removeBlock(barrel, false);
            ((Container) level.getBlockEntity(cobblestone)).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
            place(level, added, Blocks.CHEST.defaultBlockState(), "BLOCOS", Items.EMERALD, 6);
        });

        // Something this player saw after the file was made has to survive loading the file.
        BlockPos seenLater = new BlockPos(14, 70, 2);
        context.waitTicks(5);
        context.runOnClient(mc -> MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow().addMemory(CommonKeys.OVERWORLD, seenLater,
                MemoryBuilder.create(List.of(new ItemStack(Items.GLOWSTONE, 8))).inContainer(Blocks.SHULKER_BOX).build()));

        scanAndWait(context, "chesttracker scan");
        context.runOnClient(mc -> {
            MemoryKeyImpl overworld = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow().getKeyInternal(CommonKeys.OVERWORLD).orElseThrow();
            expect(overworld, barrel, "Comida", Map.of(Items.APPLE, 3));
            expect(overworld, cobblestone, "BLOCOS", Map.of(Items.COBBLESTONE, 64));
            expect(overworld, seenLater, null, Map.of(Items.GLOWSTONE, 8));
            check(overworld.get(added).isEmpty(), "the plain command must not read the world");
        });

        scanAndWait(context, "chesttracker scan new");
        context.runOnClient(mc -> {
            MemoryKeyImpl overworld = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow().getKeyInternal(CommonKeys.OVERWORLD).orElseThrow();
            check(overworld.get(barrel).isEmpty(), "a container that no longer exists must be removed");
            expect(overworld, cobblestone, "BLOCOS", Map.of(Items.COBBLESTONE, 1));
            expect(overworld, seenLater, null, Map.of(Items.REDSTONE, 4));
            expect(overworld, added, "BLOCOS", Map.of(Items.EMERALD, 6));
        });

        // The file now holds the new state.
        scanAndWait(context, "chesttracker scan");
        context.runOnClient(mc -> {
            MemoryKeyImpl overworld = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow().getKeyInternal(CommonKeys.OVERWORLD).orElseThrow();
            expect(overworld, added, "BLOCOS", Map.of(Items.EMERALD, 6));
        });

        // A memory older than the file, of a container the file doesn't have, was broken in the meantime.
        // One newer than the file is something this player saw afterwards.
        BlockPos stale = new BlockPos(30, 70, 2);
        BlockPos recent = new BlockPos(31, 70, 2);
        context.runOnClient(mc -> {
            MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow();
            bank.addMemory(CommonKeys.OVERWORLD, stale, MemoryBuilder.create(List.of(new ItemStack(Items.FLINT, 1))).inContainer(Blocks.CHEST).build());
            bank.addMemory(CommonKeys.OVERWORLD, recent, MemoryBuilder.create(List.of(new ItemStack(Items.CLAY_BALL, 1))).inContainer(Blocks.CHEST).build());
            setRealTimestamp(bank.getKeyInternal(CommonKeys.OVERWORLD).orElseThrow().getMemories().get(stale), Instant.EPOCH);
        });
        scanAndWait(context, "chesttracker scan");
        context.runOnClient(mc -> {
            MemoryKeyImpl overworld = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow().getKeyInternal(CommonKeys.OVERWORLD).orElseThrow();
            check(overworld.get(stale).isEmpty(), "loading the file must remove containers it no longer has");
            check(overworld.get(recent).isPresent(), "loading the file must keep what the player saw after it was made");
        });
        LOGGER.info("RESULT saved file flow OK");
    }

    private static void scanAndWait(ClientGameTestContext context, String command) {
        int before = ScanReceiver.finishedScans;
        context.runOnClient(mc -> mc.getConnection().sendCommand(command));
        context.waitFor(mc -> ScanReceiver.finishedScans > before, SCAN_TIMEOUT_TICKS);
        context.waitTicks(5);
    }

    private static void placeContainers(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(Level.NETHER);
        ServerLevel end = server.getLevel(Level.END);

        place(overworld, new BlockPos(2, 70, 2), Blocks.CHEST.defaultBlockState(), "BLOCOS", Items.COBBLESTONE, 64);

        // A double chest: only the right half has the name, as happens when one half is renamed.
        BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT);
        place(overworld, new BlockPos(4, 70, 2), left, null, Items.STONE, 5);
        place(overworld, new BlockPos(5, 70, 2), right, "Blocos", Items.DIRT, 10);

        place(overworld, new BlockPos(8, 70, 2), Blocks.BARREL.defaultBlockState(), "Comida", Items.APPLE, 3);
        place(overworld, new BlockPos(10, 70, 2), Blocks.COPPER_CHEST.weathering().unaffected().defaultBlockState(), null, Items.GOLD_INGOT, 7);
        place(overworld, new BlockPos(14, 70, 2), Blocks.SHULKER_BOX.defaultBlockState(), null, Items.REDSTONE, 4);

        // A loot chest has no items until someone opens it.
        BlockPos lootPos = new BlockPos(12, 70, 2);
        overworld.setBlockAndUpdate(lootPos, Blocks.CHEST.defaultBlockState());
        ((RandomizableContainerBlockEntity) overworld.getBlockEntity(lootPos)).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 1L);

        // Far from every player, so these are only reachable through the saved world.
        place(overworld, new BlockPos(5000, 70, 5000), Blocks.CHEST.defaultBlockState(), "  blocos ", Items.DIAMOND, 2);
        place(nether, new BlockPos(1000, 70, 1000), Blocks.CHEST.defaultBlockState(), "Nether", Items.QUARTZ, 9);
        place(end, new BlockPos(500, 70, 500), Blocks.CHEST.defaultBlockState(), "Fim", Items.END_STONE, 11);
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state, String name, Item item, int count) {
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        var blockEntity = (BaseContainerBlockEntity) level.getBlockEntity(pos);
        if (name != null) {
            blockEntity.applyComponents(DataComponentMap.builder().set(DataComponents.CUSTOM_NAME, Component.literal(name)).build(), DataComponentPatch.EMPTY);
        }
        ((Container) blockEntity).setItem(0, new ItemStack(item, count));
        blockEntity.setChanged();
    }

    private static void verifyMemories(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElseThrow();
            MemoryKeyImpl overworld = bank.getKeyInternal(CommonKeys.OVERWORLD).orElseThrow(() -> new AssertionError("overworld not scanned"));

            LOGGER.info("RESULT overworld memories: {}", overworld.getMemories().keySet());
            check(overworld.getMemories().size() == 6, "expected 6 overworld containers, got " + overworld.getMemories().size());
            expect(overworld, new BlockPos(2, 70, 2), "BLOCOS", Map.of(Items.COBBLESTONE, 64));
            expect(overworld, new BlockPos(4, 70, 2), "Blocos", Map.of(Items.STONE, 5, Items.DIRT, 10));
            check(overworld.get(new BlockPos(5, 70, 2)).isPresent(), "right half must resolve to the double chest");
            expect(overworld, new BlockPos(8, 70, 2), "Comida", Map.of(Items.APPLE, 3));
            expect(overworld, new BlockPos(10, 70, 2), null, Map.of(Items.GOLD_INGOT, 7));
            expect(overworld, new BlockPos(14, 70, 2), null, Map.of(Items.REDSTONE, 4));
            check(overworld.get(new BlockPos(12, 70, 2)).isEmpty(), "unopened loot chest must be skipped");
            expect(overworld, new BlockPos(5000, 70, 5000), "  blocos ", Map.of(Items.DIAMOND, 2));

            expect(bank.getKeyInternal(NETHER).orElseThrow(() -> new AssertionError("nether not scanned")), new BlockPos(1000, 70, 1000), "Nether", Map.of(Items.QUARTZ, 9));
            expect(bank.getKeyInternal(END).orElseThrow(() -> new AssertionError("end not scanned")), new BlockPos(500, 70, 500), "Fim", Map.of(Items.END_STONE, 11));
            check(bank.getMetadata().getSearchSettings().itemListRange == Integer.MAX_VALUE, "item list range should be infinite after a scan");
            check(bank.getMetadata().getIntegritySettings().memoryLifetime == IntegritySettings.MemoryLifetime.TEN_DAYS, "memories should last 10 days after a scan");
        });
    }

    private static void expect(MemoryKeyImpl key, BlockPos pos, String name, Map<Item, Integer> items) {
        Memory memory = key.getMemories().get(pos);
        if (memory == null) {
            // The root of a double chest is an implementation detail; accept either half.
            memory = key.get(pos).orElseThrow(() -> new AssertionError("nothing remembered at " + pos));
        }
        String savedName = memory.savedName() == null ? null : memory.savedName().getString();
        check(java.util.Objects.equals(savedName, name), "name at " + pos + ": expected " + name + ", got " + savedName);
        Map<Item, Integer> actual = memory.items().stream().collect(Collectors.toMap(ItemStack::getItem, ItemStack::getCount, Integer::sum));
        check(actual.equals(items), "items at " + pos + ": expected " + items + ", got " + actual);
    }

    private static void verifyWaypoints(ClientGameTestContext context, TestServerContext server) {
        BlockPos barrel = new BlockPos(8, 70, 2);
        BlockPos farChest = new BlockPos(5000, 70, 5000);

        context.runOnClient(mc -> {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
        });
        server.runCommand("tp @p 8 71 -6 0 0");
        context.waitTicks(5);
        // otherwise the player falls below the base of the beam before the screenshots
        context.runOnClient(mc -> {
            mc.player.setNoGravity(true);
            mc.player.setDeltaMovement(Vec3.ZERO);
        });
        context.waitTicks(5);

        context.setScreen(() -> new ChestTrackerScreen(null));
        context.waitForScreen(ChestTrackerScreen.class);
        context.waitTicks(5);
        ContainerTagListWidget tags = tagList(context);
        context.runOnClient(mc -> tags.select("comida"));
        context.waitTicks(5);

        // Hovering shows the distance, 8 and a bit blocks from the eyes.
        context.runOnClient(mc -> {
            ItemListWidget list = itemList(mc.gui.screen());
            ItemStack listed = list.stackAt(list.getX() + 9, list.getY() + 9).orElseThrow();
            var lines = (List<Component>) call(mc.gui.screen(), "distanceTooltip", ItemStack.class, listed);
            LOGGER.info("RESULT distance tooltip: {}", lines.stream().map(Component::getString).toList());
            String expected = String.valueOf(Math.round(Math.sqrt(barrel.distToCenterSqr(mc.player.getEyePosition()))));
            LOGGER.info("RESULT player at {}, expected {}", mc.player.position(), expected);
            check(lines.size() == 1 && lines.getFirst().getString().contains(expected), "tooltip should show the distance: " + lines);
        });
        context.takeScreenshot("chesttracker-selected-container");

        // Right click, then click the option.
        rightClickFirstItem(context);
        context.takeScreenshot("chesttracker-waypoint-menu");
        clickMenu(context);
        context.runOnClient(mc -> check(Waypoints.has(CommonKeys.OVERWORLD, barrel), "waypoint should be set"));
        check(Files.exists(FabricLoader.getInstance().getConfigDir().resolve("chesttrackermelhor").resolve("waypoints.json")), "waypoints should be saved");

        // Right click again removes it.
        rightClickFirstItem(context);
        clickMenu(context);
        context.runOnClient(mc -> check(!Waypoints.has(CommonKeys.OVERWORLD, barrel), "waypoint should be removed"));

        // A normal left click lights up the container, with no waypoint involved.
        context.runOnClient(mc -> {
            ItemListWidget list = itemList(mc.gui.screen());
            mc.gui.screen().mouseClicked(new MouseButtonEvent(list.getX() + 9, list.getY() + 9, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            check(WaypointRenderer.clickedContainerCount() == 1, "left click should light up the barrel");
            check(!Waypoints.has(CommonKeys.OVERWORLD, barrel), "left click must not create a waypoint");
        });
        context.setScreen(() -> null);
        context.waitTicks(5);
        context.takeScreenshot("chesttracker-left-click-highlight");
        context.setScreen(() -> new ChestTrackerScreen(null));
        context.waitForScreen(ChestTrackerScreen.class);
        context.waitTicks(5);

        rightClickFirstItem(context);
        clickMenu(context);
        context.setScreen(() -> null);
        context.waitTicks(10);
        context.runOnClient(mc -> LOGGER.info("RESULT beam shot from {} rot {} renderDistance {} waypoints {}", mc.player.position(), mc.player.getYRot(), mc.options.getEffectiveRenderDistance(), Waypoints.active()));
        context.takeScreenshot("chesttracker-waypoint-beam");
        server.runCommand("fill 6 69 -1 10 73 -1 minecraft:stone");
        context.waitTicks(20);
        context.takeScreenshot("chesttracker-waypoint-through-wall");
        server.runCommand("fill 6 69 -1 10 73 -1 minecraft:air");
        context.waitTicks(5);

        // Far past the render distance, the beam still shows the way.
        context.runOnClient(mc -> {
            Waypoints.add(CommonKeys.OVERWORLD, farChest, List.of());
            mc.player.setYRot(-45f);
            mc.player.yRotO = -45f;
        });
        context.waitTicks(10);
        context.takeScreenshot("chesttracker-waypoint-far-beam");
        context.runOnClient(mc -> Waypoints.removeAt(CommonKeys.OVERWORLD, farChest));

        // Opening the container clears it.
        server.runCommand("tp @p 8 71 0 0 90");
        context.waitTicks(10);
        context.runOnClient(mc -> {
            var hit = new BlockHitResult(Vec3.atCenterOf(barrel), Direction.UP, barrel, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        });
        context.waitForScreen(AbstractContainerScreen.class);
        context.waitTicks(5);
        context.runOnClient(mc -> check(!Waypoints.has(CommonKeys.OVERWORLD, barrel), "opening the container should clear its waypoint"));
        context.setScreen(() -> null);
        context.waitTicks(5);

        // The clear button takes them all, including those in other dimensions.
        context.runOnClient(mc -> {
            Waypoints.add(CommonKeys.OVERWORLD, barrel, List.of());
            Waypoints.add(CommonKeys.OVERWORLD, farChest, List.of());
            Waypoints.add(NETHER, new BlockPos(1000, 70, 1000), List.of());
        });
        context.setScreen(() -> new ChestTrackerScreen(null));
        context.waitForScreen(ChestTrackerScreen.class);
        context.waitTicks(5);
        context.takeScreenshot("chesttracker-clear-waypoints");
        context.clickScreenButton("chesttracker.gui.waypoint.clearAll");
        context.waitTicks(2);
        context.runOnClient(mc -> check(Waypoints.active().isEmpty(), "the button should clear every waypoint: " + Waypoints.active()));
        context.setScreen(() -> null);
        context.waitTicks(5);
        LOGGER.info("RESULT waypoints OK");
    }

    private static void rightClickFirstItem(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            ItemListWidget list = itemList(mc.gui.screen());
            mc.gui.screen().mouseClicked(new MouseButtonEvent(list.getX() + 9, list.getY() + 9, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0)), false);
        });
        context.waitTicks(2);
    }

    private static void clickMenu(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            ItemListWidget list = itemList(mc.gui.screen());
            mc.gui.screen().mouseClicked(new MouseButtonEvent(list.getX() + 11, list.getY() + 11, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
        });
        context.waitTicks(2);
    }

    private static ItemListWidget itemList(Object screen) {
        return ((ChestTrackerScreen) screen).children().stream().filter(ItemListWidget.class::isInstance).map(ItemListWidget.class::cast).findFirst().orElseThrow();
    }

    private static ContainerTagListWidget tagList(ClientGameTestContext context) {
        return context.computeOnClient(mc -> mc.gui.screen().children().stream()
                .filter(ContainerTagListWidget.class::isInstance).map(ContainerTagListWidget.class::cast).findFirst().orElseThrow());
    }

    private static Object call(Object target, String method, Class<?> parameter, Object argument) {
        try {
            var declared = target.getClass().getDeclaredMethod(method, parameter);
            declared.setAccessible(true);
            return declared.invoke(target, argument);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void verifyScreen(ClientGameTestContext context) {
        context.setScreen(() -> new ChestTrackerScreen(null));
        context.waitForScreen(ChestTrackerScreen.class);
        context.waitTicks(5);
        context.takeScreenshot("chesttracker-all");

        ContainerTagListWidget tags = context.computeOnClient(mc -> mc.gui.screen().children().stream()
                .filter(ContainerTagListWidget.class::isInstance).map(ContainerTagListWidget.class::cast).findFirst().orElseThrow());

        context.runOnClient(mc -> {
            LOGGER.info("RESULT tag rows: {}", tags.getRows());
            var blocos = tags.getRows().stream().filter(row -> "blocos".equals(row.tag())).findFirst()
                    .orElseThrow(() -> new AssertionError("no BLOCOS group: " + tags.getRows()));
            check(blocos.count() == 3, "BLOCOS should link 3 containers, got " + blocos.count());
            check(tags.getRows().stream().anyMatch(row -> "comida".equals(row.tag())), "no Comida group");

            tags.select("blocos");
        });
        context.waitTicks(5);
        context.takeScreenshot("chesttracker-blocos");

        context.runOnClient(mc -> {
            Map<Item, Integer> shown = listedItems(mc.gui.screen());
            LOGGER.info("RESULT BLOCOS items: {}", shown);
            check(shown.equals(Map.of(Items.COBBLESTONE, 64, Items.STONE, 5, Items.DIRT, 10, Items.DIAMOND, 2)), "BLOCOS shows the wrong items: " + shown);

            tags.select("comida");
            shown = listedItems(mc.gui.screen());
            check(shown.equals(Map.of(Items.APPLE, 3)), "Comida shows the wrong items: " + shown);

            tags.select(null);
            check(listedItems(mc.gui.screen()).size() >= 6, "'all' should show everything again");
        });
        context.setScreen(() -> null);
    }

    private static void setRealTimestamp(Memory memory, Instant time) {
        try {
            Field field = Memory.class.getDeclaredField("realTimestamp");
            field.setAccessible(true);
            field.set(memory, time);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Item, Integer> listedItems(Object screen) {
        try {
            Field items = ChestTrackerScreen.class.getDeclaredField("items");
            items.setAccessible(true);
            return ((List<ItemStack>) items.get(screen)).stream()
                    .collect(Collectors.toMap(ItemStack::getItem, ItemStack::getCount, Integer::sum));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
