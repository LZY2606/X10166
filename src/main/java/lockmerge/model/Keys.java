package lockmerge.model;

/** 包节点键：name@version。同名不同版本是不同节点，可以共存。 */
public final class Keys {
    private Keys() {
    }

    public static String of(String name, String version) {
        return name + "@" + version;
    }

    public static String nameOf(String key) {
        int at = key.lastIndexOf('@');
        return at < 0 ? key : key.substring(0, at);
    }

    public static String versionOf(String key) {
        int at = key.lastIndexOf('@');
        return at < 0 ? "" : key.substring(at + 1);
    }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || c == '@' || c == '"' || c == '{' || c == '}') return false;
        }
        return true;
    }

    public static boolean isValidVersion(String version) {
        if (version == null || version.isEmpty()) return false;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '{' || c == '}') return false;
        }
        return true;
    }
}
