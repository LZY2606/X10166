package com.lockmerge.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON-file backed session store; sessions survive restarts. */
public final class SessionStore {
    private final Path dir;
    private final ObjectMapper mapper;

    public SessionStore(Path dir) {
        this.dir = dir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Map<String, SessionState> loadAll() {
        Map<String, SessionState> out = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                try {
                    SessionState s = mapper.readValue(p.toFile(), SessionState.class);
                    out.put(s.id, s);
                } catch (IOException e) {
                    System.err.println("skipping unreadable session file " + p + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public synchronized void save(SessionState session) {
        Path tmp = dir.resolve(session.id + ".json.tmp");
        Path target = dir.resolve(session.id + ".json");
        try {
            mapper.writeValue(tmp.toFile(), session);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
