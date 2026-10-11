package com.rstech.simupack.network;

import java.util.List;

public record PackMismatch(
        String packId,
        String displayName,
        String clientVersion,
        String serverVersion,
        PackMismatchType type,
        List<String> affectedBuildings
) {
    public PackMismatch {
        affectedBuildings = affectedBuildings == null ? List.of() : List.copyOf(affectedBuildings);
    }
}
