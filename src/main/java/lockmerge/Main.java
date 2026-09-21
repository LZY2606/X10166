package lockmerge;

import java.nio.file.Path;
import lockmerge.session.SessionService;
import lockmerge.web.WebServer;

/** Entry point: starts the local Lockfile Semantic Merge Room web app. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5238;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--host requires a value");
                    }
                    host = args[++i];
                }
                case "--port" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--port requires a value");
                    }
                    port = Integer.parseInt(args[++i]);
                }
                case "--help", "-h" -> {
                    System.out.println("Usage: run --host 127.0.0.1 --port 5238");
                    return;
                }
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        SessionService service = new SessionService(Path.of(".lockmerge-data"));
        WebServer server = new WebServer(service);
        server.start(host, port);
        String url = "http://" + host + ":" + port;
        System.out.println("Lockfile \u8bed\u4e49\u5408\u5e76\u5ba4 \u5df2\u542f\u52a8");
        System.out.println("\u6253\u5f00 " + url + " \u5f00\u59cb\u4e09\u65b9\u8bed\u4e49\u5408\u5e76");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
