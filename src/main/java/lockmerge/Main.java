package lockmerge;

import java.nio.file.Path;
import lockmerge.store.SessionStore;
import lockmerge.web.MergeService;
import lockmerge.web.WebServer;

public final class Main {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5238;
        String dataDir = System.getenv().getOrDefault("LOCKMERGE_DATA", "data/sessions");
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = args[++i];
                default -> {
                }
            }
        }
        SessionStore store = new SessionStore(Path.of(dataDir));
        MergeService service = new MergeService(store);
        WebServer server = new WebServer(service, store);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start(host, port);
        Thread.currentThread().join();
    }
}
