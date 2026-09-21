package lockmerge.merge;

import java.util.List;

/** An automatic (non-conflicting) change applied during the merge. */
public record Change(String kind, String side, String target, List<String> details) {
}
