package net.hulan.ivr.render;

import com.mojang.blaze3d.vertex.PoseStack;
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

    private static final ResourceLocation STEVE = DefaultPlayerSkin.getDefaultSkin(UUID.nameUUIDFromBytes("Steve".getBytes()));
    private final PlayerModel<T> model;

    public NPCSteveRenderer (Object c){
        super(c);
        model = new PlayerModel<>(((EntityRendererProvider.Context)c).bakeLayer(ModelLayers.PLAYER), false);
    }

    public void render(@NotNull T e, float y, float p, PoseStack s, MultiBufferSource b, int l){
        model.setupAnim(e, 0, 0, p, y, e.getXRot());
        s.pushPose();
        s.scale(-1.2F, -1.2F, 1.2F);
        s.translate(0.0F, -1.501F, 0.0F);
        model.renderToBuffer(
                s,
                b.getBuffer(RenderType.entityTranslucent(getTextureLocation(e))),
                l,
                OverlayTexture.NO_OVERLAY,
                1,
                1,
                1,
                1);
        s.popPose();
    }

    public @NotNull ResourceLocation getTextureLocation(T e) {
        ResourceLocation texture = e.getTexture();
        return texture == null ? STEVE : texture;
    }
}
