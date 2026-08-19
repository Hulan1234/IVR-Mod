package net.hulan.ksd.mixin;

import mtr.block.BlockTicketBarrier;
import mtr.block.IBlock;
import mtr.data.TicketSystem;
import mtr.mappings.BlockDirectionalMapper;
import net.hulan.ksd.KSDItems;
import net.hulan.ksd.data.KCRTicketSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static mtr.block.BlockTicketBarrier.OPEN;

@Mixin(BlockTicketBarrier.class)
public class BlockTicketBarrierMixin extends BlockDirectionalMapper {

    @Shadow(remap = false)
    @Final
    private boolean isEntrance;

    public BlockTicketBarrierMixin(Properties properties) {
        super(properties);
    }

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    public void entityInside(CallbackInfo ci){
        ci.cancel();
    }

    @SuppressWarnings("deprecation")
    public @NotNull InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
        return IBlock.checkHoldingItem(world, player, item -> {
            if (!world.isClientSide) {
                TicketSystem.EnumTicketBarrierOpen canOpen = KCRTicketSystem.singleTicketCheck(world, player, pos, player.getItemInHand(interactionHand), isEntrance);
                world.setBlockAndUpdate(pos, state.setValue(OPEN, canOpen));
                if (canOpen.isOpen() && !world.getBlockTicks().hasScheduledTick(pos, this)) {
                    mtr.mappings.Utilities.scheduleBlockTick(world, pos, this, 40);
                }
            }
        }, null, KSDItems.SINGLE_TICKET.get());
    }
}
