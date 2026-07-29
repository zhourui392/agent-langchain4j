package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.agent.WorkspaceId;

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

    private final Map<FileStateKey, FileTime> readMtimes = new ConcurrentHashMap<>();

    public void recordRead(Path file) {
        recordRead(workspaceFor(file), file);
    }

    public void recordRead(WorkspaceId workspaceId, Path file) {
        FileTime mtime = currentMtime(file);
        if (mtime != null) {
            readMtimes.put(key(workspaceId, file), mtime);
            log.debug("file read state recorded: workspace={}, file={}, mtime={}", workspaceId, file, mtime);
        }
    }

    public boolean hasBeenRead(Path file) {
        return hasBeenRead(workspaceFor(file), file);
    }

    public boolean hasBeenRead(WorkspaceId workspaceId, Path file) {
        return readMtimes.containsKey(key(workspaceId, file));
    }

    public boolean isStale(Path file) {
        return isStale(workspaceFor(file), file);
    }

    public boolean isStale(WorkspaceId workspaceId, Path file) {
        FileTime recorded = readMtimes.get(key(workspaceId, file));
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

    private static FileStateKey key(WorkspaceId workspaceId, Path file) {
        return new FileStateKey(workspaceId, file.toAbsolutePath().normalize());
    }

    private static WorkspaceId workspaceFor(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        return WorkspaceId.fromPath(parent == null ? absolute : parent);
    }

    private record FileStateKey(WorkspaceId workspaceId, Path file) {
    }
}
