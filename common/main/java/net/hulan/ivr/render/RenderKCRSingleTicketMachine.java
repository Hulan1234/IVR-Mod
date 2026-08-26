package net.hulan.ivr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.BlockEntityRendererMapper;
import net.hulan.ivr.block.BlockKCRSingleTicketMachine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;

public class RenderKCRSingleTicketMachine extends BlockEntityRendererMapper<BlockKCRSingleTicketMachine.TileEntityKCRSingleTicketMachine> {

    public RenderKCRSingleTicketMachine(BlockEntityRenderDispatcher dispatcher) {
        super(dispatcher);
    }

    public void render(BlockKCRSingleTicketMachine.TileEntityKCRSingleTicketMachine blockEntity, float f, PoseStack poseStack, MultiBufferSource multiBufferSource, int i, int j) {

    }
}
