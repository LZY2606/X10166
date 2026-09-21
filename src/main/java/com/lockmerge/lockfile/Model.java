package com.lockmerge.lockfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Core model for the custom lockfile format. */
public final class Model {

    private Model() {}

    /** Exact package coordinate: name + version. Node identity in the graph. */
    public record NameVersion(String name, String version) implements Comparable<NameVersion> {
        public NameVersion {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
        }

        public static NameVersion parse(String text) {
            int at = text.lastIndexOf('@');
            if (at <= 0 || at == text.length() - 1) {
                throw new IllegalArgumentException("invalid name@version: " + text);
            }
            return new NameVersion(text.substring(0, at), text.substring(at + 1));
        }

        @Override
        public String toString() {
            return name + "@" + version;
        }

        @Override
        public int compareTo(NameVersion other) {
            int c = name.compareTo(other.name);
            return c != 0 ? c : version.compareTo(other.version);
        }
    }

    /** A single package node in the lock graph. */
    public static final class PackageNode {
        public final String name;
        public final String version;
        public String source;
        public String integrity;
        public PlatformExpr platform; // may be null
        public final List<NameVersion> depends = new ArrayList<>();

        public PackageNode(String name, String version) {
            this.name = name;
            this.version = version;
        }

        public NameVersion key() {
            return new NameVersion(name, version);
        }

        public PackageNode copy() {
            PackageNode n = new PackageNode(name, version);
            n.source = source;
            n.integrity = integrity;
            n.platform = platform;
            n.depends.addAll(depends);
            return n;
        }

        /** Semantic equality: platform compared by normalized form, depends as a set. */
        public boolean semanticEquals(PackageNode other) {
            if (other == null) return false;
            if (!name.equals(other.name) || !version.equals(other.version)) return false;
            if (!Objects.equals(source, other.source)) return false;
            if (!Objects.equals(integrity, other.integrity)) return false;
            if (!Objects.equals(
                    platform == null ? null : platform.canonical(),
                    other.platform == null ? null : other.platform.canonical())) return false;
            return new LinkedHashSet<>(depends).equals(new LinkedHashSet<>(other.depends));
        }
    }

    /** A parsed lockfile: declared roots plus package nodes keyed by name@version. */
    public static final class Lockfile {
        public final LinkedHashSet<NameVersion> roots = new LinkedHashSet<>();
        public final LinkedHashMap<NameVersion, PackageNode> packages = new LinkedHashMap<>();
    }

    public enum Severity { ERROR, WARNING }

    /** A parse or analysis diagnostic. */
    public record Diagnostic(Severity severity, int line, String message) {
        @Override
        public String toString() {
            String loc = line > 0 ? ("line " + line + ": ") : "";
            return severity + " " + loc + message;
        }
    }
}
