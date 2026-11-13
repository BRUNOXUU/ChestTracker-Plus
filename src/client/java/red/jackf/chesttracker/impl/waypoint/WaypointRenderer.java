package red.jackf.chesttracker.impl.waypoint;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.providers.ProviderUtils;
import red.jackf.whereisit.client.render.WhereIsItPipelines;
import red.jackf.whereisit.config.WhereIsItConfig;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Draws a beacon-style beam on every waypoint in the player's dimension. */
public final class WaypointRenderer {
    private static final int COLOUR = 0xFFFFB020;
    private static final int HIGHLIGHT_COLOUR = 0xFFFF00E6;
    private static final float HIGHLIGHT_MIN_ALPHA = 150f;
    private static final float HIGHLIGHT_MAX_ALPHA = 235f;
    private static final double HIGHLIGHT_INFLATE = 0.02;
    private static final int BEAM_HEIGHT = 2048;
    /** Beams widen from this far away, so that a distant one is still easy to spot. */
    private static final float DISTANCE_PER_RADIUS_SCALE = 24f;
    private static final float SOLID_RADIUS = 0.2f;
    private static final float GLOW_RADIUS = 0.25f;
    private static final int ANIMATION_TICKS = 40;
    private static final double BLOCKS_INSIDE_RENDER_DISTANCE = 8.0;
    private static final int THROUGH_WALLS_ALPHA = 170;

    private record Highlight(BlockPos pos, List<BlockPos> others) {
    }

    /** Containers lit up by clicking an item, for as long as Where Is It shows its search results. */
    private static List<Highlight> clickedContainers = List.of();
    private static Identifier clickedDimension = null;
    private static long clickedUntilGameTime = 0;

    private WaypointRenderer() {
    }

    public static void setup() {
        LevelRenderEvents.COLLECT_SUBMITS.register(WaypointRenderer::submit);
    }

    /** Replaces the containers lit up by the last click. */
    public static void highlightClicked(Identifier dimension, Collection<Map.Entry<BlockPos, Memory>> containers) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        clickedDimension = dimension;
        clickedUntilGameTime = level.getGameTime() + WhereIsItConfig.INSTANCE.instance().getCommon().fadeoutTimeTicks;
        clickedContainers = containers.stream().map(entry -> new Highlight(entry.getKey(), entry.getValue().otherPositions())).toList();
    }

    /** @return how many containers are currently lit up by a click */
    public static int clickedContainerCount() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getGameTime() <= clickedUntilGameTime ? clickedContainers.size() : 0;
    }

    private static void submit(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        Identifier dimension = ProviderUtils.getPlayersCurrentKey().orElse(null);
        if (dimension == null) return;

        Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        float animation = Math.floorMod(minecraft.level.getGameTime(), ANIMATION_TICKS) + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double maxDistance = minecraft.options.getEffectiveRenderDistance() * 16.0 - BLOCKS_INSIDE_RENDER_DISTANCE;
        PoseStack pose = context.poseStack();

        for (Waypoints.Waypoint waypoint : Waypoints.active()) {
            if (!waypoint.dimension().equals(dimension)) continue;

            double offsetX = waypoint.pos().getX() + 0.5 - camera.x;
            double offsetZ = waypoint.pos().getZ() + 0.5 - camera.z;
            double distance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);

            // A beam past the render distance would be clipped, so it is drawn at the edge, in the right direction.
            double shrink = distance > maxDistance ? maxDistance / distance : 1.0;
            float radiusScale = Math.max(1f, (float) Math.min(distance, maxDistance) / DISTANCE_PER_RADIUS_SCALE);

            pose.pushPose();
            // the beam adds half a block to centre itself
            pose.translate(offsetX * shrink - 0.5, waypoint.pos().getY() + 1 - camera.y, offsetZ * shrink - 0.5);
            BeaconRenderer.submitBeaconBeam(pose, context.submitNodeCollector(), BeaconRenderer.BEAM_LOCATION, 1f, animation,
                    0, BEAM_HEIGHT, COLOUR, SOLID_RADIUS * radiusScale, GLOW_RADIUS * radiusScale);
            pose.popPose();

            submitBeamThroughWalls(context, offsetX * shrink, waypoint.pos().getY() + 1 - camera.y, offsetZ * shrink, SOLID_RADIUS * radiusScale);

            if (distance <= maxDistance) submitHighlight(context, waypoint.pos(), waypoint.others(), camera, animation);
        }

        if (dimension.equals(clickedDimension) && clickedContainerCount() > 0) {
            for (Highlight clicked : clickedContainers) {
                if (clicked.pos().distToCenterSqr(camera) <= maxDistance * maxDistance) submitHighlight(context, clicked.pos(), clicked.others(), camera, animation);
            }
        }
    }

    /** The game's beam is hidden by terrain, so underground this column is what shows where the waypoint is. */
    private static void submitBeamThroughWalls(LevelRenderContext context, double centreX, double bottomY, double centreZ, float radius) {
        RenderType renderType = WhereIsItPipelines.DEBUG_QUADS_NO_DEPTH;
        if (renderType == null) return;

        int colour = ARGB.color(THROUGH_WALLS_ALPHA, COLOUR);
        float x0 = (float) centreX - radius, x1 = (float) centreX + radius;
        float z0 = (float) centreZ - radius, z1 = (float) centreZ + radius;
        float y0 = (float) bottomY, y1 = y0 + BEAM_HEIGHT;

        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), renderType, (pose, buffer) -> {
            quad(pose, buffer, colour, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(pose, buffer, colour, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quad(pose, buffer, colour, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(pose, buffer, colour, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        });
    }

    /** Fills the container's whole shape with a pulsing colour that shows through walls. */
    private static void submitHighlight(LevelRenderContext context, BlockPos pos, List<BlockPos> others, Vec3 camera, float animation) {
        RenderType renderType = WhereIsItPipelines.DEBUG_QUADS_NO_DEPTH;
        if (renderType == null) return;

        AABB shape = new AABB(pos);
        for (BlockPos other : others) shape = shape.minmax(new AABB(other));
        AABB box = shape.inflate(HIGHLIGHT_INFLATE).move(camera.reverse());

        float pulse = (Mth.sin(animation / ANIMATION_TICKS * Mth.TWO_PI) + 1f) / 2f;
        int colour = ARGB.color((int) Mth.lerp(pulse, HIGHLIGHT_MIN_ALPHA, HIGHLIGHT_MAX_ALPHA), HIGHLIGHT_COLOUR);

        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), renderType, (pose, buffer) -> {
            float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
            float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
            quad(pose, buffer, colour, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            quad(pose, buffer, colour, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            quad(pose, buffer, colour, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(pose, buffer, colour, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quad(pose, buffer, colour, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(pose, buffer, colour, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        });
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, int colour,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        buffer.addVertex(pose, x1, y1, z1).setColor(colour);
        buffer.addVertex(pose, x2, y2, z2).setColor(colour);
        buffer.addVertex(pose, x3, y3, z3).setColor(colour);
        buffer.addVertex(pose, x4, y4, z4).setColor(colour);
    }
}
