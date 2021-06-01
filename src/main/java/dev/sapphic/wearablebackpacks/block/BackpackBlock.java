package dev.sapphic.wearablebackpacks.block;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dev.sapphic.wearablebackpacks.Backpack;
import dev.sapphic.wearablebackpacks.advancement.BackpackCriteriaTriggers;
import dev.sapphic.wearablebackpacks.block.entity.BackpackBlockEntity;
import dev.sapphic.wearablebackpacks.item.BackpackItem;
import dev.sapphic.wearablebackpacks.mixin.BucketItemAccessor;
import dev.sapphic.wearablebackpacks.mixin.DyeColorAccessor;
import dev.sapphic.wearablebackpacks.stat.BackpackStats;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BucketItem;
import net.minecraft.item.DyeItem;
import net.minecraft.item.DyeableItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

public final class BackpackBlock extends BlockWithEntity implements Waterloggable {
  public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
  public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

  private static final Map<Direction, VoxelShape> SHAPES = ImmutableMap.of(
    Direction.NORTH, VoxelShapes.union(cube(3.0, 0.0, 6.0, 13.0, 12.0, 11.0), cube(4.0, 1.0, 4.0, 12.0, 7.0, 6.0)),
    Direction.EAST, VoxelShapes.union(cube(5.0, 0.0, 3.0, 10.0, 12.0, 13.0), cube(10.0, 1.0, 4.0, 12.0, 7.0, 12.0)),
    Direction.SOUTH, VoxelShapes.union(cube(3.0, 0.0, 5.0, 13.0, 12.0, 10.0), cube(4.0, 1.0, 10.0, 12.0, 7.0, 12.0)),
    Direction.WEST, VoxelShapes.union(cube(6.0, 0.0, 3.0, 11.0, 12.0, 13.0), cube(4.0, 1.0, 4.0, 6.0, 7.0, 12.0))
  );

  public BackpackBlock(final Settings settings) {
    super(settings);
    this.setDefaultState(this.stateManager.getDefaultState()
      .with(FACING, Direction.NORTH).with(WATERLOGGED, false));
  }

  // Block.createCuboidShape but a sane, concise name
  private static VoxelShape cube(
    final double x0, final double y0, final double z0, final double x1, final double y1, final double z1
  ) {
    final double v = 16.0;
    return VoxelShapes.cuboid(x0 / v, y0 / v, z0 / v, x1 / v, y1 / v, z1 / v);
  }

