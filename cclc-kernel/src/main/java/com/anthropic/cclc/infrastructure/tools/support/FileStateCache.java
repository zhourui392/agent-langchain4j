package com.anthropic.cclc.infrastructure.tools.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FileStateCache {

    private final Map<Path, FileTime> readMtimes = new ConcurrentHashMap<>();

    public void recordRead(Path file) {
        FileTime mtime = currentMtime(file);
        if (mtime != null) {
            readMtimes.put(file.toAbsolutePath().normalize(), mtime);
        }
    }

    public boolean hasBeenRead(Path file) {
        return readMtimes.containsKey(file.toAbsolutePath().normalize());
    }

    public boolean isStale(Path file) {
        Path key = file.toAbsolutePath().normalize();
        FileTime recorded = readMtimes.get(key);
        if (recorded == null) {
            return false;
        }
        FileTime current = currentMtime(file);
        return current != null && current.compareTo(recorded) > 0;
    }

    public void clear() {
        readMtimes.clear();
    }

    private static FileTime currentMtime(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException ex) {
            return null;
        }
    }
}
