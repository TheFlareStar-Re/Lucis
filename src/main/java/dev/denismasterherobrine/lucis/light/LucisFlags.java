package dev.denismasterherobrine.lucis.light;

/**
 * Feature flags for the light-engine optimization paths, isolated from the NeoForge
 * config stack so engine code stays loadable in headless tests. Values are resolved
 * from system properties at class initialization and re-synced from {@code LucisConfig}
 * when the in-game config loads.
 *
 * Defaults reflect the validated state:
 * - sectionFastPath, skySeedSkip, denseIncremental: validated correctness (differential
 *   suite) and performance (end-to-end benchmarks); on by default.
 * - inlineRuntime: no regression measured in the benchmark harness and removes the
 *   cross-tick commit latency; on by default.
 * - runtimeAdoption: adoption-backed region init; on by default, falls back to full
 *   compute automatically whenever adoption is not possible.
 * - boundaryDeltas: cross-region boundary continuation prototype; OFF - the prototype
 *   oscillates for roof-crossing batches (see docs/light-engine-architecture.md).
 */
public final class LucisFlags {
    public static volatile boolean sectionFastPath =
            Boolean.parseBoolean(System.getProperty("lucis.experimentalSectionFastPath", "true"));
    public static volatile boolean skySeedSkip =
            Boolean.parseBoolean(System.getProperty("lucis.experimentalSkySeedSkip", "true"));
    public static volatile boolean denseIncremental =
            Boolean.parseBoolean(System.getProperty("lucis.experimentalDenseIncremental", "true"));
    public static volatile boolean inlineRuntime =
            Boolean.parseBoolean(System.getProperty("lucis.experimentalInlineRuntime", "true"));
    public static volatile boolean boundaryDeltas =
            Boolean.parseBoolean(System.getProperty("lucis.experimentalBoundaryDeltas", "false"));
    public static volatile boolean runtimeAdoption =
            Boolean.parseBoolean(System.getProperty("lucis.experimentalRuntimeAdoption", "true"));

    private LucisFlags() {
    }

    public static void set(boolean sectionFastPath, boolean skySeedSkip, boolean denseIncremental, boolean inlineRuntime) {
        LucisFlags.sectionFastPath = sectionFastPath;
        LucisFlags.skySeedSkip = skySeedSkip;
        LucisFlags.denseIncremental = denseIncremental;
        LucisFlags.inlineRuntime = inlineRuntime;
    }

    public static void setAll(boolean sectionFastPath, boolean skySeedSkip, boolean denseIncremental,
                              boolean inlineRuntime, boolean runtimeAdoption) {
        set(sectionFastPath, skySeedSkip, denseIncremental, inlineRuntime);
        LucisFlags.runtimeAdoption = runtimeAdoption;
    }
}
