package net.hulan.ivr.render;

import net.hulan.ivr.render_promotion.cache.VertexMesh;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
import net.hulan.ivr.render_promotion.cache.VertexRecorder;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
import net.hulan.ivr.render_promotion.mesh.GpuInstanceBatch;
import mtr.render.MoreRenderLayers;
import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.MTRClient;
import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.IBlock;
import mtr.block.ITripleBlock;
import mtr.data.IGui;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.mappings.ModelDataWrapper;
import mtr.mappings.ModelMapper;
import mtr.mappings.UtilitiesClient;
import mtr.render.RenderTrains;
import mtr.render.StoredMatrixTransformations;
import net.hulan.ivr.block.BlockKCRAPGDoor;
import net.hulan.ivr.block.BlockKCRAPGGlassEnd;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.NotNull;

public class RenderKCRPSDAPGDoor<T extends BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase> extends BlockEntityRendererMapper<T> implements IGui, IBlock {

    private final int type;
    private static final ModelSingleCube MODEL_PSD = new ModelSingleCube(36, 18, 0, 0, 0, 16, 16, 2);
    private static final ModelSingleCube MODEL_PSD_END_LEFT_1 = new ModelSingleCube(20, 18, 0, 0, 0, 8, 16, 2);
    private static final ModelSingleCube MODEL_PSD_END_RIGHT_1 = new ModelSingleCube(20, 18, 8, 0, 0, 8, 16, 2);
    private static final ModelSingleCube MODEL_PSD_END_LEFT_2 = new ModelSingleCube(20, 18, 8, 0, 2, 8, 16, 2);
    private static final ModelSingleCube MODEL_PSD_END_RIGHT_2 = new ModelSingleCube(20, 18, 0, 0, 2, 8, 16, 2);
    private static final ModelSingleCube MODEL_PSD_LIGHT_LEFT = new ModelSingleCube(16, 16, 0, -1, 5, 1, 1, 1);
    private static final ModelSingleCube MODEL_PSD_LIGHT_RIGHT = new ModelSingleCube(16, 16, 15, -1, 5, 1, 1, 1);
    private static final ModelSingleCube MODEL_APG_TOP = new ModelSingleCube(34, 9, 0, 8, 1, 16, 8, 1);
    private static final ModelAPGDoorBottom MODEL_APG_BOTTOM = new ModelAPGDoorBottom();
    private static final ModelAPGDoorLight MODEL_APG_LIGHT = new ModelAPGDoorLight();
    private static final ModelSingleCube MODEL_APG_DOOR_LOCKED = new ModelSingleCube(6, 6, 5, 10, 1, 6, 6, 0);
    private static final ModelSingleCube MODEL_PSD_DOOR_LOCKED = new ModelSingleCube(6, 6, 5, 6, 1, 6, 6, 0);
    private static final ModelSingleCube MODEL_LIFT_LEFT = new ModelSingleCube(28, 18, 0, 0, 0, 12, 16, 2);
    private static final ModelSingleCube MODEL_LIFT_RIGHT = new ModelSingleCube(28, 18, 4, 0, 0, 12, 16, 2);

    public RenderKCRPSDAPGDoor(BlockEntityRenderDispatcher dispatcher, int type) {
        super(dispatcher);
        this.type = type;
    }

