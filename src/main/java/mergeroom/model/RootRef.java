package mergeroom.model;

import java.util.Objects;

/**
 * A top-level root dependency declaration. It points at a package name and
 * pins the exact resolved node version.
 */
public record RootRef(String name, String version, String condition) {
    public RootRef {
        Objects.requireNonNull(name);
        Objects.requireNonNull(version);
        if (condition == null) {
            condition = "";
        }
    }

    public String targetKey() {
        return name + "@" + version;
    }
}
