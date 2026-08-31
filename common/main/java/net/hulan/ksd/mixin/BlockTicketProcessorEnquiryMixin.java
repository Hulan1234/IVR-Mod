package net.hulan.ksd.mixin;

import mtr.SoundEvents;
import mtr.block.BlockTicketProcessorEnquiry;
import mtr.mappings.Text;
import net.hulan.ksd.data.KCRSingleTicketSystem;
import net.hulan.ksd.data.KSDRailwayData;
import net.hulan.ksd.data.OctopusSystem;
import net.hulan.ksd.item.ItemOctopus;
import net.hulan.ksd.item.ItemSingleTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockTicketProcessorEnquiry.class)
public class BlockTicketProcessorEnquiryMixin {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (!world.isClientSide) {
            ItemStack itemInHand = player.getItemInHand(interactionHand);
            if (itemInHand.getItem() instanceof ItemSingleTicket) {
                player.displayClientMessage(Text.literal(KCRSingleTicketSystem.getPrintedData(itemInHand)), false);
                world.playSound(null, pos, SoundEvents.TICKET_PROCESSOR_ENTRY, SoundSource.BLOCKS, 1.0F, 1.0F);
                cir.setReturnValue(InteractionResult.SUCCESS);
            } else if (itemInHand.getItem() instanceof ItemOctopus) {
                KSDRailwayData railwayData = KSDRailwayData.getInstance(world);
                if (railwayData != null) {
                    player.displayClientMessage(Text.literal(OctopusSystem.readPrintableData(itemInHand, railwayData.jsonDataManager)), false);
                    world.playSound(null, pos, SoundEvents.TICKET_PROCESSOR_ENTRY, SoundSource.BLOCKS, 1.0F, 1.0F);
                    cir.setReturnValue(InteractionResult.SUCCESS);
                }
            }
        }
    }
}