  @Override
  @Deprecated
  public BlockState getStateForNeighborUpdate(
    final BlockState state, final Direction side, final BlockState neighbor, final WorldAccess world,
    final BlockPos pos, final BlockPos offset
  ) {
    if (state.get(WATERLOGGED)) {
      world.getFluidTickScheduler().schedule(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
    }
    return super.getStateForNeighborUpdate(state, side, neighbor, world, pos, offset);
  }

  @Override
  @Deprecated
  public void onStateReplaced(
    final BlockState state, final World world, final BlockPos pos, final BlockState next, final boolean moved
  ) {
    if (state.getBlock() != next.getBlock()) {
      final @Nullable BlockEntity be = world.getBlockEntity(pos);
      if (be instanceof BackpackBlockEntity) {
        ItemScatterer.spawn(world, pos, (Inventory) be);
        world.updateComparators(pos, this);
      }
      super.onStateReplaced(state, world, pos, next, moved);
    }
  }

  @Override
  @Deprecated
  public ActionResult onUse(
    final BlockState state, final World world, final BlockPos pos, final PlayerEntity player, final Hand hand,
    final BlockHitResult hit
  ) {
    final @Nullable BlockEntity be = world.getBlockEntity(pos);
    if (be instanceof BackpackBlockEntity) {
      final ItemStack stack = player.getStackInHand(hand);
      final Backpack backpack = (Backpack) be;
      if (stack.getItem() instanceof DyeItem) {
        if (!world.isClient) {
          final int newColor = this.getBlendedColor(backpack, (DyeItem) stack.getItem());
          if (!backpack.hasColor() || (backpack.getColor() != newColor)) {
            if (!world.canPlayerModifyAt(player, pos)) {
              return ActionResult.FAIL;
            }
            backpack.setColor(newColor);
            world.playSound(null, pos, SoundEvents.ENTITY_ITEM_FRAME_ADD_ITEM, player.getSoundCategory(),
              0.5F, (player.world.random.nextFloat() * 0.1F) + 0.9F
            );
            if (!player.abilities.creativeMode) {
              stack.decrement(1);
            }
            BackpackCriteriaTriggers.DYED.trigger((ServerPlayerEntity) player);
          }
        }
        return ActionResult.success(world.isClient);
      }
      if (backpack.hasColor() && (stack.getItem() instanceof BucketItem)) {
        //noinspection CastToIncompatibleInterface
        final BucketItemAccessor bucket = (BucketItemAccessor) stack.getItem();
        if (bucket.getFluid().isIn(FluidTags.WATER)) {
          if (!world.canPlayerModifyAt(player, pos)) {
            return ActionResult.FAIL;
          }
          if (!world.isClient) {
            player.setStackInHand(hand, bucket.callGetEmptiedStack(stack, player));
            bucket.invokePlayEmptyingSound(null, world, pos);
            backpack.clearColor();
            player.incrementStat(BackpackStats.CLEANED);
          }
          return ActionResult.success(world.isClient);
        }
      }
      player.openHandledScreen((NamedScreenHandlerFactory) be);
      player.incrementStat(BackpackStats.OPENED);
      return ActionResult.success(world.isClient);
    }
    return ActionResult.FAIL;
  }

  @Override
  @Deprecated
  public FluidState getFluidState(final BlockState state) {
    return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
  }

  @Override
  @Deprecated
  public boolean hasComparatorOutput(final BlockState state) {
    return true;
  }

  @Override
  @Deprecated
  public BlockState rotate(final BlockState state, final BlockRotation rotation) {
    return state.with(FACING, rotation.rotate(state.get(FACING)));
  }

  @Override
  @Deprecated
  public BlockState mirror(final BlockState state, final BlockMirror mirror) {
    return state.rotate(mirror.getRotation(state.get(FACING)));
  }

  @Override
  @Deprecated
  public int getComparatorOutput(final BlockState state, final World world, final BlockPos pos) {
    return ScreenHandler.calculateComparatorOutput(world.getBlockEntity(pos));
  }

  @Override
  @Deprecated
  public VoxelShape getOutlineShape(
    final BlockState state, final BlockView view, final BlockPos pos, final ShapeContext context
  ) {
    return SHAPES.get(state.get(FACING));
  }

  @Override
  @Deprecated
  public float calcBlockBreakingDelta(
    final BlockState state, final PlayerEntity player, final BlockView world, final BlockPos pos
  ) {
    if (player.isSneaking() && !(player.getEquippedStack(EquipmentSlot.CHEST).getItem() instanceof BackpackItem)) {
      return super.calcBlockBreakingDelta(state, player, world, pos);
    }
    return 0.005F;
  }

  @Override
  public @Nullable BlockState getPlacementState(final ItemPlacementContext context) {
    final Direction facing = context.getPlayerFacing().getOpposite();
    final Fluid fluid = context.getWorld().getFluidState(context.getBlockPos()).getFluid();
    return this.getDefaultState().with(FACING, facing).with(WATERLOGGED, fluid == Fluids.WATER);
  }

  @Override
  public void afterBreak(final World world, final PlayerEntity player, final BlockPos pos, final BlockState state, final @org.jetbrains.annotations.Nullable BlockEntity blockEntity, final ItemStack stack) {
    player.incrementStat(Stats.MINED.getOrCreateStat(this));
    player.addExhaustion(0.005F);
  }

  @Override
  public ItemStack getPickStack(final BlockView world, final BlockPos pos, final BlockState state) {
    return this.getPickStack(world.getBlockEntity(pos), world, pos, state);
  }

  @Override
  public void onBreak(final World world, final BlockPos pos, final BlockState state, final PlayerEntity player) {
    final @Nullable BlockEntity be = world.getBlockEntity(pos);
    if ((be instanceof BackpackBlockEntity) && player.isSneaking() && !player.hasStackEquipped(EquipmentSlot.CHEST)) {
      player.equipStack(EquipmentSlot.CHEST, this.getPickStack(be, world, pos, state));
      world.removeBlockEntity(pos);
    }
    super.onBreak(world, pos, state, player);
  }

  @Override
  protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
    builder.add(FACING, WATERLOGGED);
  }

  @Override
  public BlockRenderType getRenderType(final BlockState state) {
    return BlockRenderType.ENTITYBLOCK_ANIMATED;
  }

  @Override
  public BlockEntity createBlockEntity(final BlockView view) {
    return new BackpackBlockEntity();
  }

  private int getBlendedColor(final Backpack backpack, final DyeItem dye) {
    if (backpack.hasColor()) {
      final ItemStack tmp = new ItemStack(this);
      final DyeableItem item = (DyeableItem) tmp.getItem();
      item.setColor(tmp, backpack.getColor());
      return item.getColor(DyeableItem.blendAndSetColor(tmp, ImmutableList.of(dye)));
    }
    //noinspection ConstantConditions
    return ((DyeColorAccessor) (Object) dye.getColor()).getColor();
  }

  private ItemStack getPickStack(
    final @Nullable BlockEntity be, final BlockView world, final BlockPos pos, final BlockState state
  ) {
    final ItemStack stack = super.getPickStack(world, pos, state);
    if (be instanceof BackpackBlockEntity) {
      ((BackpackBlockEntity) be).saveTo(stack);
    }
    return stack;
  }
}
