/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Quarry process — spirals inward mining only specified blocks.
 * 
 * The process mines a rectangular tunnel strip along the current wall,
 * turns 90° when hitting non-mineable blocks, and after 4 turns (full
 * perimeter) shrinks inward by the tunnel width.
 *
 * @author GagarinDD
 */
public final class QuarryProcess extends BaritoneProcessHelper {

    // ============== State ==============

    /** Blocks to mine (from command args) */
    private BlockOptionalMetaLookup filter;
    /** Height of the tunnel */
    private int mineHeight;
    /** Width of the tunnel (also the step-in size after each full perimeter) */
    private int mineWidth;
    /** Current direction the quarry is facing */
    private Direction facing;
    /** Which wall we're mining (0-3 per full perimeter) */
    private int wallIndex;
    /** Current bounds of the quarry rectangle */
    private BlockPos corner1;
    private BlockPos corner2;
    /** The starting corner — where player stood when quarry was launched */
    private BlockPos homeCorner;
    /** Whether the process is paused */
    private boolean paused;
    /** Current target block to break */
    private BlockPos currentTarget;
    /** Number of completed perimeters (full 4-wall loops) */
    private int layersDone;

    // ============== Constructor ==============

    public QuarryProcess(Baritone baritone) {
        super(baritone);
    }

    // ============== Public API ==============

