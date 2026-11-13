package red.jackf.chesttracker.scan;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChestTrackerScan implements ModInitializer {
    public static final String ID = "chesttracker_scan";
    private static final Logger LOGGER = LoggerFactory.getLogger(ID);
    /** A batch is at most 64 containers, but nested shulker boxes and books can make one very large. */
    private static final int MAX_BATCH_BYTES = 32 * 1024 * 1024;

    private static final long SCHEDULE_CHECK_SECONDS = 15;

    private static @Nullable ContainerScan running = null;
    private static @Nullable ScheduledExecutorService scheduler = null;
    private static long lastCheckedMinute = -1;

    @Override
    public void onInitialize() {
        ScanConfig.createIfMissing();

        PayloadTypeRegistry.clientboundPlay().registerLarge(ScanPayloads.Batch.TYPE, ScanPayloads.Batch.CODEC, MAX_BATCH_BYTES);
        PayloadTypeRegistry.clientboundPlay().register(ScanPayloads.Finished.TYPE, ScanPayloads.Finished.CODEC);

        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
                Commands.literal("chesttracker").then(Commands.literal("scan")
                        .executes(command -> start(command, false))
                        .then(Commands.literal("new")
                                .requires(ChestTrackerScan::canReadWorld)
                                .executes(command -> start(command, true))))));

        ServerLifecycleEvents.SERVER_STARTED.register(ChestTrackerScan::startScheduler);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (scheduler != null) scheduler.shutdownNow();
            scheduler = null;
            if (running != null) running.cancel();
            running = null;
        });
    }

    /** Reading the whole world is heavy on the server, so only operators and the owner of a singleplayer world may start it. */
    private static boolean canReadWorld(CommandSourceStack source) {
        if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) return true;
        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
    }

    private static int start(CommandContext<CommandSourceStack> context, boolean forceNew) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();

        if (running != null) {
            source.sendFailure(Component.literal("Já existe uma varredura em andamento."));
            return 0;
        }
        if (!ServerPlayNetworking.canSend(player, ScanPayloads.Batch.TYPE)) {
            source.sendFailure(Component.literal("Seu cliente precisa do ChestTrackerMelhor para receber a varredura."));
            return 0;
        }

        ScanCache.Reader saved = forceNew ? null : ScanCache.open(server);
        if (saved == null && !canReadWorld(source)) {
            source.sendFailure(Component.literal("Ainda não há lista salva. Peça a um operador para rodar /chesttracker scan new."));
            return 0;
        }

        if (saved != null) {
            running = ContainerScan.fromSavedFile(server, List.of(player), saved, false, ignored -> running = null);
        } else {
            source.sendSuccess(() -> Component.literal(forceNew
                    ? "Varredura completa iniciada. Você pode continuar jogando."
                    : "Ainda não há arquivo salvo, então vou fazer uma varredura completa. Você pode continuar jogando."), false);
            startWorldScan(server, player);
            return 1;
        }
        running.start();
        return 1;
    }

    /** @param player who asked for it, or null for a scheduled scan */
    private static void startWorldScan(MinecraftServer server, @Nullable ServerPlayer player) {
        long startedAt = System.currentTimeMillis();
        // Same as /save-all flush. An autosave would not do: it skips chunks saved in the last few seconds,
        // and the scan only sees what is in the region data.
        server.saveAllChunks(true, true, false);
        running = ContainerScan.ofWorld(server, player, startedAt, succeeded -> {
            running = null;
            if (succeeded) shareWithOtherPlayers(server, player);
        });
        running.start();
    }

    /**
     * Watches the clock on a thread of its own. A dedicated server stops ticking after a minute with nobody online,
     * so a tick-based check would never fire then; tasks handed to the server still run while it is paused.
     * Only on dedicated servers: in singleplayer this would run in whichever world happened to be open.
     */
    private static void startScheduler(MinecraftServer server) {
        if (!server.isDedicatedServer()) return;
        lastCheckedMinute = -1;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Chest Tracker schedule");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(() -> checkSchedule(server), 1, SCHEDULE_CHECK_SECONDS, TimeUnit.SECONDS);
    }

    private static void checkSchedule(MinecraftServer server) {
        // An exception escaping here would stop every later check.
        try {
            long minute = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis());
            if (minute == lastCheckedMinute) return;
            lastCheckedMinute = minute;

            ScanConfig.Schedule schedule = ScanConfig.schedule();
            if (!schedule.times().contains(LocalTime.now(schedule.zone()).truncatedTo(ChronoUnit.MINUTES))) return;

            server.execute(() -> {
                if (running != null) {
                    LOGGER.warn("Skipping the scheduled world scan: another scan is running");
                    return;
                }
                LOGGER.info("Starting the scheduled world scan, {} players online", server.getPlayerCount());
                startWorldScan(server, null);
            });
        } catch (Exception e) {
            LOGGER.warn("Could not check the scan schedule", e);
        }
    }

    /** Brings everyone else online up to date with the scan that just finished, from the file it wrote. */
    private static void shareWithOtherPlayers(MinecraftServer server, @Nullable ServerPlayer scanner) {
        List<ServerPlayer> others = server.getPlayerList().getPlayers().stream()
                .filter(other -> scanner == null || !other.getUUID().equals(scanner.getUUID()))
                .filter(other -> ServerPlayNetworking.canSend(other, ScanPayloads.Batch.TYPE))
                .toList();
        if (others.isEmpty()) return;

        ScanCache.Reader saved = ScanCache.open(server);
        if (saved == null) return;
        running = ContainerScan.fromSavedFile(server, others, saved, true, ignored -> running = null);
        running.start();
    }
}
