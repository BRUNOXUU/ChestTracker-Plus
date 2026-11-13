package red.jackf.chesttracker.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;

/** Network messages sent from the server to the player who ran the scan. */
public final class ScanPayloads {
    private ScanPayloads() {
    }

    /**
     * One container found by the scan.
     *
     * @param pos    root position; a double chest is reported once, with its other half in {@code others}
     * @param block  the container block
     * @param name   the name given in an anvil, if any
     * @param others other positions of the same container
     * @param items  non-empty stacks inside
     */
    public record ScannedContainer(BlockPos pos, Block block, Optional<Component> name, List<BlockPos> others, List<ItemStack> items) {
        static final StreamCodec<RegistryFriendlyByteBuf, ScannedContainer> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ScannedContainer::pos,
                ByteBufCodecs.registry(Registries.BLOCK), ScannedContainer::block,
                ComponentSerialization.TRUSTED_OPTIONAL_STREAM_CODEC, ScannedContainer::name,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), ScannedContainer::others,
                ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()), ScannedContainer::items,
                ScannedContainer::new
        );
    }

    /**
     * @param snapshotMillis when the saved file these containers come from was made, or 0 if they were just read from
     *                       the world. A client keeps what it saw itself after that moment.
     */
    public record Batch(Identifier dimension, long snapshotMillis, List<ScannedContainer> containers) implements CustomPacketPayload {
        public static final Type<Batch> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ChestTrackerScan.ID, "batch"));
        static final StreamCodec<RegistryFriendlyByteBuf, Batch> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Batch::dimension,
                ByteBufCodecs.VAR_LONG, Batch::snapshotMillis,
                ScannedContainer.CODEC.apply(ByteBufCodecs.list()), Batch::containers,
                Batch::new
        );

        @Override
        public Type<Batch> type() {
            return TYPE;
        }
    }

    /**
     * Sent after the last batch, so the client knows when to save.
     *
     * @param completeDimensions dimensions read in full, where a remembered container that was not reported no longer
     *                           exists. Empty for scans with errors.
     * @param snapshotMillis     when the world was read
     */
    public record Finished(List<Identifier> completeDimensions, long snapshotMillis) implements CustomPacketPayload {
        public static final Type<Finished> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ChestTrackerScan.ID, "finished"));
        static final StreamCodec<RegistryFriendlyByteBuf, Finished> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), Finished::completeDimensions,
                ByteBufCodecs.VAR_LONG, Finished::snapshotMillis,
                Finished::new
        );

        @Override
        public Type<Finished> type() {
            return TYPE;
        }
    }
}
