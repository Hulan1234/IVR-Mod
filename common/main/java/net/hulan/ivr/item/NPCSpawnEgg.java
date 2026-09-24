package net.hulan.ivr.item;

import mtr.CreativeModeTabs;
import mtr.mappings.RegistryUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.hulan.ivr.entity.NPC;
import net.hulan.ivr.platform.NPCPlatform;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

public class NPCSpawnEgg extends SpawnEggItem {

    public static final String TICKETS_ROLE = "tickets";
    public static final String FA_ROLE = "fare_adjustments";

    public NPCSpawnEgg(mtr.RegistryObject<? extends EntityType<? extends NPC>> entityType, int backgroundColor, int highlightColor, CreativeModeTabs.Wrapper creativeModeTab) {
        super(entityType.get(), backgroundColor, highlightColor, RegistryUtilities.createItemProperties(creativeModeTab::get));
    }

    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        NPC npc = (NPC) getType(context.getItemInHand().getTag()).create(level);
        if (npc == null) {
            return InteractionResult.FAIL;
        }
        float facing = 180.0F;
        if (context.getPlayer() != null) {
            facing = NPCPlatform.getYaw(context.getPlayer()) + 180.0F;
        }
        npc.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, facing, 0.0F);
        level.addFreshEntity(npc);
        ItemStack stack = context.getItemInHand();
        if (!NPCPlatform.isCreative(context.getPlayer())) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
