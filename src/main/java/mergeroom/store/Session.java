package mergeroom.store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted session: raw inputs, parse diagnostics, verdicts and the last
 * produced output version. Verdicts are bound to the triple input fingerprint.
 */
public final class Session {
    public String id;
    public long revision;
    public String baseText = "";
    public String leftText = "";
    public String rightText = "";
    /** id -&gt; "left"/"right" verdicts that still match {@link #fingerprint}. */
    public Map<String, String> verdicts = new LinkedHashMap<>();
    /** Verdicts preserved for inspection but no longer auto-applied. */
    public Map<String, String> staleVerdicts = new LinkedHashMap<>();
    public String fingerprint = "";
    public String outputText = "";
    public String createdAt = "";
    public String updatedAt = "";
}
