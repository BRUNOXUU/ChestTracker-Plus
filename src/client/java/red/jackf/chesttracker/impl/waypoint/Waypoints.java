package red.jackf.chesttracker.impl.waypoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Containers the player marked with a beam of light, kept per memory bank and saved between sessions. A waypoint
 * lasts until its container is opened, it is removed by hand, or the container leaves the memory bank.
 */
public final class Waypoints {
    private static final int MISSING_CONTAINER_CHECK_TICKS = 20;

    public record Waypoint(Identifier dimension, BlockPos pos, List<BlockPos> others) {
        private static final Codec<Waypoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("dimension").forGetter(Waypoint::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Waypoint::pos),
                BlockPos.CODEC.listOf().fieldOf("others").forGetter(Waypoint::others)
        ).apply(instance, Waypoint::new));

        private boolean covers(Identifier dimension, BlockPos position) {
            return this.dimension.equals(dimension) && (this.pos.equals(position) || this.others.contains(position));
        }
    }

    private static final Codec<Map<String, List<Waypoint>>> FILE_CODEC = Codec.unboundedMap(Codec.STRING, Waypoint.CODEC.listOf());
    private static final Map<String, List<Waypoint>> byBank = new HashMap<>();
    private static boolean loaded = false;
    private static int ticks = 0;

    private Waypoints() {
    }

    public static void setup() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks % MISSING_CONTAINER_CHECK_TICKS == 0) removeThoseWithoutContainer();
        });
    }

    /** Otherwise a beam on a broken container could never be cleared: its items are gone from the list. */
    private static void removeThoseWithoutContainer() {
        MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null);
        if (bank == null) return;
        List<Waypoint> waypoints = forBank(bank);
        if (waypoints.removeIf(waypoint -> bank.getKeyInternal(waypoint.dimension()).flatMap(key -> key.get(waypoint.pos())).isEmpty())) {
            save();
        }
    }

    public static List<Waypoint> active() {
        return MemoryBankAccessImpl.INSTANCE.getLoadedInternal().map(bank -> List.copyOf(forBank(bank))).orElse(List.of());
    }

    public static boolean has(Identifier dimension, BlockPos position) {
        return active().stream().anyMatch(waypoint -> waypoint.covers(dimension, position));
    }

    public static void add(Identifier dimension, BlockPos pos, List<BlockPos> others) {
        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            if (has(dimension, pos)) return;
            forBank(bank).add(new Waypoint(dimension, pos, List.copyOf(others)));
            save();
        });
    }

    public static void removeAt(Identifier dimension, BlockPos position) {
        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            if (forBank(bank).removeIf(waypoint -> waypoint.covers(dimension, position))) save();
        });
    }

    /** Removes every waypoint in the loaded memory bank, in all dimensions. */
    public static void clear() {
        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            forBank(bank).clear();
            save();
        });
    }

    private static List<Waypoint> forBank(MemoryBankImpl bank) {
        load();
        return byBank.computeIfAbsent(bank.getId(), ignored -> new ArrayList<>());
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("chesttrackermelhor").resolve("waypoints.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Path file = file();
        if (!Files.exists(file)) return;

        try {
            JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            FILE_CODEC.parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(error -> ChestTracker.LOGGER.warn("Could not read waypoints: {}", error))
                    .ifPresent(saved -> saved.forEach((bank, waypoints) -> byBank.put(bank, new ArrayList<>(waypoints))));
        } catch (IOException | RuntimeException e) {
            ChestTracker.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private static void save() {
        Path file = file();
        @Nullable JsonElement json = FILE_CODEC.encodeStart(JsonOps.INSTANCE, byBank)
                .resultOrPartial(error -> ChestTracker.LOGGER.warn("Could not save waypoints: {}", error))
                .orElse(null);
        if (json == null) return;

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ChestTracker.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
