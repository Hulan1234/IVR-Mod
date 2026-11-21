package net.hulan.ivr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.block.IBlock;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.mappings.UtilitiesClient;
import net.hulan.ivr.block.BlockKCRTicketBarrier;
import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;

public class RenderKCRTicketBarrier extends BlockEntityRendererMapper<BlockKCRTicketBarrier.TileEntityKCRTicketBarrier> {

    public RenderKCRTicketBarrier(BlockEntityRenderDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override
    public void render(BlockKCRTicketBarrier.TileEntityKCRTicketBarrier entity,
                       float tickDelta,
                       @NotNull PoseStack matrices,
                       @NotNull MultiBufferSource vertexConsumers,
                       int light,
                       int overlay) {
        // The arms are an atlas-baked template: the preheat hook rebuilds it after every resource
        // reload, so this path never constructs quads or looks up the sprite.
        VertexMesh mesh = TicketBarrierMesh.get();
        if (mesh == null) {
            return;
        }
        Direction facing = IBlock.getStatePropertySafe(entity.getBlockState(), BlockKCRTicketBarrier.FACING);
        VertexConsumer vertices = vertexConsumers.getBuffer(RenderType.solid());
        matrices.pushPose();
        matrices.translate(0.5D, 0.5D, 0.5D);
        UtilitiesClient.rotateYDegrees(matrices, 180.0F - facing.toYRot());
        matrices.translate(-0.5D, -0.5D, -0.5D);
        TicketBarrierMesh.rotateAroundAxis(matrices, -entity.getAnimationAngle(tickDelta));
        mesh.replay(matrices, vertices, light, overlay);
        matrices.popPose();
    }
}
