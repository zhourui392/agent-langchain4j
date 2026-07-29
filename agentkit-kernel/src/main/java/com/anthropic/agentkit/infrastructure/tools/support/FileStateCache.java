package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.tool.ExecutionContext;

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

    public void recordRead(ExecutionContext context, Path file) {
        FileTime mtime = currentMtime(file);
        if (mtime != null) {
            readMtimes.put(key(context, file), mtime);
            log.debug("file read state recorded: run={}, workspace={}, file={}, mtime={}",
                    context.runId(), context.workspaceId(), file, mtime);
        }
    }

    public boolean hasBeenRead(ExecutionContext context, Path file) {
        return readMtimes.containsKey(key(context, file));
    }

    public boolean isStale(ExecutionContext context, Path file) {
        FileTime recorded = readMtimes.get(key(context, file));
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

    private static FileStateKey key(ExecutionContext context, Path file) {
        return new FileStateKey(
                context.runId(), context.workspaceId(), file.toAbsolutePath().normalize());
    }

    private record FileStateKey(RunId runId, WorkspaceId workspaceId, Path file) {
    }
}
