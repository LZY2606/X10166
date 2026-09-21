package lockmerge.merge;

import java.util.List;

import lockmerge.model.RootDep;

/** A root declaration in the merged graph. */
public record GraphRoot(RootDep dep, List<String> presentIn, List<String> origins,
                        boolean targetPresent) {
}
