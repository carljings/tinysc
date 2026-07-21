package io.tinysc.deployment;

public final class WarLimits {
    private final int maxEntries;
    private final long maxEntryBytes;
    private final long maxExpandedBytes;

    public WarLimits(int maxEntries, long maxEntryBytes, long maxExpandedBytes) {
        if (maxEntries <= 0 || maxEntryBytes <= 0 || maxExpandedBytes <= 0) {
            throw new IllegalArgumentException("WAR limits must be positive");
        }
        if (maxEntryBytes > maxExpandedBytes) {
            throw new IllegalArgumentException("maxEntryBytes must not exceed maxExpandedBytes");
        }
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxExpandedBytes = maxExpandedBytes;
    }

    public int maxEntries() {
        return maxEntries;
    }

    public long maxEntryBytes() {
        return maxEntryBytes;
    }

    public long maxExpandedBytes() {
        return maxExpandedBytes;
    }

    public static WarLimits defaults() {
        return new WarLimits(100000, 1024L * 1024L * 1024L, 6L * 1024L * 1024L * 1024L);
    }
}
