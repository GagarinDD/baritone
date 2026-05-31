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
import baritone.api.process.IQuarryProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.BlockOptionalMeta;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Quarry process — chains BuilderProcess strips in a shrinking spiral.
 *
 * Uses the battle-tested BuilderProcess (same as #tunnel) for each strip.
 * Only targets blocks matching the filter — non-filtered blocks are treated
 * as boundaries.
 */
public final class QuarryProcess extends BaritoneProcessHelper implements IQuarryProcess {

    private BlockOptionalMetaLookup filter;
    private int mineHeight;
    private int mineWidth;
    private Direction facing;
    private int wallIndex;
    private BlockPos corner1;
    private BlockPos corner2;
    private boolean paused;
    private int layersDone;
    private boolean stripActive;

    public QuarryProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void quarry(int height, int width, BlockOptionalMetaLookup filter) {
        this.mineHeight = height;
        this.mineWidth = width;
        this.filter = filter;
        this.facing = ctx.player().getDirection();
        this.wallIndex = 0;
        this.layersDone = 0;
        this.paused = false;
        this.stripActive = false;

        logDirect(String.format(
            "[Quarry] %dH×%dW, facing %s. Stand in corner, face along wall.",
            height, width, facing
        ));
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (paused || filter == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // --- STOP CONDITIONS ---
        int minFree = Baritone.settings().quarryMinFreeSlots.value;
        if (minFree > 0) {
            long freeSlots = ctx.player().getInventory().items.stream()
                    .filter(ItemStack::isEmpty).count();
            if (freeSlots <= minFree) {
                logNotification(String.format(
                    "[Quarry] Inventory full! Free=%d, need > %d. #resume after clearing.",
                    freeSlots, minFree), true);
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        int minDur = Baritone.settings().quarryMinPickaxeDurability.value;
        if (minDur >= 0) {
            boolean hasPickaxe = ctx.player().getInventory().items.stream()
                    .filter(s -> !s.isEmpty() && s.getItem() instanceof PickaxeItem)
                    .anyMatch(s -> s.getMaxDamage() - s.getDamageValue() >= minDur);
            if (!hasPickaxe) {
                logNotification(String.format(
                    "[Quarry] No pickaxe dur >= %d! #resume after repair.", minDur), true);
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // --- Detect bounds ---
        if (corner1 == null || corner2 == null) {
            detectBounds();
            if (corner1 == null || corner2 == null) {
                logDirect("[Quarry] Could not detect bounds — no mineable blocks ahead?");
                stopQuarry();
                return null;
            }
            logDirect(String.format("[Quarry] Bounds: %s → %s", corner1, corner2));
        }

        // --- If BuilderProcess active, defer ---
        if (stripActive) {
            if (baritone.getBuilderProcess().isActive()) {
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            // Builder done → advance
            stripActive = false;
            advanceStrip();
            if (!isActive()) return null;
        }

        // --- Check exit condition ---
        if (corner1.getX() >= corner2.getX() || corner1.getZ() >= corner2.getZ()) {
            logNotification(String.format(
                "[Quarry] Done! %d layers.", layersDone), false);
            stopQuarry();
            return null;
        }

        // --- Start next strip ---
        BlockPos origin = ctx.playerFeet();
        int stripLen = stripLength(origin);

        if (stripLen < 2) {
            // Not enough space — advance immediately
            advanceStrip();
            if (!isActive()) return null;
            if (corner1.getX() >= corner2.getX() || corner1.getZ() >= corner2.getZ()) {
                logNotification(String.format("[Quarry] Done! %d layers.", layersDone), false);
                stopQuarry();
                return null;
            }
            origin = ctx.playerFeet();
            stripLen = stripLength(origin);
        }

        // Schematic: mineWidth wide, mineHeight tall, stripLen long, filled with AIR
        // Mask: only break blocks matching the filter
        final BlockOptionalMetaLookup f = filter;
        final int len = stripLen;
        MaskSchematic filteredAir = new MaskSchematic(
            new FillSchematic(mineWidth, mineHeight, len, Blocks.AIR.defaultBlockState())
        ) {
            @Override
            public boolean partOfMask(int x, int y, int z,
                    net.minecraft.world.level.block.state.BlockState current) {
                return f.has(current);
            }
        };

        baritone.getBuilderProcess().build("quarry-strip", filteredAir, origin);
        stripActive = true;

        logDirect(String.format("[Quarry] Strip %d (%s), len=%d", wallIndex + 1, facing, len));
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    // ============================================================
    //  BOUNDS
    // ============================================================

    private void detectBounds() {
        BlockPos feet = ctx.playerFeet();
        int maxScan = 200;

        // Scan forward
        int farDist = 0;
        for (int i = 0; i < maxScan; i++) {
            BlockPos check = feet.relative(facing, i);
            if (!isPassable(check)) break;
            farDist = i;
        }

        Direction left = rotateLeft(facing);
        Direction right = rotateLeft(rotateLeft(rotateLeft(facing)));

        int leftDist = 0;
        for (int i = 0; i < maxScan; i++) {
            BlockPos check = feet.relative(left, i);
            if (!isPassable(check)) break;
            leftDist = i;
        }

        int rightDist = 0;
        for (int i = 0; i < maxScan; i++) {
            BlockPos check = feet.relative(right, i);
            if (!isPassable(check)) break;
            rightDist = i;
        }

        if (farDist < mineWidth || leftDist < mineWidth || rightDist < mineWidth) {
            logDirect(String.format("[Quarry] Area too small: f=%d l=%d r=%d", farDist, leftDist, rightDist));
            return;
        }

        corner1 = new BlockPos(
            feet.getX() - Math.max(0, left.getStepX() * leftDist),
            feet.getY(),
            feet.getZ() - Math.max(0, left.getStepZ() * leftDist)
        );
        corner2 = new BlockPos(
            feet.getX() + Math.max(0, facing.getStepX() * farDist) + Math.max(0, right.getStepX() * rightDist),
            feet.getY() + mineHeight - 1,
            feet.getZ() + Math.max(0, facing.getStepZ() * farDist) + Math.max(0, right.getStepZ() * rightDist)
        );

        // Normalize: corner1 = min, corner2 = max
        BlockPos c1 = new BlockPos(
            Math.min(corner1.getX(), corner2.getX()),
            corner1.getY(),
            Math.min(corner1.getZ(), corner2.getZ())
        );
        BlockPos c2 = new BlockPos(
            Math.max(corner1.getX(), corner2.getX()),
            corner2.getY(),
            Math.max(corner1.getZ(), corner2.getZ())
        );
        corner1 = c1;
        corner2 = c2;
    }

    private boolean isPassable(BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        if (state.getBlock() instanceof AirBlock) return true;
        return filter.has(state);
    }

    // ============================================================
    //  STRIP
    // ============================================================

    /**
     * How far the strip should extend from origin in the current facing direction,
     * before hitting the area boundary.
     */
    private int stripLength(BlockPos origin) {
        int dist = 0;
        // Scan until out of bounds
        while (true) {
            BlockPos check = origin.relative(facing, dist);
            if (!isWithinBounds(check)) break;
            dist++;
            if (dist > 300) break;
        }
        return dist;
    }

    private boolean isWithinBounds(BlockPos pos) {
        if (corner1 == null || corner2 == null) return true;
        return pos.getX() >= corner1.getX() && pos.getX() <= corner2.getX()
            && pos.getZ() >= corner1.getZ() && pos.getZ() <= corner2.getZ();
    }

    // ============================================================
    //  ADVANCEMENT
    // ============================================================

    private void advanceStrip() {
        wallIndex++;
        if (wallIndex >= 4) {
            wallIndex = 0;
            layersDone++;
            // Shrink inward: move both corners toward center by mineWidth
            Direction pushDir = rotateLeft(rotateLeft(facing));
            corner1 = new BlockPos(
                corner1.getX() + mineWidth * Math.max(0, pushDir.getStepX()),
                corner1.getY(),
                corner1.getZ() + mineWidth * Math.max(0, pushDir.getStepZ())
            );
            corner2 = new BlockPos(
                corner2.getX() - mineWidth * Math.max(0, pushDir.getOpposite().getStepX()),
                corner2.getY(),
                corner2.getZ() - mineWidth * Math.max(0, pushDir.getOpposite().getStepZ())
            );
            logDirect(String.format("[Quarry] Layer %d done → shrinking inward", layersDone));
        } else {
            facing = rotateLeft(facing);
            logDirect(String.format("[Quarry] Turn left → %s (wall %d/4)", facing, wallIndex + 1));
        }
    }

    // ============================================================
    //  HELPERS
    // ============================================================

    private Direction rotateLeft(Direction dir) {
        switch (dir) {
            case NORTH: return Direction.WEST;
            case WEST:  return Direction.SOUTH;
            case SOUTH: return Direction.EAST;
            case EAST:  return Direction.NORTH;
            default:    return dir;
        }
    }

    private void stopQuarry() {
        filter = null;
        corner1 = null;
        corner2 = null;
        paused = false;
        stripActive = false;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public void onLostControl() {
        stopQuarry();
    }

    @Override
    public String displayName0() {
        return paused ? "Quarry Paused" : "Quarry";
    }

    @Override
    public double priority() {
        return 3;
    }
}