    public void quarry(int height, int width, BlockOptionalMetaLookup filter) {
        this.mineHeight = height;
        this.mineWidth = width;
        this.filter = filter;
        this.facing = ctx.player().getDirection();
        this.wallIndex = 0;
        this.layersDone = 0;
        this.paused = false;
        this.homeCorner = ctx.playerFeet();
        this.currentTarget = null;

        logDirect(String.format(
            "[Quarry] Starting %d×%d tunnel, facing %s, filter: %s",
            height, width, facing, filter.toString()
        ));
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    // ============== Tick ==============

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (paused || filter == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // --- STOP CONDITIONS ---

        // 1. Inventory check
        int minFree = Baritone.settings().quarryMinFreeSlots.value;
        if (minFree > 0) {
            long freeSlots = ctx.player().getInventory().items.stream()
                    .filter(ItemStack::isEmpty)
                    .count();
            if (freeSlots <= minFree) {
                logNotification(
                    String.format("[Quarry] ⛔ Inventory full! Free slots: %d (threshold: %d). Clear inventory and run #resume",
                        freeSlots, minFree),
                    true
                );
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // 2. Pickaxe check
        int minDur = Baritone.settings().quarryMinPickaxeDurability.value;
        if (minDur >= 0) {
            boolean hasPickaxe = ctx.player().getInventory().items.stream()
                    .filter(s -> !s.isEmpty() && s.getItem() instanceof PickaxeItem)
                    .anyMatch(s -> s.getMaxDamage() - s.getDamageValue() >= minDur);
            if (!hasPickaxe) {
                logNotification(
                    String.format("[Quarry] ⛔ No pickaxe with durability >= %d! Repair/craft and run #resume", minDur),
                    true
                );
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // --- Detect boundaries ---

        // If bounds not set, find them by scanning forward in current direction
        if (corner1 == null || corner2 == null) {
            detectBounds();
            if (corner1 == null || corner2 == null) {
                logDirect("[Quarry] Could not detect bounds — is there bedrock around?");
                cancel();
                return null;
            }
            logDirect(String.format("[Quarry] Bounds: %s → %s, %d×%d",
                corner1, corner2,
                Math.abs(corner2.getX() - corner1.getX()) + 1,
                Math.abs(corner2.getZ() - corner1.getZ()) + 1
            ));
        }

        // --- Find next block to mine ---

        currentTarget = findNextBlock();
        if (currentTarget == null) {
            // No mineable blocks in current strip — advance
            advanceStrip();
            currentTarget = findNextBlock();
            if (currentTarget == null) {
                // Truly nothing left to mine
                logNotification(String.format(
                    "[Quarry] ✅ Finished! %d layers, %d perimeters completed",
                    layersDone, layersDone
                ), false);
                cancel();
                return null;
            }
        }

        // --- Path to target and break ---

        BlockPos target = currentTarget;
        BlockState state = ctx.world().getBlockState(target);

        // Check if we can break it right now
        if (ctx.playerFeet().distSqr(target) < 25 && isSafeToCancel) {
            Optional<Rotation> rot = RotationUtils.reachable(ctx, target);
            if (rot.isPresent() && MovementHelper.switchToBestToolFor(ctx, state)) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                if (ctx.isLookingAt(target) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // Otherwise path to it
        return new PathingCommand(new GoalBlock(target), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    // ============== Bound Detection ==============

    /**
     * Scans forward to find the far boundary (bedrock or non-mineable block) in current direction.
     * Sets corner1 and corner2 based on the detected rectangle.
     */
    private void detectBounds() {
        BlockPos feet = ctx.playerFeet();
        int maxScan = 200; // don't scan forever

        // Scan forward until hitting non-mineable
        int farDist = 0;
        for (int i = 1; i <= maxScan; i++) {
            BlockPos check = feet.relative(facing, i);
            if (!isMineable(check)) {
                farDist = i - 1;
                break;
            }
        }
        if (farDist < mineWidth) {
            // Too close to boundary
            return;
        }

        // Try to find left and right boundaries by scanning perpendicular
        Direction left = rotateLeft(facing);
        int leftDist = 0;
        for (int i = 1; i <= maxScan; i++) {
            BlockPos check = feet.relative(left, i);
            boolean anyMineable = false;
            for (int dy = 0; dy < mineHeight; dy++) {
                if (isMineable(check.above(dy))) {
                    anyMineable = true;
                    break;
                }
            }
            if (!anyMineable) {
                leftDist = i - 1;
                break;
            }
        }

        Direction right = rotateLeft(rotateLeft(rotateLeft(left))); // opposite of left = right
        int rightDist = 0;
        for (int i = 1; i <= maxScan; i++) {
            BlockPos check = feet.relative(right, i);
            boolean anyMineable = false;
            for (int dy = 0; dy < mineHeight; dy++) {
                if (isMineable(check.above(dy))) {
                    anyMineable = true;
                    break;
                }
            }
            if (!anyMineable) {
                rightDist = i - 1;
                break;
            }
        }

        // Set corners
        BlockPos c1 = feet.relative(left, leftDist).relative(facing.getOpposite(), 0);
        BlockPos c2 = feet.relative(right, rightDist).relative(facing, farDist).above(mineHeight - 1);

        corner1 = new BlockPos(Math.min(c1.getX(), c2.getX()), c1.getY(), Math.min(c1.getZ(), c2.getZ()));
        corner2 = new BlockPos(Math.max(c1.getX(), c2.getX()), c2.getY(), Math.max(c1.getZ(), c2.getZ()));
    }

    // ============== Block Finding ==============

    /**
     * Find the next mineable block in the current tunnel strip along the current wall.
     */
    private BlockPos findNextBlock() {
        if (corner1 == null || corner2 == null) return null;

        BlockPos feet = ctx.playerFeet();
        int scanRadius = 10;

        // Search in current strip ahead
        for (int dist = 0; dist <= Math.max(Math.abs(corner2.getX() - corner1.getX()), Math.abs(corner2.getZ() - corner1.getZ())) + 10; dist++) {
            BlockPos check = feet.relative(facing, dist);

            // Check if this position is still within bounds
            if (!isWithinBounds(check)) {
                continue;
            }

            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = 0; dy < mineHeight; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos pos = check.offset(dx, dy, dz);
                        if (!isWithinBounds(pos)) continue;
                        if (ctx.playerFeet().distSqr(pos) > scanRadius * scanRadius) continue;

                        BlockState state = ctx.world().getBlockState(pos);
                        if (state.getBlock() instanceof AirBlock) continue;
                        if (filter.has(state) && !MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    // ============== Strip Advancement ==============

    /**
     * Advance to the next wall strip. Turn 90° left, or after 4 walls: shrink inward.
     */
    private void advanceStrip() {
        wallIndex++;
        if (wallIndex >= 4) {
            // Completed full perimeter → shrink inward
            wallIndex = 0;
            layersDone++;

            Direction rightDir = rotateLeft(rotateLeft(rotateLeft(facing)));
            BlockPos shrink = new BlockPos(
                corner1.getX() + mineWidth * Math.abs(facing.getStepX()),
                corner1.getY(),
                corner1.getZ() + mineWidth * Math.abs(facing.getStepZ())
            );

            corner1 = shrink;
            corner2 = corner2.relative(facing.getOpposite(), mineWidth).relative(rightDir.getOpposite(), mineWidth);

            if (corner1.getX() >= corner2.getX() || corner1.getZ() >= corner2.getZ()) {
                logDirect("[Quarry] Area exhausted — done!");
                cancel();
                return;
            }

            logDirect(String.format("[Quarry] Layer %d complete. Shrunk bounds: %s → %s", layersDone, corner1, corner2));
        } else {
            // Turn 90° left
            facing = rotateLeft(facing);
            logDirect(String.format("[Quarry] Turning left to face %s (wall %d/4)", facing, wallIndex + 1));
        }
    }

    // ============== Helpers ==============

    private Direction rotateLeft(Direction dir) {
        switch (dir) {
            case NORTH: return Direction.WEST;
            case WEST:  return Direction.SOUTH;
            case SOUTH: return Direction.EAST;
            case EAST:  return Direction.NORTH;
            default:    return dir;
        }
    }

    private boolean isWithinBounds(BlockPos pos) {
        if (corner1 == null || corner2 == null) return false;
        return pos.getX() >= corner1.getX() && pos.getX() <= corner2.getX()
            && pos.getY() >= corner1.getY() && pos.getY() <= corner2.getY()
            && pos.getZ() >= corner1.getZ() && pos.getZ() <= corner2.getZ();
    }

    private boolean isMineable(BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        if (state.getBlock() instanceof AirBlock) return true; // already mined
        return filter.has(state);
    }

    private void cancel() {
        filter = null;
        corner1 = null;
        corner2 = null;
        currentTarget = null;
        paused = false;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public void onLostControl() {
        cancel();
    }

    @Override
    public String displayName0() {
        return paused ? "Quarry Paused" : "Quarry " + filter;
    }

    @Override
    public double priority() {
        return 3; // Lower than MineProcess(4) so it doesn't fight
    }
}
