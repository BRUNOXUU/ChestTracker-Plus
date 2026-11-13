package red.jackf.chesttracker.scan;

import com.mojang.serialization.DynamicOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import red.jackf.chesttracker.scan.ScanPayloads.ScannedContainer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The results of the last full scan, kept in the world folder so that {@code /chesttracker scan} can answer without
 * touching the world. A header is followed by one record per container, all gzipped.
 */
final class ScanCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChestTrackerScan.ID);
    private static final int FORMAT = 1;
    private static final String COMPLETE_KEY = "complete";

    private ScanCache() {
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("chesttrackermelhor").resolve("containers.dat");
    }

    private static DynamicOps<Tag> ops(MinecraftServer server) {
        return server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    private static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /** Writes to a temporary file, so that a scan that fails leaves the previous results in place. */
    static final class Writer implements Closeable {
        private final Path target;
        private final Path temporary;
        private final DynamicOps<Tag> ops;
        private DataOutputStream out;
        private @Nullable IOException failure = null;

        Writer(MinecraftServer server, long createdAtMillis) throws IOException {
            this.target = file(server);
            this.temporary = target.resolveSibling(target.getFileName() + ".tmp");
            this.ops = ops(server);
            Files.createDirectories(target.getParent());
            this.out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary))));

            CompoundTag header = new CompoundTag();
            header.putInt("format", FORMAT);
            header.putInt("dataVersion", currentDataVersion());
            header.putLong("createdAt", createdAtMillis);
            NbtIo.write(header, out);
        }

        /** A failure here is remembered and reported by {@link #commit()}; the scan itself can still finish. */
        void write(Identifier dimension, ScannedContainer container) {
            if (failure != null) return;
            try {
                NbtIo.write(encode(dimension, container), out);
            } catch (IOException e) {
                failure = e;
            }
        }

        private CompoundTag encode(Identifier dimension, ScannedContainer container) {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension.toString());
            tag.putLong("pos", container.pos().asLong());
            tag.putString("block", BuiltInRegistries.BLOCK.getKey(container.block()).toString());
            container.name().ifPresent(name -> tag.store("name", ComponentSerialization.CODEC, ops, name));
            tag.put("others", new LongArrayTag(container.others().stream().mapToLong(BlockPos::asLong).toArray()));

            ListTag items = new ListTag();
            for (ItemStack stack : container.items()) {
                ItemStack.CODEC.encodeStart(ops, stack)
                        .resultOrPartial(error -> LOGGER.warn("Could not save item: {}", error))
                        .ifPresent(items::add);
            }
            tag.put("items", items);
            return tag;
        }

        /** @param complete whether every chunk was read, which lets a client trust that an absent container is gone */
        void commit(boolean complete) throws IOException {
            if (failure != null) throw failure;
            CompoundTag trailer = new CompoundTag();
            trailer.putBoolean(COMPLETE_KEY, complete);
            NbtIo.write(trailer, out);
            out.close();
            out = null;
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public void close() throws IOException {
            if (out != null) {
                out.close();
                Files.deleteIfExists(temporary);
            }
        }
    }

    record Entry(Identifier dimension, ScannedContainer container) {
    }

    static final class Reader implements Closeable {
        private final DataInputStream in;
        private final DynamicOps<Tag> ops;
        private final long createdAtMillis;
        private boolean complete = false;

        private Reader(DataInputStream in, DynamicOps<Tag> ops, long createdAtMillis) {
            this.in = in;
            this.ops = ops;
            this.createdAtMillis = createdAtMillis;
        }

        long createdAtMillis() {
            return createdAtMillis;
        }

        /** Known once {@link #next()} has reached the end of the file. Files without the trailer count as incomplete. */
        boolean isComplete() {
            return complete;
        }

        /** @return the next container, or null at the end of the file */
        @Nullable Entry next() throws IOException {
            while (true) {
                CompoundTag tag;
                try {
                    tag = NbtIo.read(in);
                } catch (EOFException e) {
                    return null;
                }

                if (tag.contains(COMPLETE_KEY)) {
                    complete = tag.getBooleanOr(COMPLETE_KEY, false);
                    return null;
                }

                Entry entry = decode(tag);
                if (entry != null) return entry;
            }
        }

        private @Nullable Entry decode(CompoundTag tag) {
            Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(tag.getStringOr("block", "minecraft:air")));
            if (block == Blocks.AIR) return null;

            Optional<Component> name = tag.read("name", ComponentSerialization.CODEC, ops);
            List<BlockPos> others = Arrays.stream(tag.getLongArray("others").orElse(new long[0])).mapToObj(BlockPos::of).toList();

            List<ItemStack> items = new ArrayList<>();
            for (Tag item : tag.getListOrEmpty("items")) {
                ItemStack.CODEC.parse(ops, item)
                        .resultOrPartial(error -> LOGGER.warn("Skipping unreadable item: {}", error))
                        .ifPresent(items::add);
            }

            BlockPos pos = BlockPos.of(tag.getLongOr("pos", 0L));
            return new Entry(Identifier.parse(tag.getStringOr("dimension", "minecraft:overworld")),
                    new ScannedContainer(pos, block, name, others, items));
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** @return a reader for the saved results, or null if there are none this version of the game can use */
    static @Nullable Reader open(MinecraftServer server) {
        Path file = file(server);
        if (!Files.exists(file)) return null;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))));
            CompoundTag header = NbtIo.read(in);
            if (header.getIntOr("format", -1) == FORMAT && header.getIntOr("dataVersion", -1) == currentDataVersion()) {
                return new Reader(in, ops(server), header.getLongOr("createdAt", 0L));
            }
            LOGGER.info("Ignoring {}: saved by another version", file);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}", file, e);
        }

        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                LOGGER.warn("Could not close {}", file, e);
            }
        }
        return null;
    }
}
