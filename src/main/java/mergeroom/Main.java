package mergeroom;

import mergeroom.web.WebServer;

/**
 * Entry point: {@code ./gradlew run --args='--host 127.0.0.1 --port 5238'}.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5238;
        String storage = "data/sessions";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = requireArg(args, ++i, "--host");
                case "--port" -> port = Integer.parseInt(requireArg(args, ++i, "--port"));
                case "--storage" -> storage = requireArg(args, ++i, "--storage");
                default -> usage();
            }
        }
        WebServer server = new WebServer(java.nio.file.Path.of(storage));
        int bound = server.start(host, port);
        System.out.println("Lockfile 语义合并室已启动: http://" + host + ":" + bound);
        System.out.println("会话持久化目录: " + java.nio.file.Path.of(storage).toAbsolutePath());
    }

    private static String requireArg(String[] args, int i, String name) {
        if (i >= args.length) {
            usage();
        }
        return args[i];
    }

    private static void usage() {
        System.err.println("用法: [--host 127.0.0.1] [--port 5238] [--storage data/sessions]");
        System.exit(2);
    }
}
