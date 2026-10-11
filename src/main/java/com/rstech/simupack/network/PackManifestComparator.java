package com.rstech.simupack.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PackManifestComparator {
    private PackManifestComparator() {
    }

    public static List<PackMismatch> compare(
            List<PackManifestEntry> client,
            List<PackManifestEntry> server
    ) {
        Map<String, PackManifestEntry> clientById = byId(client);
        Map<String, PackManifestEntry> serverById = byId(server);
        List<PackMismatch> result = new ArrayList<>();

        for (PackManifestEntry required : serverById.values()) {
            PackManifestEntry installed = clientById.get(required.id());
            if (installed == null) {
                result.add(new PackMismatch(required.id(), required.id(), "", required.version(),
                        PackMismatchType.MISSING, required.buildingIds()));
                continue;
            }
            if (installed.version().isBlank() || required.version().isBlank()) {
                if (!installed.sha256().isBlank() && installed.sha256().equalsIgnoreCase(required.sha256())) {
                    continue;
                }
                result.add(new PackMismatch(required.id(), required.id(), installed.version(), required.version(),
                        PackMismatchType.UNKNOWN_VERSION, required.buildingIds()));
                continue;
            }
            int versionComparison = compareVersions(installed.version(), required.version());
            if (versionComparison < 0) {
                result.add(new PackMismatch(required.id(), required.id(), installed.version(), required.version(),
                        PackMismatchType.OUTDATED, required.buildingIds()));
            } else if (versionComparison > 0) {
                result.add(new PackMismatch(required.id(), required.id(), installed.version(), required.version(),
                        PackMismatchType.LOCAL_NEWER, required.buildingIds()));
            } else if (!installed.sha256().isBlank() && !required.sha256().isBlank()
                    && !installed.sha256().equalsIgnoreCase(required.sha256())) {
                result.add(new PackMismatch(required.id(), required.id(), installed.version(), required.version(),
                        PackMismatchType.HASH_MISMATCH, required.buildingIds()));
            }
        }
        result.sort(Comparator.comparing(PackMismatch::packId));
        return List.copyOf(result);
    }

    private static Map<String, PackManifestEntry> byId(List<PackManifestEntry> entries) {
        Map<String, PackManifestEntry> result = new HashMap<>();
        if (entries != null) {
            for (PackManifestEntry entry : entries) {
                if (entry != null && entry.id() != null && !entry.id().isBlank()) {
                    result.putIfAbsent(entry.id(), entry);
                }
            }
        }
        return result;
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? numericPart(a[i]) : 0;
            int bv = i < b.length ? numericPart(b[i]) : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int numericPart(String value) {
        String digits = value.replaceAll("[^0-9].*", "");
        if (digits.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
