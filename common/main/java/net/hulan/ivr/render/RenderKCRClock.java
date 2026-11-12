package net.hulan.ivr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.block.IBlock;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.mappings.UtilitiesClient;
import mtr.render.MoreRenderLayers;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.render_promotion.mesh.QuadSpec;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.hulan.ivr.client.IVRConfig;
import net.minecraft.client.renderer.texture.OverlayTexture;
import java.util.List;
import net.hulan.ivr.block.BlockKCRClock;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

public class RenderKCRClock extends BlockEntityRendererMapper<BlockKCRClock.TileEntityKCRClock> implements IGui, IBlock {
    private static final ResourceLocation HAND_TEXTURE = new ResourceLocation("mtr:textures/block/white.png");
    private static final List<QuadSpec> HOUR_SPEC = List.of(new QuadSpec(-0.01F, -0.03F, 0.01F, 0.15F, 0.1F, -5592406, 0, 1, 0));
    private static final List<QuadSpec> MINUTE_SPEC = List.of(new QuadSpec(-0.01F, -0.03F, 0.01F, 0.24F, 0.105F, -5592406, 0, 1, 0));

    
    public RenderKCRClock(BlockEntityRenderDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override
    public void render(BlockKCRClock.TileEntityKCRClock entity,
                       float tickDelta,
                       @NotNull PoseStack matrices,
                       @NotNull MultiBufferSource vertexConsumers,
                       int light,
                       int overlay) {
        Level world = entity.getLevel();
        if (world != null) {
            BlockPos pos = entity.getBlockPos();
            BlockState state = world.getBlockState(pos);
            boolean rotated = IBlock.getStatePropertySafe(state, BlockKCRClock.FACING);
            matrices.pushPose();
            matrices.translate(0.5D, 0.3125D, 0.5D);
            if (rotated) {
                UtilitiesClient.rotateYDegrees(matrices, 90.0F);
            }
            long time = world.getDayTime() + 6000L;
            drawHand(matrices, vertexConsumers, (float)time * 360.0F / 12000.0F, true);
            drawHand(matrices, vertexConsumers, (float)time * 360.0F / 1000.0F, false);
            UtilitiesClient.rotateYDegrees(matrices, 180.0F);
            drawHand(matrices, vertexConsumers, (float)time * 360.0F / 12000.0F, true);
            drawHand(matrices, vertexConsumers, (float)time * 360.0F / 1000.0F, false);
            matrices.popPose();
        }
    }

    private static void drawHand(PoseStack matrices, MultiBufferSource vertexConsumers, float rotation, boolean isHourHand) {
        matrices.pushPose();
        UtilitiesClient.rotateZDegrees(matrices, -rotation);
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(MoreRenderLayers.getLight(HAND_TEXTURE, false));
        VertexMeshKey key = new VertexMeshKey("ivr:clock_hand", isHourHand ? 0 : 1,
                RenderDataRevision.current());
        VertexMesh mesh = RenderCacheLifecycle.requestQuads(key,
                isHourHand ? HOUR_SPEC : MINUTE_SPEC);
        if (mesh == null) {
            IDrawing.drawTexture(matrices, vertexConsumer, -0.01F, isHourHand ? 0.15F : 0.24F, isHourHand ? 0.1F : 0.105F, 0.01F, -0.03F, isHourHand ? 0.1F : 0.105F, Direction.UP, -5592406, 15728816);
        } else if (IVRConfig.cpuOptimization) {
            mesh.replay(matrices, vertexConsumer, 15728816, OverlayTexture.NO_OVERLAY);
        } else {
            IDrawing.drawTexture(matrices, vertexConsumer, -0.01F, isHourHand ? 0.15F : 0.24F,
                    isHourHand ? 0.1F : 0.105F, 0.01F, -0.03F,
                    isHourHand ? 0.1F : 0.105F, Direction.UP, -5592406, 15728816);
        }
        matrices.popPose();
    }
}
