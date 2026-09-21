package dev.lockmerge.merge;

import java.util.List;

/**
 * One user adjudication. {@code choice} is one of LEFT / RIGHT / BASE / BOTH
 * (BOTH only valid for EDGE_DIVERGE). For integrity conflicts the chosen value
 * rewrites every node sharing the (source, version) coordinate.
 */
public record Decision(String conflictId,
                       String choice,
                       String fingerprintBase,
                       String fingerprintLeft,
                       String fingerprintRight) {

    public static final String LEFT = "LEFT";
    public static final String RIGHT = "RIGHT";
    public static final String BASE = "BASE";
    public static final String BOTH = "BOTH";

    public boolean binds(List<String> fingerprints) {
        return fingerprintBase.equals(fingerprints.get(0))
                && fingerprintLeft.equals(fingerprints.get(1))
                && fingerprintRight.equals(fingerprints.get(2));
    }
}
