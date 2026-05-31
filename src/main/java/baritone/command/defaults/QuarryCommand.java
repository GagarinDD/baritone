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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ForBlockOptionalMeta;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class QuarryCommand extends Command {

    public QuarryCommand(IBaritone baritone) {
        super(baritone, "quarry");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        // Parse as: quarry <height> <width> <blocks...>
        // Blocks can be comma-separated or space-separated
        int height = args.getAsOrDefault(Integer.class, 0);
        int width = args.getAsOrDefault(Integer.class, 0);

        if (width < 1 || height < 2) {
            logDirect("Width must be >= 1, height must be >= 2. Usage: #quarry 3 3 cobblestone copper_ore ...");
            return;
        }

        // Collect all remaining args and split by commas
        List<String> rawBlocks = new ArrayList<>();
        while (args.hasAny()) {
            rawBlocks.add(args.getString());
        }

        if (rawBlocks.isEmpty()) {
            logDirect("Specify at least one block type to mine. Usage: #quarry 3 3 cobblestone copper_ore ...");
            return;
        }

        // Split comma-separated blocks (supports both "a,b,c" and "a b c")
        List<BlockOptionalMeta> boms = new ArrayList<>();
        for (String raw : rawBlocks) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        boms.add(new BlockOptionalMeta(trimmed));
                    } catch (IllegalArgumentException e) {
                        logDirect("Unknown block: " + trimmed);
                        return;
                    }
                }
            }
        }

        BlockOptionalMetaLookup filter = new BlockOptionalMetaLookup(
            boms.toArray(new BlockOptionalMeta[0])
        );
        logDirect(String.format(
            "Starting quarry %d\u00d7%d, blocks: %s. Stand in corner, face along a wall.",
            height, width, boms.toString()
        ));
        baritone.getQuarryProcess().quarry(height, width, filter);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        args.getAsOrDefault(Integer.class, 0); // height
        args.getAsOrDefault(Integer.class, 0); // width
        while (args.has(2)) {
            args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
        }
        return args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE);
    }

    @Override
    public String getShortDesc() {
        return "Spiral quarry — mines a shrinking rectangle";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
            "The quarry command creates a spiral quarry that automatically mines",
            "a rectangular perimeter and shrinks inward layer by layer.",
            "",
            "Stand in a corner, face along one wall, then run:",
            "> quarry <height> <width> <blocks...>",
            "",
            "Examples:",
            "> quarry 3 3 cobblestone stone iron_ore coal_ore",
            "> quarry 2 2 deepslate,copper_ore,gold_ore",
            "",
            "Blocks can be space-separated or comma-separated.",
            "",
            "The bot will:",
            "  1. Mine straight until it hits non-mineable blocks",
            "  2. Turn 90Â° left, mine next wall",
            "  3. After 4 walls (full perimeter), step inward by <width> blocks",
            "  4. Repeat until area is exhausted",
            "",
            "Stop conditions (configurable):",
            "  - Inventory nearly full (quarryMinFreeSlots)",
            "  - No usable pickaxe (quarryMinPickaxeDurability)",
            "",
            "Use #stop to cancel, #resume to continue after fixing stop conditions."
        );
    }
}
