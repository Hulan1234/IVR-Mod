package net.hulan.ivr.item;

import mtr.CreativeModeTabs;
import mtr.mappings.RegistryUtilities;
import net.hulan.ivr.entity.InteractiveNPC;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.hulan.ksd.utils.NPCUtilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

public class ItemNPCSpawnEgg extends SpawnEggItem {

    public static final String TICKETS_ROLE = "tickets";
    public static final String FA_ROLE = "fare_adjustments";
    private final EntityType<? extends InteractiveNPC> npcType;
    public final CreativeModeTabs.Wrapper creativeModeTab;

    public ItemNPCSpawnEgg(mtr.RegistryObject<? extends EntityType<? extends InteractiveNPC>> entityType, int backgroundColor, int highlightColor, CreativeModeTabs.Wrapper creativeModeTab) {
        super(entityType.get(), backgroundColor, highlightColor, RegistryUtilities.createItemProperties(creativeModeTab::get));
        this.npcType = entityType.get();
        this.creativeModeTab = creativeModeTab;
    }

    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        InteractiveNPC interactiveNpc = npcType.create(level);
        if (interactiveNpc == null) {
            return InteractionResult.FAIL;
        }
        float facing = 180.0F;
        if (context.getPlayer() != null) {
            facing = NPCUtilities.getYaw(context.getPlayer()) + 180.0F;
        }
        interactiveNpc.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, facing, 0.0F);
        interactiveNpc.setPersistenceRequired();
        level.addFreshEntity(interactiveNpc);
        ItemStack stack = context.getItemInHand();
        if (context.getPlayer() == null || !NPCUtilities.isCreative(context.getPlayer())) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
