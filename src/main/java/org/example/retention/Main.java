package org.example.retention;

import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 5210;
        Path databaseFile = Path.of("data", "retention.db");
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--db".equals(args[i]) && i + 1 < args.length) {
                databaseFile = Path.of(args[++i]);
            }
        }
        Database database = Database.open(databaseFile);
        Clock clock = new Clock(database);
        RetentionService service = new RetentionService(database, clock);
        SeedData.seedIfEmpty(service);
        HttpServer server = new HttpServer(service);
        server.start(port);
        System.out.println("保留策略演算台 listening on http://localhost:" + port);
        System.out.println("SQLite: " + databaseFile.toAbsolutePath());
        Thread.currentThread().join();
    }
}
