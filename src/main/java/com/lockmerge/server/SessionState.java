package com.lockmerge.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted session state: raw inputs, resolutions, output versions, last analysis. */
public class SessionState {
    public String id;
    public long version;
    public String createdAt;
    public String updatedAt;
    public String baseText;
    public String leftText;
    public String rightText;
    public String fingerprint;
    /** conflictId -> resolution (bound to the input fingerprint at decision time). */
    public Map<String, Resolution> resolutions = new LinkedHashMap<>();
    public List<OutputRecord> outputs = new ArrayList<>();
    /** Last computed analysis snapshot (parse diagnostics, conflicts, prunes, integrity). */
    public Map<String, Object> lastAnalysis = new LinkedHashMap<>();

    public static class Resolution {
        public String conflictId;
        public String choice; // "left" | "right"
        public String fingerprint;
        public String createdAt;

        public Resolution() {}

        public Resolution(String conflictId, String choice, String fingerprint, String createdAt) {
            this.conflictId = conflictId;
            this.choice = choice;
            this.fingerprint = fingerprint;
            this.createdAt = createdAt;
        }
    }

    public static class OutputRecord {
        public long seq;
        public String fingerprint;
        public String text;
        public List<String> diagnostics = new ArrayList<>();
        public String createdAt;

        public OutputRecord() {}
    }
}
