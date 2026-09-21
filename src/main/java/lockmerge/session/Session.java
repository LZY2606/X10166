package lockmerge.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lockmerge.merge.Decision;

/**
 * Mutable, resumable merge session.
 *
 * <p>Everything needed to continue an interrupted merge is persisted:
 * the three raw inputs, the last diagnostics, all adjudications and the
 * produced output versions.
 */
public final class Session {

    private String id;
    private long revision;
    private String baseRaw = "";
    private String leftRaw = "";
    private String rightRaw = "";
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private final List<Decision> decisions = new ArrayList<>();
    private String lastOutputText;
    private String lastOutputFingerprint;
    private int outputVersion;
    private Instant outputUpdatedAt;

    public String id() {
        return id;
    }

    public void id(String id) {
        this.id = id;
    }

    public long revision() {
        return revision;
    }

    public void revision(long revision) {
        this.revision = revision;
    }

    public String baseRaw() {
        return baseRaw;
    }

    public String leftRaw() {
        return leftRaw;
    }

    public String rightRaw() {
        return rightRaw;
    }

    public void raws(String base, String left, String right) {
        this.baseRaw = base == null ? "" : base;
        this.leftRaw = left == null ? "" : left;
        this.rightRaw = right == null ? "" : right;
        this.updatedAt = Instant.now();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void createdAt(Instant value) {
        this.createdAt = value;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void updatedAt(Instant value) {
        this.updatedAt = value;
    }

    public List<Decision> decisions() {
        return decisions;
    }

    public String lastOutputText() {
        return lastOutputText;
    }

    public String lastOutputFingerprint() {
        return lastOutputFingerprint;
    }

    public int outputVersion() {
        return outputVersion;
    }

    public Instant outputUpdatedAt() {
        return outputUpdatedAt;
    }

    public void recordOutput(String text, String fingerprint) {
        if (text != null && text.equals(lastOutputText)) {
            return;
        }
        this.lastOutputText = text;
        this.lastOutputFingerprint = fingerprint;
        this.outputVersion++;
        this.outputUpdatedAt = Instant.now();
    }

    public void restoreOutput(String text, String fingerprint, int version, Instant when) {
        this.lastOutputText = text;
        this.lastOutputFingerprint = fingerprint;
        this.outputVersion = version;
        this.outputUpdatedAt = when;
    }
}
