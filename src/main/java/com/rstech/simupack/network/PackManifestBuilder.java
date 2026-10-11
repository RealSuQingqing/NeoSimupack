package com.rstech.simupack.network;

import com.rstech.simupack.pack.BuildingPack;

import java.util.List;

public final class PackManifestBuilder {
    private PackManifestBuilder() {
    }

    public static PackManifestEntry fromPack(BuildingPack pack) {
        return new PackManifestEntry(
                pack.id(),
                pack.path().getFileName().toString(),
                pack.version(),
                "",
                1,
                List.of(),
                List.of()
        );
    }
}
