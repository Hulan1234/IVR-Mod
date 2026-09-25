package net.hulan.ivr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.mappings.EntityRendererMapper;
import net.hulan.ivr.entity.NPC;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public class NPCSteveRenderer<T extends NPC> extends EntityRendererMapper<T> {

    private static final float MODEL_SCALE = 1.0F;
    private static final float HEAD_SCALE = 1.0F / MODEL_SCALE;
    private static final float NAME_TAG_VERTICAL_OFFSET = 0F;
    private static final ResourceLocation STEVE_TEXTURE = DefaultPlayerSkin.getDefaultSkin(
            UUID.nameUUIDFromBytes("Steve".getBytes())
    );

    private final PlayerModel<T> model = new PlayerModel<>(0, false);

    public NPCSteveRenderer(Object context) {
        super(context);
        model.hat.visible = true;
    }

    public void render(T entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light) {
        model.setupAnim(entity, 0, 0, partialTick, yaw, 0.0F);
        poseStack.pushPose();
        poseStack.scale(-MODEL_SCALE, -MODEL_SCALE, MODEL_SCALE);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        VertexConsumer vertexConsumer = bufferSource.getBuffer(
                RenderType.entityTranslucent(getTextureLocation(entity))
        );
        model.renderToBuffer(
                poseStack,
                vertexConsumer,
                light,
                OverlayTexture.NO_OVERLAY,
                1,
                1,
                1,
                1);
        poseStack.popPose();
        renderAdjustedNameTag(entity, poseStack, bufferSource, light);
    }

    private void renderAdjustedNameTag(T entity, PoseStack poseStack, MultiBufferSource bufferSource, int light) {
        poseStack.pushPose();
        poseStack.translate(0.0F, NAME_TAG_VERTICAL_OFFSET, 0.0F);
        renderNameTag(entity, entity.getName(), poseStack, bufferSource, light);
        poseStack.popPose();
    }
`r`n    public ResourceLocation getTextureLocation(T entity) {
        ResourceLocation texture = entity.getTexture();
        return texture != null && Minecraft.getInstance().getResourceManager().hasResource(texture)
                ? texture
                : STEVE_TEXTURE;
    }
}

