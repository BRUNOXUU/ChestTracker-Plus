package red.jackf.chesttracker.impl.scan;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.providers.MemoryBuilder;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.metadata.IntegritySettings;
import red.jackf.chesttracker.impl.memory.metadata.SearchSettings;
import red.jackf.chesttracker.impl.storage.Storage;
import red.jackf.chesttracker.scan.ScanPayloads;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores what {@code /chesttracker scan} finds into the loaded memory bank.
 */
public final class ScanReceiver {
    /** How many scans have completed since the game started. */
    public static volatile int finishedScans = 0;
    private static boolean droppedBatches = false;
    /** Every position reported by the scan in progress, by dimension. */
    private static final Map<Identifier, Set<BlockPos>> reported = new HashMap<>();

    private ScanReceiver() {
    }

    public static void setup() {
        ClientPlayNetworking.registerGlobalReceiver(ScanPayloads.Batch.TYPE, (payload, context) -> receive(payload));
        ClientPlayNetworking.registerGlobalReceiver(ScanPayloads.Finished.TYPE, (payload, context) -> finish(payload));
    }

    private static void receive(ScanPayloads.Batch batch) {
        MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null);
        if (bank == null) {
            droppedBatches = true;
            return;
        }

        var remembered = bank.getMetadata().getFilteringSettings().rememberedContainers.predicate;
        Set<BlockPos> reportedHere = reported.computeIfAbsent(batch.dimension(), ignored -> new HashSet<>());
        for (ScanPayloads.ScannedContainer container : batch.containers()) {
            reportedHere.add(container.pos());
            reportedHere.addAll(container.others());

            if (!remembered.test(container.block().defaultBlockState())) continue;
            if (keepNewerMemory(bank, batch, container)) continue;

            Memory memory = MemoryBuilder.create(container.items())
                    .withCustomName(container.name().orElse(null))
                    .otherPositions(container.others())
                    .inContainer(container.block())
                    .build();
            bank.addMemory(batch.dimension(), container.pos(), memory);
        }
    }

    /**
     * A saved file can be days old, so it must not undo what this player has seen since.
     *
     * @return whether the memory already held is newer than the saved file
     */
    private static boolean keepNewerMemory(MemoryBankImpl bank, ScanPayloads.Batch batch, ScanPayloads.ScannedContainer container) {
        if (batch.snapshotMillis() == 0L) return false;
        Memory existing = bank.getKeyInternal(batch.dimension()).flatMap(key -> key.get(container.pos())).orElse(null);
        if (existing == null || existing.realTimestamp().toEpochMilli() <= batch.snapshotMillis()) return false;

        var level = Minecraft.getInstance().level;
        if (level != null) existing.touch(bank.getMetadata().getLoadedTime(), level.getGameTime());
        return true;
    }

    /**
     * @param snapshotMillis when the scan read the world; anything this player saw later is left alone
     * @return how many memories were of containers that no longer exist
     */
    private static int removeMissingContainers(MemoryBankImpl bank, List<Identifier> dimensions, long snapshotMillis) {
        int removed = 0;
        for (Identifier dimension : dimensions) {
            var key = bank.getKeyInternal(dimension).orElse(null);
            if (key == null) continue;
            Set<BlockPos> found = reported.getOrDefault(dimension, Set.of());

            for (var entry : List.copyOf(key.getMemories().entrySet())) {
                Memory memory = entry.getValue();
                boolean scannable = memory.container().map(block ->
                        block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock).orElse(false);
                boolean olderThanScan = memory.realTimestamp().toEpochMilli() <= snapshotMillis;
                if (scannable && olderThanScan && memory.entityId() == null && !found.contains(entry.getKey())) {
                    bank.removeMemory(dimension, entry.getKey());
                    removed++;
                }
            }
        }
        return removed;
    }

    private static void finish(ScanPayloads.Finished finished) {
        MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null);
        if (bank == null || droppedBatches) {
            droppedBatches = false;
            tellPlayer("Chest Tracker: nenhum banco de memória estava carregado, então a varredura não foi guardada.");
        } else {
            SearchSettings search = bank.getMetadata().getSearchSettings();
            if (search.itemListRange != Integer.MAX_VALUE) {
                // Otherwise chests further than the default 256 blocks would be found but never listed.
                search.itemListRange = Integer.MAX_VALUE;
                tellPlayer("Chest Tracker: o alcance da lista de itens agora é infinito, para mostrar todos os baús encontrados.");
            }
            IntegritySettings integrity = bank.getMetadata().getIntegritySettings();
            Long lifetime = integrity.memoryLifetime.seconds;
            if (lifetime != null && lifetime < IntegritySettings.MemoryLifetime.TEN_DAYS.seconds) {
                integrity.memoryLifetime = IntegritySettings.MemoryLifetime.TEN_DAYS;
                tellPlayer("Chest Tracker: as memórias deste banco agora duram 10 dias.");
            }
            int removed = removeMissingContainers(bank, finished.completeDimensions(), finished.snapshotMillis());
            if (removed > 0) tellPlayer("Chest Tracker: %d contêineres que não existem mais foram removidos da memória.".formatted(removed));
            Storage.save(bank);
        }
        reported.clear();
        finishedScans++;
    }

    private static void tellPlayer(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.literal(message));
    }
}
