package net.hulan.ivr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.mappings.EntityRendererMapper;
import net.hulan.ivr.entity.NPC;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class NPCSteveRenderer<T extends NPC> extends EntityRendererMapper<T> {

    private static final float MODEL_SCALE = 1.0F;
    private static final float HEAD_SCALE = 1.0F / MODEL_SCALE;
    private static final float NAME_TAG_VERTICAL_OFFSET = 0F;
    private static final ResourceLocation STEVE = DefaultPlayerSkin.getDefaultSkin(UUID.nameUUIDFromBytes("Steve".getBytes()));
    private final PlayerModel<T> model;

    public NPCSteveRenderer (Object c){
        super(c);
        model = new PlayerModel<>(((EntityRendererProvider.Context)c).bakeLayer(ModelLayers.PLAYER), false);
        model.hat.visible = true;
    }

    public void render(@NotNull T e, float y, float p, PoseStack s, MultiBufferSource b, int l){
        model.setupAnim(e, 0, 0, p, y, e.getXRot());
        s.pushPose();
        s.scale(-MODEL_SCALE, -MODEL_SCALE, MODEL_SCALE);
        s.translate(0.0F, -1.501F, 0.0F);
        VertexConsumer vertexConsumer = b.getBuffer(
                RenderType.entityTranslucent(getTextureLocation(e))
        );

        model.renderToBuffer(
                s,
                vertexConsumer,
                l,
                OverlayTexture.NO_OVERLAY,
                1,
                1,
                1,
                1);
        s.popPose();
        renderAdjustedNameTag(e, s, b, l);
    }

    private void renderAdjustedNameTag(T entity, PoseStack poseStack, MultiBufferSource bufferSource, int light) {
        poseStack.pushPose();
        poseStack.translate(0.0F, NAME_TAG_VERTICAL_OFFSET, 0.0F);
        renderNameTag(entity, entity.getName(), poseStack, bufferSource, light);
        poseStack.popPose();
    }
`r`n    public @NotNull ResourceLocation getTextureLocation(T e) {
        ResourceLocation texture = e.getTexture();
        return texture == null ? STEVE : texture;
    }
}

