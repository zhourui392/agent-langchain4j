package com.anthropic.cclc.infrastructure.tools.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileStateCache {

    private static final Logger log = LoggerFactory.getLogger(FileStateCache.class);

    private final Map<Path, FileTime> readMtimes = new ConcurrentHashMap<>();

    public void recordRead(Path file) {
        FileTime mtime = currentMtime(file);
        if (mtime != null) {
            readMtimes.put(file.toAbsolutePath().normalize(), mtime);
            log.debug("file read state recorded: file={}, mtime={}", file, mtime);
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
        boolean stale = current != null && current.compareTo(recorded) > 0;
        if (stale) {
            log.warn("file state stale: file={}, recordedMtime={}, currentMtime={}", file, recorded, current);
        }
        return stale;
    }

    public void clear() {
        readMtimes.clear();
        log.debug("file read state cache cleared");
    }

    private static FileTime currentMtime(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException ex) {
            return null;
        }
    }
}
