package com.rstech.simupack.network;

import java.util.List;

public record PackManifestEntry(
        String id,
        String fileName,
        String version,
        String sha256,
        int formatVersion,
        List<String> categories,
        List<String> buildingIds
) {
    public PackManifestEntry {
        categories = categories == null ? List.of() : List.copyOf(categories);
        buildingIds = buildingIds == null ? List.of() : List.copyOf(buildingIds);
    }
}
