package net.hulan.ksd.mixin;

import mtr.SoundEvents;
import mtr.block.BlockDirectionalDoubleBlockBase;
import mtr.block.BlockTicketBarrier;
import mtr.block.BlockTicketProcessor;
import mtr.block.IBlock;
import mtr.data.TicketSystem;
import mtr.mappings.Utilities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static mtr.block.BlockTicketProcessor.LIGHTS;
import static mtr.block.IBlock.HALF;

@Mixin(BlockTicketProcessor.class)
public class BlockTicketProcessorMixin extends BlockDirectionalDoubleBlockBase {


    @Shadow
    public boolean canEnter;

    @Shadow
    public boolean canExit;

    public BlockTicketProcessorMixin(Properties settings) {
        super(settings);
    }

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (!world.isClientSide && IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER) {
            TicketSystem.EnumTicketBarrierOpen open = TicketSystem.passThrough(world, pos, player, canEnter, canExit, SoundEvents.TICKET_PROCESSOR_ENTRY, SoundEvents.TICKET_PROCESSOR_ENTRY_CONCESSIONARY, SoundEvents.TICKET_PROCESSOR_EXIT, SoundEvents.TICKET_PROCESSOR_EXIT_CONCESSIONARY, SoundEvents.TICKET_PROCESSOR_FAIL, true);
            world.setBlockAndUpdate(pos, state.setValue(LIGHTS, open.isOpen() ? BlockTicketProcessor.EnumTicketProcessorLights.GREEN : BlockTicketProcessor.EnumTicketProcessorLights.RED));
            Utilities.scheduleBlockTick(world, pos, this, 20);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
