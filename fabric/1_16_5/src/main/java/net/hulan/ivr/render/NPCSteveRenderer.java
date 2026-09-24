package net.hulan.ivr.render;

import com.mojang.blaze3d.vertex.PoseStack;
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

    private static final float MODEL_SCALE = 1.2F;
    private static final ResourceLocation STEVE_TEXTURE = DefaultPlayerSkin.getDefaultSkin(
            UUID.nameUUIDFromBytes("Steve".getBytes())
    );

    private final PlayerModel<T> model = new PlayerModel<>(0, false);

    public NPCSteveRenderer(Object context) {
        super(context);
    }

    public void render(T entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light) {
        model.setupAnim(entity, 0, 0, partialTick, yaw, 0.0F);
        poseStack.pushPose();
        poseStack.scale(-MODEL_SCALE, -MODEL_SCALE, MODEL_SCALE);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        model.renderToBuffer(
                poseStack,
                bufferSource.getBuffer(RenderType.entityTranslucent(getTextureLocation(entity))),
                light,
                OverlayTexture.NO_OVERLAY,
                1,
                1,
                1,
                1
        );
        poseStack.popPose();
    }

    public ResourceLocation getTextureLocation(T entity) {
        ResourceLocation texture = entity.getTexture();
        return texture != null && Minecraft.getInstance().getResourceManager().hasResource(texture)
                ? texture
                : STEVE_TEXTURE;
    }
}
