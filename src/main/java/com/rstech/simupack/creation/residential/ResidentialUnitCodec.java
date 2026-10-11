package com.rstech.simupack.creation.residential;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Parser and writer for NSUK's repeated unit: metadata lines. */
public final class ResidentialUnitCodec {
    private ResidentialUnitCodec() {
    }

    public static String toSkLine(CreationUnit unit) {
        if (unit == null || !unit.hasRange()) {
            return null;
        }
        BlockPos min = unit.min();
        BlockPos max = unit.max();
        return "unit: " + unit.label().trim() + ", "
                + min.getX() + "," + min.getY() + "," + min.getZ() + "~"
                + max.getX() + "," + max.getY() + "," + max.getZ();
    }

    /** Parse only valid rectangular unit lines, preserving source order. */
    public static List<CreationUnit> parse(String text) {
        List<CreationUnit> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String line : text.split("\\R", -1)) {
            CreationUnit unit = parseLine(line);
            if (unit != null) {
                result.add(unit);
            }
        }
        return result;
    }

    /** Parse one line using NSUK's case-insensitive unit: prefix and range rules. */
    public static CreationUnit parseLine(String line) {
        if (line == null || !line.regionMatches(true, 0, "unit:", 0, 5)) {
            return null;
        }
        int comma = line.indexOf(',', 5);
        if (comma < 0) {
            return null;
        }
        String label = line.substring(5, comma).trim();
        String coordinates = line.substring(comma + 1).trim();
        if (coordinates.contains("|")) {
            return null;
        }
        int tilde = coordinates.indexOf('~');
        if (tilde < 0 || tilde != coordinates.lastIndexOf('~')) {
            return null;
        }
        BlockPos first = parseCoord(coordinates.substring(0, tilde).trim());
        BlockPos second = parseCoord(coordinates.substring(tilde + 1).trim());
        return first == null || second == null ? null : new CreationUnit(label, first, second);
    }

    private static BlockPos parseCoord(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
