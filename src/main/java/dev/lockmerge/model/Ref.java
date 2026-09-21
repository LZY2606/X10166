package dev.lockmerge.model;

import java.util.Objects;

/**
 * Precise reference to a child node. The reference pins an exact version and may
 * carry a normalized platform condition under which the edge is active.
 */
public record Ref(String name, String version, String condition) {

    public Ref {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
    }

    public String key() {
        return name + "@" + version + (condition == null ? "" : "[" + condition + "]");
    }
}
