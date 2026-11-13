package red.jackf.chesttracker.scan;

import com.mojang.serialization.DynamicOps;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import red.jackf.chesttracker.scan.ScanPayloads.ScannedContainer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads every container out of the saved region files, without loading any chunk into the world.
 *
 * <p>Region files are read directly by a pool of workers, because the game's own chunk reader is one queue per
 * dimension. The command flushes the world first, so the files are current. If a chunk is being rewritten as it is
 * read and comes back damaged, that chunk is read again through the game's reader.
 */
final class ContainerScan {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChestTrackerScan.ID);
    private static final Pattern REGION_FILE = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final int SECTOR_BYTES = 4096;
    private static final int CHUNK_HEADER_BYTES = 5;
    private static final int EXTERNAL_CHUNK_FLAG = 0x80;
    private static final int BATCH_SIZE = 64;
    private static final long SEND_INTERVAL_MS = 5;
    private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long IO_TIMEOUT_SECONDS = 60;

    private final MinecraftServer server;
    private final List<UUID> recipients;
    private final boolean announcement;
    private final Consumer<Boolean> onDone;
    private final long startedAtMillis;
    private final @Nullable ScanCache.Reader savedFile;
    private final List<ServerLevel> levels = new ArrayList<>();
    private final Thread thread;
    private final BlockingQueue<ScanPayloads.Batch> outgoing = new ArrayBlockingQueue<>(64);

    private final AtomicInteger regionsDone = new AtomicInteger();
    private final AtomicInteger containers = new AtomicInteger();
    private final AtomicInteger unopenedLoot = new AtomicInteger();
    private final AtomicInteger unreadableChunks = new AtomicInteger();
    private volatile int regionsTotal = 0;
    private volatile boolean cancelled = false;
    private volatile @Nullable ScanCache.Writer fileWriter = null;
    private long lastProgressNanos = System.nanoTime();

    private ContainerScan(MinecraftServer server, List<ServerPlayer> players, boolean announcement, long startedAtMillis,
                          @Nullable ScanCache.Reader savedFile, Consumer<Boolean> onDone) {
        this.server = server;
        this.recipients = players.stream().map(ServerPlayer::getUUID).toList();
        this.announcement = announcement;
        this.startedAtMillis = startedAtMillis;
        this.savedFile = savedFile;
        this.onDone = onDone;
        server.getAllLevels().forEach(levels::add);
        this.thread = new Thread(this::run, "Chest Tracker scan");
        this.thread.setDaemon(true);
    }

    /**
     * Reads every container from the world, and saves the result for later scans.
     *
     * @param player who asked and receives the results as they are found, or null for a scheduled scan
     * @param onDone told whether the scan finished without failing
     */
    static ContainerScan ofWorld(MinecraftServer server, @Nullable ServerPlayer player, long startedAtMillis, Consumer<Boolean> onDone) {
        return new ContainerScan(server, player == null ? List.of() : List.of(player), false, startedAtMillis, null, onDone);
    }

    /**
     * Sends the results saved by an earlier scan, without touching the world.
     *
     * @param announcement whether these players are being told about someone else's scan, rather than having asked
     */
    static ContainerScan fromSavedFile(MinecraftServer server, List<ServerPlayer> players, ScanCache.Reader savedFile, boolean announcement, Consumer<Boolean> onDone) {
        return new ContainerScan(server, players, announcement, savedFile.createdAtMillis(), savedFile, onDone);
    }

    void start() {
        thread.start();
    }

    void cancel() {
        cancelled = true;
    }

    private void run() {
        long start = System.nanoTime();
        String failure = null;
        try {
            if (savedFile != null) sendSavedFile(savedFile);
            else scanWorld();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "interrompida";
        } catch (Exception e) {
            LOGGER.error("Chest Tracker scan failed", e);
            failure = String.valueOf(e.getMessage());
        }

        LOGGER.info("Scan finished in {} ms: {} regions, {} containers, {} unreadable chunks, from file: {}",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), regionsDone.get(), containers.get(), unreadableChunks.get(), savedFile != null);

        String finalFailure = failure;
        double seconds = (System.nanoTime() - start) / 1e9;
        server.execute(() -> {
            onDone.accept(finish(finalFailure, seconds));
        });
    }

    private void scanWorld() throws Exception {
        int cores = ScanConfig.cores();
        LOGGER.info("Scanning the world with {} cores", cores);
        ExecutorService workers = Executors.newFixedThreadPool(cores, task -> {
            Thread worker = new Thread(task, "Chest Tracker scan worker");
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            return worker;
        });

        try (ScanCache.Writer writer = new ScanCache.Writer(server, startedAtMillis)) {
            fileWriter = writer;

            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            Map<ServerLevel, List<Path>> regions = new LinkedHashMap<>();
            int total = 0;
            for (ServerLevel level : levels) {
                // Wait for the save requested by the command to reach the region files.
                level.getChunkSource().chunkMap.synchronize(false).get(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                List<Path> files = listRegionFiles(DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region"));
                regions.put(level, files);
                total += files.size();
            }
            regionsTotal = total;

            for (Map.Entry<ServerLevel, List<Path>> entry : regions.entrySet()) {
                if (cancelled) return;
                DimensionScan dimension = new DimensionScan(entry.getKey());
                List<Future<?>> tasks = new ArrayList<>();
                for (Path file : entry.getValue()) {
                    tasks.add(workers.submit(() -> dimension.scanRegion(file)));
                }
                sendWhile(() -> tasks.stream().anyMatch(task -> !task.isDone()));
                for (Future<?> task : tasks) task.get();
                dimension.finish();
                sendWhile(() -> !outgoing.isEmpty());
            }

            if (!cancelled) writer.commit(unreadableChunks.get() == 0);
        } finally {
            workers.shutdownNow();
        }
    }

    private void sendSavedFile(ScanCache.Reader reader) throws IOException, InterruptedException {
        try (reader) {
            Identifier dimension = null;
            List<ScannedContainer> batch = new ArrayList<>(BATCH_SIZE);
            for (ScanCache.Entry entry = reader.next(); entry != null && !cancelled; entry = reader.next()) {
                if (!entry.dimension().equals(dimension) || batch.size() >= BATCH_SIZE) {
                    if (dimension != null && !batch.isEmpty()) dispatch(new ScanPayloads.Batch(dimension, startedAtMillis, List.copyOf(batch)));
                    batch.clear();
                    dimension = entry.dimension();
                }
                batch.add(entry.container());
                containers.incrementAndGet();
            }
            if (dimension != null && !batch.isEmpty() && !cancelled) dispatch(new ScanPayloads.Batch(dimension, startedAtMillis, List.copyOf(batch)));
        }
    }

    private void dispatch(ScanPayloads.Batch batch) throws InterruptedException {
        if (recipients.isEmpty()) return; // a scheduled scan, which only has to write the file

        server.execute(() -> {
            List<ServerPlayer> players = onlineRecipients();
            if (players.isEmpty()) cancel();
            for (ServerPlayer player : players) ServerPlayNetworking.send(player, batch);
        });
        Thread.sleep(SEND_INTERVAL_MS);
    }

    private List<ServerPlayer> onlineRecipients() {
        return recipients.stream().map(server.getPlayerList()::getPlayer).filter(Objects::nonNull).toList();
    }

    /** Sends finished batches for as long as the condition holds. Paced by time, so it keeps going while the game is paused. */
    private void sendWhile(java.util.function.BooleanSupplier condition) throws InterruptedException {
        while (!cancelled && condition.getAsBoolean()) {
            ScanPayloads.Batch batch = outgoing.poll(20, TimeUnit.MILLISECONDS);
            if (batch != null) dispatch(batch);
            if (System.nanoTime() - lastProgressNanos > PROGRESS_INTERVAL_NANOS) {
                lastProgressNanos = System.nanoTime();
                server.execute(this::showProgress);
            }
        }
    }

    private void showProgress() {
        Component progress = Component.literal("Varredura: %d/%d regiões, %d contêineres"
                .formatted(regionsDone.get(), regionsTotal, containers.get()));
        onlineRecipients().forEach(player -> player.sendSystemMessage(progress, true));
    }

    /** @return whether the scan went through without failing */
    private boolean finish(@Nullable String failure, double seconds) {
        // Only a clean read of the whole world can say that a remembered container is gone.
        boolean complete = failure == null && !cancelled && unreadableChunks.get() == 0 && (savedFile == null || savedFile.isComplete());
        ScanPayloads.Finished finished = new ScanPayloads.Finished(
                complete ? levels.stream().map(level -> level.dimension().identifier()).toList() : List.of(),
                startedAtMillis);

        Component message = Component.literal(failure != null ? "A varredura falhou: " + failure : summary(seconds));
        for (ServerPlayer player : onlineRecipients()) {
            ServerPlayNetworking.send(player, finished);
            player.sendSystemMessage(message);
        }
        return failure == null && !cancelled;
    }

    private String summary(double seconds) {
        if (announcement) return "Chest Tracker: a lista de contêineres do servidor foi atualizada.";
        if (savedFile != null) {
            String date = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(startedAtMillis));
            return "%d contêineres carregados do arquivo salvo em %s. Use /chesttracker scan new para atualizar.".formatted(containers.get(), date);
        }

        String summary = String.format(Locale.ROOT, "Varredura concluída em %.1f s: %d contêineres em %d dimensões.", seconds, containers.get(), levels.size());
        if (unopenedLoot.get() > 0)
            summary += " %d baús de loot nunca abertos foram ignorados, porque ainda não têm itens.".formatted(unopenedLoot.get());
        if (unreadableChunks.get() > 0)
            summary += " %d chunks não puderam ser lidos (detalhes no log do servidor).".formatted(unreadableChunks.get());
        return summary;
    }

    private static List<Path> listRegionFiles(Path regionFolder) throws IOException {
        if (!Files.isDirectory(regionFolder)) return List.of();
        try (Stream<Path> files = Files.list(regionFolder)) {
            return files.filter(file -> REGION_FILE.matcher(file.getFileName().toString()).matches()).toList();
        }
    }

    /** One chunk as stored in a region file, still compressed. */
    private record RawChunk(byte[] bytes, int length, RegionFileVersion compression) {
        DataInputStream open() throws IOException {
            return new DataInputStream(compression.wrap(new ByteArrayInputStream(bytes, CHUNK_HEADER_BYTES, length)));
        }
    }

    /** @return the chunk, or null if it is not stored inline in this file and has to go through the game's reader */
    private static @Nullable RawChunk readRawChunk(FileChannel file, int location) throws IOException {
        long position = (long) (location >>> 8) * SECTOR_BYTES;
        ByteBuffer buffer = ByteBuffer.allocate((location & 0xFF) * SECTOR_BYTES);
        while (buffer.hasRemaining()) {
            if (file.read(buffer, position + buffer.position()) < 0) throw new IOException("Unexpected end of region file");
        }

        int storedLength = buffer.getInt(0);
        int version = buffer.get(4) & 0xFF;
        if ((version & EXTERNAL_CHUNK_FLAG) != 0) return null;
        RegionFileVersion compression = RegionFileVersion.fromId(version);
        if (compression == null) return null;
        if (storedLength <= 1 || storedLength + 4 > buffer.capacity()) throw new IOException("Bad chunk length " + storedLength);
        return new RawChunk(buffer.array(), storedLength - 1, compression);
    }

    /**
     * Cheap first look that skips the block data, which is most of a chunk. Chunks saved by an older version are
     * always read in full, because their data is laid out differently until upgraded.
     */
    private static boolean mightHoldContainers(RawChunk chunk, int currentDataVersion) throws IOException {
        CollectFields fields = new CollectFields(new FieldSelector(IntTag.TYPE, "DataVersion"), new FieldSelector(ListTag.TYPE, "block_entities"));
        try (DataInputStream in = chunk.open()) {
            NbtIo.parse(in, fields, NbtAccounter.unlimitedHeap());
        }
        if (!(fields.getResult() instanceof CompoundTag tag)) return true;
        if (tag.getIntOr("DataVersion", -1) < currentDataVersion) return true;

        for (Tag entry : tag.getListOrEmpty("block_entities")) {
            if (!(entry instanceof CompoundTag blockEntity)) continue;
            String id = blockEntity.getStringOr("id", "");
            if (blockEntity.contains("Items") || blockEntity.contains("LootTable")
                    || id.contains("chest") || id.contains("barrel") || id.contains("shulker")) {
                return true;
            }
        }
        return false;
    }

    private final class DimensionScan {
        private final ServerLevel level;
        private final ChunkMap chunkMap;
        private final DynamicOps<Tag> ops;
        private final CompoundTag fixContext;
        private final int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        private final Identifier dimension;
        // A double chest can straddle a chunk border, so a half waits here until its partner is read.
        private final Map<BlockPos, ChestHalf> waitingHalves = new HashMap<>();
        private final List<ScannedContainer> batch = new ArrayList<>(BATCH_SIZE);

        DimensionScan(ServerLevel level) {
            this.level = level;
            this.chunkMap = level.getChunkSource().chunkMap;
            this.ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            this.fixContext = ChunkMap.getChunkDataFixContextTag(level.dimension(), level.getChunkSource().getGenerator().getTypeNameForDataFixer());
            this.dimension = level.dimension().identifier();
        }

        void scanRegion(Path path) {
            Matcher name = REGION_FILE.matcher(path.getFileName().toString());
            if (!name.matches()) return;
            int regionX = Integer.parseInt(name.group(1));
            int regionZ = Integer.parseInt(name.group(2));

            try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
                ByteBuffer locations = ByteBuffer.allocate(SECTOR_BYTES);
                while (locations.hasRemaining() && file.read(locations, locations.position()) >= 0) {
                    // keep reading until the header is complete or the file ends
                }
                if (locations.hasRemaining()) return;

                for (int i = 0; i < 1024 && !cancelled; i++) {
                    int location = locations.getInt(i * 4);
                    if (location != 0) scanChunk(file, location, new ChunkPos(regionX * 32 + (i & 31), regionZ * 32 + (i >> 5)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                unreadableChunks.incrementAndGet();
                LOGGER.warn("Could not read region file {}", path, e);
            } finally {
                regionsDone.incrementAndGet();
            }
        }

        private void scanChunk(FileChannel file, int location, ChunkPos pos) throws InterruptedException {
            try {
                CompoundTag tag = null;
                try {
                    RawChunk raw = readRawChunk(file, location);
                    if (raw != null) {
                        if (!mightHoldContainers(raw, dataVersion)) return;
                        try (DataInputStream in = raw.open()) {
                            tag = NbtIo.read(in);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    tag = null; // rewritten while we read it, most likely
                }

                if (tag == null) {
                    Optional<CompoundTag> saved = chunkMap.read(pos).get(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (saved.isEmpty()) return;
                    tag = saved.get();
                }

                readContainers(pos, chunkMap.upgradeChunkTag(tag, -1, fixContext, dataVersion));
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (unreadableChunks.incrementAndGet() <= 20)
                    LOGGER.warn("Could not read chunk {} in {}", pos, dimension, e);
            }
        }

        private void readContainers(ChunkPos chunkPos, CompoundTag tag) throws InterruptedException {
            if (SerializableChunkData.getChunkStatusFromTag(tag) != ChunkStatus.FULL) return;
            if (tag.getListOrEmpty("block_entities").isEmpty()) return;

            SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
            if (data == null) return;

            for (CompoundTag blockEntity : data.blockEntities()) {
                BlockPos pos = BlockEntity.getPosFromTag(chunkPos, blockEntity);
                BlockState state = stateAt(data, pos);
                if (state == null) continue;
                Block block = state.getBlock();
                if (!(block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock)) continue;

                // Items of a loot chest only exist once someone opens it.
                if (blockEntity.contains("LootTable")) {
                    unopenedLoot.incrementAndGet();
                    continue;
                }

                Optional<Component> name = blockEntity.read("CustomName", ComponentSerialization.CODEC, ops);
                List<ItemStack> items = readItems(blockEntity, ops);

                if (block instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
                    reportHalf(new ChestHalf(pos, partner, block, state.getValue(ChestBlock.TYPE), name, items));
                } else {
                    report(new ScannedContainer(pos, block, name, List.of(), items));
                }
            }
        }

        private synchronized void reportHalf(ChestHalf half) throws InterruptedException {
            ChestHalf other = waitingHalves.remove(half.partner);
            if (other == null) {
                waitingHalves.put(half.pos, half);
            } else {
                report(merge(half, other));
            }
        }

        private synchronized void report(ScannedContainer container) throws InterruptedException {
            batch.add(container);
            containers.incrementAndGet();
            if (batch.size() >= BATCH_SIZE) flush();
        }

        /** Reports what is still pending. Halves whose partner is gone are reported on their own. */
        synchronized void finish() throws InterruptedException {
            for (ChestHalf half : List.copyOf(waitingHalves.values())) {
                report(new ScannedContainer(half.pos, half.block, half.name, List.of(), half.items));
            }
            waitingHalves.clear();
            if (!batch.isEmpty()) flush();
        }

        private void flush() throws InterruptedException {
            ScanCache.Writer writer = fileWriter;
            if (writer != null) {
                for (ScannedContainer container : batch) writer.write(dimension, container);
            }
            ScanPayloads.Batch payload = new ScanPayloads.Batch(dimension, 0L, List.copyOf(batch));
            batch.clear();
            while (!cancelled && !outgoing.offer(payload, 1, TimeUnit.SECONDS)) {
                // The sender is behind; wait for room.
            }
        }
    }

    /** Orders the halves as the game does in a double chest screen: the RIGHT half first. */
    private static ScannedContainer merge(ChestHalf a, ChestHalf b) {
        ChestHalf first = a.type == ChestType.RIGHT ? a : b;
        ChestHalf second = first == a ? b : a;
        List<ItemStack> items = new ArrayList<>(first.items);
        items.addAll(second.items);
        return new ScannedContainer(first.pos, first.block, first.name.or(() -> second.name), List.of(second.pos), items);
    }

    private static @Nullable BlockState stateAt(SerializableChunkData data, BlockPos pos) {
        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        for (SerializableChunkData.SectionData section : data.sectionData()) {
            if (section.y() != sectionY) continue;
            LevelChunkSection blocks = section.chunkSection();
            return blocks == null ? null : blocks.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
        return null;
    }

    private static List<ItemStack> readItems(CompoundTag blockEntity, DynamicOps<Tag> ops) {
        List<ItemStack> items = new ArrayList<>();
        for (Tag item : blockEntity.getListOrEmpty("Items")) {
            ItemStackWithSlot.CODEC.parse(ops, item)
                    .resultOrPartial(error -> LOGGER.warn("Skipping unreadable item: {}", error))
                    .map(ItemStackWithSlot::stack)
                    .filter(stack -> !stack.isEmpty())
                    .ifPresent(items::add);
        }
        return items;
    }

    private record ChestHalf(BlockPos pos, BlockPos partner, Block block, ChestType type, Optional<Component> name, List<ItemStack> items) {
    }
}
