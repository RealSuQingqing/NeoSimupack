/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.client.ui.preview;

import com.rstech.simupack.SimuPackMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector3fc;

@OnlyIn(value=Dist.CLIENT)
public final class StructurePreviewLoader {
    private static CompoundTag cachedRoot;
    private static Snapshot cachedSnapshot;

    private StructurePreviewLoader() {
    }

    public static Snapshot snapshot(CompoundTag root) {
        if (root == cachedRoot) {
            return cachedSnapshot;
        }
        Snapshot parsed = StructurePreviewLoader.parse(root);
        cachedRoot = root;
        cachedSnapshot = parsed;
        return parsed;
    }

    public static List<Placed> read(CompoundTag root) {
        return StructurePreviewLoader.snapshot(root).blocks();
    }

    private static Snapshot parse(CompoundTag root) {
        if (root == null || !root.contains("blocks")) {
            return new Snapshot(List.of(), new Vector3f(), new Vector3f(1.0f));
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return new Snapshot(List.of(), new Vector3f(), new Vector3f(1.0f));
        }
        HolderLookup.RegistryLookup blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        StructureTemplate template = new StructureTemplate();
        try {
            template.load((HolderGetter)blockLookup, root);
        }
        catch (RuntimeException exception) {
            SimuPackMod.LOGGER.warn("\u7ed3\u6784 NBT \u65e0\u6cd5\u6309\u539f\u7248\u7ed3\u6784\u89e3\u6790\uff0c\u9884\u89c8\u6309\u7a7a\u5904\u7406", (Throwable)exception);
            return new Snapshot(List.of(), new Vector3f(), new Vector3f(1.0f));
        }
        CompoundTag flat = template.save(new CompoundTag());
        ListTag paletteTag = StructurePreviewLoader.paletteStates(flat);
        ArrayList<BlockState> states = new ArrayList<BlockState>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); ++i) {
            states.add(NbtUtils.readBlockState((HolderGetter)blockLookup, (CompoundTag)paletteTag.getCompound(i)));
        }
        ListTag blocks = flat.getList("blocks", 10);
        ArrayList<Placed> result = new ArrayList<Placed>(blocks.size());
        for (int i = 0; i < blocks.size(); ++i) {
            BlockState state;
            CompoundTag entry = blocks.getCompound(i);
            ListTag posTag = entry.getList("pos", 3);
            int index = entry.getInt("state");
            if (posTag.size() < 3 || index < 0 || index >= states.size() || (state = (BlockState)states.get(index)).isAir() || state.is(Blocks.STRUCTURE_VOID)) continue;
            result.add(new Placed(new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)), state));
        }
        Vector3f minimum = new Vector3f(0.0f);
        Vector3f maximum = new Vector3f(1.0f);
        if (!result.isEmpty()) {
            minimum.set(Float.POSITIVE_INFINITY);
            maximum.set(Float.NEGATIVE_INFINITY);
            for (Placed placed : result) {
                BlockPos pos = placed.pos();
                minimum.min((Vector3fc)new Vector3f((float)pos.getX(), (float)pos.getY(), (float)pos.getZ()));
                maximum.max((Vector3fc)new Vector3f((float)pos.getX() + 1.0f, (float)pos.getY() + 1.0f, (float)pos.getZ() + 1.0f));
            }
        }
        return new Snapshot(result, minimum, maximum);
    }

    private static ListTag paletteStates(CompoundTag flat) {
        if (flat.contains("palette", 9)) {
            return flat.getList("palette", 10);
        }
        ListTag palettes = flat.getList("palettes", 10);
        if (palettes.isEmpty()) {
            return new ListTag();
        }
        return palettes.getCompound(0).getList("palette", 10);
    }

    static {
        cachedSnapshot = new Snapshot(List.of(), new Vector3f(), new Vector3f(1.0f));
    }

    public record Snapshot(List<Placed> blocks, Vector3f minimum, Vector3f maximum) {
        public Snapshot {
            blocks = List.copyOf(blocks);
            minimum = new Vector3f((Vector3fc)minimum);
            maximum = new Vector3f((Vector3fc)maximum);
        }

        public boolean isEmpty() {
            return this.blocks.isEmpty();
        }
    }

    public record Placed(BlockPos pos, BlockState state) {
    }
}

