package dev.lockmerge;

import dev.lockmerge.web.WebServer;

import java.nio.file.Path;

/** Entry point: {@code run --host 127.0.0.1 --port 5238 [--data data]}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5238;
        String data = "data";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> data = args[++i];
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        WebServer server = new WebServer(Path.of(data));
        server.start(host, port);
        System.out.println("Lockfile 语义合并室 running at http://" + host + ":" + port);
        Thread.currentThread().join();
    }
}
