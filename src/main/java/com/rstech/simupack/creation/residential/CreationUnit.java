package com.rstech.simupack.creation.residential;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/** One rectangular residential unit in structure-local coordinates. */
public final class CreationUnit {
    private final String id;
    private String label;
    private BlockPos min;
    private BlockPos max;

    public CreationUnit() {
        this(UUID.randomUUID().toString(), "", null, null);
    }

    public CreationUnit(String label, BlockPos first, BlockPos second) {
        this(UUID.randomUUID().toString(), label, first, second);
    }

    public CreationUnit(String id, String label, BlockPos first, BlockPos second) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.label = label == null ? "" : label;
        setRange(first, second);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
    }

    public BlockPos min() {
        return min;
    }

    public BlockPos max() {
        return max;
    }

    public boolean hasRange() {
        return min != null && max != null;
    }

    public void setRange(BlockPos first, BlockPos second) {
        if (first == null || second == null) {
            min = null;
            max = null;
            return;
        }
        min = new BlockPos(Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()));
        max = new BlockPos(Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ()));
    }

    public boolean contains(BlockPos pos) {
        return hasRange() && pos != null
                && pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
}