    @Override
    public void render(T entity,
                       float tickDelta,
                       @NotNull PoseStack matrices,
                       @NotNull MultiBufferSource vertexConsumers,
                       int light,
                       int overlay) {
        Level world = entity.getLevel();
        if (world != null) {
            BlockPos pos = entity.getBlockPos();
            if (!(Boolean)IBlock.getStatePropertySafe(world, pos, BlockPSDAPGDoorBase.TEMP)) {
                Direction facing = IBlock.getStatePropertySafe(world, pos, BlockPSDAPGDoorBase.FACING);
                boolean side = IBlock.getStatePropertySafe(world, pos, BlockPSDAPGDoorBase.SIDE) == EnumSide.RIGHT;
                boolean half = IBlock.getStatePropertySafe(world, pos, BlockPSDAPGDoorBase.HALF) == DoubleBlockHalf.UPPER;
                boolean end = IBlock.getStatePropertySafe(world, pos, BlockPSDAPGDoorBase.END);
                boolean unlocked = IBlock.getStatePropertySafe(world, pos, BlockPSDAPGDoorBase.UNLOCKED);
                float open = Math.min(entity.getOpen(MTRClient.getLastFrameDuration()), type >= 3 ? 0.75F : 1.0F);
                StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations();
                storedMatrixTransformations.add((matricesNew) -> {
                    matricesNew.translate(0.5D + (double)entity.getBlockPos().getX(), entity.getBlockPos().getY(), 0.5D + (double)entity.getBlockPos().getZ());
                    UtilitiesClient.rotateYDegrees(matricesNew, -facing.toYRot());
                    UtilitiesClient.rotateXDegrees(matricesNew, 180.0F);
                });
                StoredMatrixTransformations storedMatrixTransformationsLight = storedMatrixTransformations.copy();
                switch(type) {
                    case 0:
                    case 1:
                        if (half) {
                            RenderTrains.scheduleRender(new ResourceLocation(String.format("mtr:textures/block/light_%s.png", open > 0.0F ? "on" : "off")), false, open > 0.0F ? RenderTrains.QueuedRenderLayer.LIGHT : RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                                storedMatrixTransformationsLight.transform(matricesNew);
                                renderCached((side ? MODEL_PSD_LIGHT_RIGHT : MODEL_PSD_LIGHT_LEFT), matricesNew, vertexConsumer, light, overlay);
                                matricesNew.popPose();
                            });
                        }
                        if (end) {
                            RenderTrains.scheduleRender(new ResourceLocation(String.format("%s:textures/block/%s" + "psd_door_end_%s_%s_2_%s.png", type == 1 ? "ivr" : "mtr", this.type == 1 ? "kcr_" : "", half ? "top" : "bottom", side ? "right" : "left", this.type == 1 ? "2" : "1")), false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                                storedMatrixTransformationsLight.transform(matricesNew);
                                matricesNew.translate(open / 2.0F * (float)(side ? -1 : 1), 0.0D, 0.0D);
                                renderCached((side ? MODEL_PSD_END_RIGHT_2 : MODEL_PSD_END_LEFT_2), matricesNew, vertexConsumer, light, overlay);
                                matricesNew.popPose();
                            });
                        }
                        break;
                    case 2:
                        if (half) {
                            Block block = world.getBlockState(pos.relative(side ? facing.getClockWise() : facing.getCounterClockWise())).getBlock();
                            if (block instanceof BlockKCRAPGDoor || block instanceof BlockKCRAPGGlassEnd) {
                                RenderTrains.scheduleRender(new ResourceLocation(String.format("ivr:textures/block/kcr_apg_door_light_%s.png", open > 0.0F ? "on" : "off")), false, open > 0.0F ? RenderTrains.QueuedRenderLayer.LIGHT_TRANSLUCENT : RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                                    storedMatrixTransformationsLight.transform(matricesNew);
                                    matricesNew.translate(side ? -0.515625D : 0.515625D, 0.0D, 0.0D);
                                    matricesNew.scale(0.5F, 1.0F, 1.0F);
                                    renderCached(MODEL_APG_LIGHT, matricesNew, vertexConsumer, light, overlay);
                                    matricesNew.popPose();
                                });
                            }
                        }
                }
                storedMatrixTransformations.add((matricesNew) -> matricesNew.translate(open * (float)(side ? -1 : 1), 0.0D, 0.0D));
                switch(this.type) {
                    case 0:
                    case 1:
                        if (end) {
                            RenderTrains.scheduleRender(new ResourceLocation(String.format("%s:textures/block/%s" + "psd_door_end_%s_%s_1_%s.png", this.type == 1 ? "ivr" : "mtr", this.type == 1 ? "kcr_" : "", half ? "top" : "bottom", side ? "right" : "left", this.type == 1 ? "2" : "1")), false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                                storedMatrixTransformations.transform(matricesNew);
                                renderCached((side ? MODEL_PSD_END_RIGHT_1 : MODEL_PSD_END_LEFT_1), matricesNew, vertexConsumer, light, overlay);
                                matricesNew.popPose();
                            });
                        } else {
                            ResourceLocation texture = new ResourceLocation(String.format("ivr:textures/block/kcr_psd_door_%s_%s_%s.png", half ? "top" : "bottom", side ? "right" : "left", this.type == 1 ? "2" : "1"));
                            RenderTrains.scheduleRender(texture, false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                                storedMatrixTransformations.transform(matricesNew);
                                renderCachedExterior(MODEL_PSD, texture, matricesNew, vertexConsumer, light, overlay);
                                matricesNew.popPose();
                            });
                        }
                        if (half && !unlocked) {
                            RenderTrains.scheduleRender(new ResourceLocation("ivr:textures/block/sign/door_not_in_use.png"), false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                                storedMatrixTransformations.transform(matricesNew);
                                if (end) {
                                    matricesNew.translate(side ? 0.25D : -0.25D, 0.0D, 0.0D);
                                }

                                renderCached(MODEL_PSD_DOOR_LOCKED, matricesNew, vertexConsumer, light, overlay);
                                matricesNew.popPose();
                            });
                        }
                        break;
                    case 2:
                        ResourceLocation texture = new ResourceLocation(String.format("ivr:textures/block/kcr_apg_door_%s_%s.png", half ? "top" : "bottom", side ? "right" : "left"));
                        RenderTrains.scheduleRender(texture, false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                            storedMatrixTransformations.transform(matricesNew);
                            renderCachedExterior((half ? MODEL_APG_TOP : MODEL_APG_BOTTOM), texture, matricesNew, vertexConsumer, light, overlay);
                            matricesNew.popPose();
                        });
                        if (half && !unlocked) {
                            RenderTrains.scheduleRender(new ResourceLocation("ivr:textures/block/sign/door_not_in_use.png"), false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                                storedMatrixTransformations.transform(matricesNew);
                                renderCached(MODEL_APG_DOOR_LOCKED, matricesNew, vertexConsumer, light, overlay);
                                matricesNew.popPose();
                            });
                        }
                        break;
                    case 4:
                        if (IBlock.getStatePropertySafe(world, pos, ITripleBlock.ODD)) {
                            break;
                        }
                        storedMatrixTransformations.add((matricesNew) -> matricesNew.translate(side ? 0.5D : -0.5D, 0.0D, 0.0D));
                    case 3:
                        RenderTrains.scheduleRender(new ResourceLocation(String.format("mtr:textures/block/lift_door_%s_%s_1.png", half ? "top" : "bottom", side ? "right" : "left")), false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                            storedMatrixTransformations.transform(matricesNew);
                            renderCached((side ? MODEL_LIFT_RIGHT : MODEL_LIFT_LEFT), matricesNew, vertexConsumer, light, overlay);
                            matricesNew.popPose();
                        });
                        if (half && !unlocked) {
                            RenderTrains.scheduleRender(new ResourceLocation("ivr:textures/block/sign/door_not_in_use.png"), false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
                                storedMatrixTransformations.transform(matricesNew);
                                matricesNew.translate(side ? 0.125D : -0.125D, 0.0D, 0.0D);
                                renderCached(MODEL_PSD_DOOR_LOCKED, matricesNew, vertexConsumer, light, overlay);
                                matricesNew.popPose();
                            });
                        }
                }
            }
        }
    }

    private static final List<EntityModel<Entity>> LOCAL_MODELS = List.of(
            MODEL_PSD, MODEL_PSD_END_LEFT_1, MODEL_PSD_END_RIGHT_1,
            MODEL_PSD_END_LEFT_2, MODEL_PSD_END_RIGHT_2, MODEL_PSD_LIGHT_LEFT,
            MODEL_PSD_LIGHT_RIGHT, MODEL_APG_TOP, MODEL_APG_BOTTOM, MODEL_APG_LIGHT,
            MODEL_APG_DOOR_LOCKED, MODEL_PSD_DOOR_LOCKED, MODEL_LIFT_LEFT, MODEL_LIFT_RIGHT);

    /** Only explicitly known static EXTERIOR door bodies enter the optional collector. */
    private static void renderCachedExterior(EntityModel<Entity> model, ResourceLocation texture,
                                              PoseStack matrices, VertexConsumer consumer, int light, int overlay) {
        VertexMeshKey key = new VertexMeshKey("ivr:door", LOCAL_MODELS.indexOf(model), 0);
        VertexMesh mesh = RenderCacheLifecycle.get(key);
        if (mesh != null && !RenderCacheLifecycle.isReloading()) {
            GpuInstanceBatch.submitOrFallback(MoreRenderLayers.getExterior(texture), texture,
                    key, mesh, matrices, light, overlay, consumer);
        } else if (!RenderCacheLifecycle.shouldAttemptBuild(key)) {
            model.renderToBuffer(matrices, consumer, light, overlay, 1, 1, 1, 1);
        } else {
            renderCached(model, matrices, consumer, light, overlay);
        }
    }

    /** Only static local model geometry is recorded; animation and lighting stay live. */
    private static void renderCached(EntityModel<Entity> model, PoseStack matrices,
                                     VertexConsumer consumer, int light, int overlay) {
        VertexMeshKey key = new VertexMeshKey("ivr:door", LOCAL_MODELS.indexOf(model), 0);
        VertexMesh mesh = RenderCacheLifecycle.get(key);
        if (mesh != null) {
            mesh.replay(matrices, consumer, light, overlay);
        } else if (RenderCacheLifecycle.isReloading() || RenderCacheLifecycle.isPending(key)) {
            model.renderToBuffer(matrices, consumer, light, overlay, 1, 1, 1, 1);
        } else if (!RenderCacheLifecycle.shouldAttemptBuild(key)) {
            model.renderToBuffer(matrices, consumer, light, overlay, 1, 1, 1, 1);
        } else {
            // ModelMapper is only touched here on the client render thread.
            VertexRecorder recorder = new VertexRecorder();
            model.renderToBuffer(new PoseStack(), recorder, 0, 0, 1, 1, 1, 1);
            List<VertexMesh.Vertex> snapshot = recorder.snapshot();
            RenderCacheLifecycle.request(key, snapshot);
            new VertexMesh(snapshot).replay(matrices, consumer, light, overlay);
        }
    }
    public boolean shouldRenderOffScreen(@NotNull T blockEntity) {
        return true;
    }

    private static class ModelSingleCube extends EntityModel<Entity> {

        private final ModelMapper cube;

        private ModelSingleCube(int textureWidth, int textureHeight, int x, int y, int z, int length, int height, int depth) {
            ModelDataWrapper modelDataWrapper = new ModelDataWrapper(this, textureWidth, textureHeight);
            this.cube = new ModelMapper(modelDataWrapper);
            this.cube.texOffs(0, 0).addBox((float)(x - 8), (float)(y - 16), (float)(z - 8), length, height, depth, 0.0F, false);
            modelDataWrapper.setModelPart(textureWidth, textureHeight);
            this.cube.setModelPart();
        }

        @Override
        public void renderToBuffer(@NotNull PoseStack matrices, @NotNull VertexConsumer vertices, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
            this.cube.render(matrices, vertices, 0.0F, 0.0F, 0.0F, packedLight, packedOverlay);
        }

        @Override
        public void setupAnim(@NotNull Entity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        }
    }

    private static class ModelAPGDoorBottom extends EntityModel<Entity> {

        private final ModelMapper bone;

        private ModelAPGDoorBottom() {
            ModelDataWrapper modelDataWrapper = new ModelDataWrapper(this, 34, 27);
            this.bone = new ModelMapper(modelDataWrapper);
            this.bone.texOffs(0, 0).addBox(-8.0F, -16.0F, -7.0F, 16, 16, 1, 0.0F, false);
            this.bone.texOffs(0, 17).addBox(-8.0F, -6.0F, -8.0F, 16, 6, 1, 0.0F, false);
            ModelMapper cube_r1 = new ModelMapper(modelDataWrapper);
            cube_r1.setPos(0.0F, -6.0F, -8.0F);
            this.bone.addChild(cube_r1);
            cube_r1.setRotationAngle(-0.7854F, 0.0F, 0.0F);
            cube_r1.texOffs(0, 24).addBox(-8.0F, -2.0F, 0.0F, 16, 2, 1, 0.0F, false);
            modelDataWrapper.setModelPart(34, 27);
            this.bone.setModelPart();
        }

        @Override
        public void renderToBuffer(@NotNull PoseStack matrices, @NotNull VertexConsumer vertices, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
            this.bone.render(matrices, vertices, 0.0F, 0.0F, 0.0F, packedLight, packedOverlay);
        }

        @Override
        public void setupAnim(@NotNull Entity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        }
    }

    private static class ModelAPGDoorLight extends EntityModel<Entity> {

        private final ModelMapper bone;

        private ModelAPGDoorLight() {
            ModelDataWrapper modelDataWrapper = new ModelDataWrapper(this, 8, 8);
            this.bone = new ModelMapper(modelDataWrapper);
            this.bone.texOffs(0, 4).addBox(-0.5F, -9.0F, -7.0F, 1, 1, 3, 0.05F, false);
            ModelMapper cube_r1 = new ModelMapper(modelDataWrapper);
            cube_r1.setPos(0.0F, -9.05F, -4.95F);
            this.bone.addChild(cube_r1);
            cube_r1.setRotationAngle(0.3927F, 0.0F, 0.0F);
            cube_r1.texOffs(0, 0).addBox(-0.5F, 0.05F, -3.05F, 1, 1, 3, 0.05F, false);
            modelDataWrapper.setModelPart(8, 8);
            this.bone.setModelPart();
        }

        @Override
        public void renderToBuffer(@NotNull PoseStack matrices, @NotNull VertexConsumer vertices, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
            this.bone.render(matrices, vertices, 0.0F, 0.0F, 0.0F, packedLight, packedOverlay);
        }

        @Override
        public void setupAnim(@NotNull Entity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        }
    }
}
