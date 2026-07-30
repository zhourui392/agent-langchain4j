package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bounded, symlink-safe local application log adapter.
 *
 * @author alex
 */
public final class LocalFileLogQueryClient implements LogQueryClient {

    private static final Pattern ISO = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\S+?Z)(?:\\s|$)");
    private static final Pattern LOCAL = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?)");
    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LOCAL_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization)\\s*[:=]\\s*(?:bearer\\s+)?\\S+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?key|token|password|secret|credential)"
                    + "\\s*[:=]\\s*(?:['\"]?)[^\\s,'\"}]+");

    private final LocalLogSource source;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Path realRoot;
    private final List<PathMatcher> matchers;

    public LocalFileLogQueryClient(LocalLogSource source) {
        this(source, Clock.systemUTC(), System::nanoTime);
    }

    public LocalFileLogQueryClient(LocalLogSource source, Clock clock) {
        this(source, clock, System::nanoTime);
    }

    LocalFileLogQueryClient(LocalLogSource source, Clock clock, LongSupplier nanoTime) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        try {
            realRoot = source.root().toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("local log root is not readable", failure);
        }
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("local log root must be a directory");
        }
        matchers = source.allowedGlobs().stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob)).toList();
    }

    @Override
    public String query(LogQueryRequest request) throws IOException {
        LogQueryResult result = queryResult(request);
        String header = "dataSourceId=" + result.dataSourceId()
                + " queryStart=" + request.startTime() + " queryEnd=" + request.endTime()
                + " matched=" + result.matched() + " returned=" + result.returned()
                + " truncated=" + result.truncated();
        return result.content().isBlank() ? header : header + "\n" + result.content();
    }

    @Override
    public LogQueryResult queryResult(LogQueryRequest request) throws IOException {
        QueryWindow window = QueryWindow.parse(request, clock.instant());
        ScanBudget budget = new ScanBudget(
                source.maxLines(), source.maxBytes(), source.maxScanDuration(), nanoTime);
        List<Match> matches = new ArrayList<>();
        CandidateSet candidates = candidates(budget);
        List<Path> files = candidates.files();
        for (int index = 0; index < files.size(); index++) {
            ScanAllocation allocation = budget.allocate(files.size() - index);
            scan(files.get(index), request, window, allocation, budget, matches);
            if (!budget.available()) {
                break;
            }
        }
        matches.sort(Comparator.comparing(Match::timestamp).reversed()
                .thenComparing(Match::logicalFile));
        List<Match> returned = matches.stream().limit(request.limit()).toList();
        boolean truncated = candidates.truncated() || budget.truncated()
                || matches.size() > returned.size();
        return LogQueryResult.success(render(returned), source.id(), source.id(), "unknown",
                matches.size(), returned.size(), truncated);
    }

    private CandidateSet candidates(ScanBudget budget) throws IOException {
        Comparator<Path> logicalOrder = Comparator.comparing(
                path -> realRoot.relativize(path).toString());
        PriorityQueue<Path> selected = new PriorityQueue<>(
                source.maxFiles(), logicalOrder.reversed());
        int eligibleFiles = 0;
        try (Stream<Path> paths = Files.walk(realRoot, source.maxDepth())) {
            var iterator = paths.iterator();
            while (iterator.hasNext() && budget.available()) {
                Path path = iterator.next();
                if (!allowedFile(path)) {
                    continue;
                }
                eligibleFiles++;
                if (selected.size() < source.maxFiles()) {
                    selected.add(path);
                } else if (logicalOrder.compare(path, selected.element()) < 0) {
                    selected.remove();
                    selected.add(path);
                }
            }
        }
        List<Path> files = new ArrayList<>(selected);
        files.sort(logicalOrder);
        return new CandidateSet(
                files, eligibleFiles > source.maxFiles() || budget.truncated());
    }

    private boolean allowedFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || sensitive(path)) {
            return false;
        }
        try {
            Path real = path.toRealPath();
            Path relative = realRoot.relativize(real);
            return real.startsWith(realRoot) && matchers.stream().anyMatch(m -> m.matches(relative));
        } catch (IOException | IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean sensitive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Stream.of("secret", "credential", "key", "token").anyMatch(name::contains);
    }

    private void scan(Path file, LogQueryRequest request, QueryWindow window,
                      ScanAllocation allocation, ScanBudget budget,
                      List<Match> matches) throws IOException {
        TailRead tail = readTail(file, allocation);
        if (tail.truncated()) {
            budget.markTruncated();
        }
        Event event = null;
        for (String line : tail.lines()) {
            if (!budget.available() || !budget.consume(line)) {
                break;
            }
            Optional<Instant> timestamp = timestamp(line);
            if (timestamp.isPresent()) {
                addIfMatched(file, event, request, window, matches);
                event = new Event(timestamp.get(), new ArrayList<>());
            }
            if (event != null) {
                event.lines.add(redact(line));
            }
        }
        addIfMatched(file, event, request, window, matches);
    }

    private static TailRead readTail(Path file, ScanAllocation allocation) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel channel = Files.newByteChannel(file, options);
        try (channel; Reader decoded = tailReader(channel, allocation.bytes());
             BufferedReader reader = new BufferedReader(decoded)) {
            List<String> lines = reader.lines().toList();
            int from = Math.max(0, lines.size() - allocation.lines());
            boolean truncated = channel.size() > allocation.bytes() || from > 0;
            return new TailRead(lines.subList(from, lines.size()), truncated);
        }
    }

    private static Reader tailReader(SeekableByteChannel channel, long byteLimit) throws IOException {
        long start = Math.max(0, channel.size() - byteLimit);
        channel.position(start);
        BufferedReader reader = new BufferedReader(
                Channels.newReader(channel, StandardCharsets.UTF_8));
        if (start > 0) {
            reader.readLine();
        }
        return reader;
    }

    private void addIfMatched(Path file, Event event, LogQueryRequest request,
                              QueryWindow window, List<Match> matches) {
        if (event == null || !window.contains(event.timestamp)) {
            return;
        }
        String text = String.join("\n", event.lines);
        if (contains(text, request.traceId()) && contains(text, request.keyword())
                && contains(text, request.level())) {
            matches.add(new Match(realRoot.relativize(file).toString(), event.timestamp, text));
        }
    }

    private static boolean contains(String text, String expected) {
        return expected == null || expected.isBlank()
                || text.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private Optional<Instant> timestamp(String line) {
        Matcher iso = ISO.matcher(line);
        if (iso.find()) {
            try {
                return Optional.of(Instant.parse(iso.group(1)));
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
        Matcher local = LOCAL.matcher(line);
        if (!local.find()) {
            return Optional.empty();
        }
        try {
            DateTimeFormatter format = local.group(1).contains(".") ? LOCAL_MILLIS : LOCAL_SECONDS;
            return Optional.of(LocalDateTime.parse(local.group(1), format)
                    .atZone(source.logZone()).toInstant());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private String render(List<Match> matches) {
        StringBuilder out = new StringBuilder();
        matches.forEach(match -> out.append("\n--- ").append(match.logicalFile)
                .append(" @ ").append(match.timestamp).append(" ---\n").append(match.text));
        return out.isEmpty() ? "" : out.substring(1);
    }

    private static String redact(String line) {
        String authorizationRedacted = AUTHORIZATION.matcher(line).replaceAll("$1=***");
        return SECRET.matcher(authorizationRedacted).replaceAll("$1=***");
    }

    private record Event(Instant timestamp, List<String> lines) { }
    private record Match(String logicalFile, Instant timestamp, String text) { }
    private record CandidateSet(List<Path> files, boolean truncated) {
        private CandidateSet {
            files = List.copyOf(files);
        }
    }
    private record ScanAllocation(int lines, long bytes) { }
    private record TailRead(List<String> lines, boolean truncated) {
        private TailRead {
            lines = List.copyOf(lines);
        }
    }

    private record QueryWindow(Instant start, Instant end) {
        private static QueryWindow parse(LogQueryRequest request, Instant now) throws IOException {
            try {
                Instant start = request.startTime().isBlank() ? Instant.EPOCH : Instant.parse(request.startTime());
                Instant end = request.endTime().isBlank() ? now : Instant.parse(request.endTime());
                if (!start.isBefore(end) || end.isAfter(now)) {
                    throw new IOException("invalid absolute log query time window");
                }
                return new QueryWindow(start, end);
            } catch (DateTimeParseException failure) {
                throw new IOException("invalid absolute log query time window", failure);
            }
        }
        private boolean contains(Instant timestamp) {
            return !timestamp.isBefore(start) && timestamp.isBefore(end);
        }
    }

    private static final class ScanBudget {
        private int lines;
        private long bytes;
        private final long deadlineNanos;
        private final LongSupplier nanoTime;
        private boolean truncated;

        private ScanBudget(int lines, long bytes, Duration duration, LongSupplier nanoTime) {
            this.lines = lines;
            this.bytes = bytes;
            this.nanoTime = nanoTime;
            this.deadlineNanos = nanoTime.getAsLong() + duration.toNanos();
        }

        private boolean consume(String line) {
            long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (lines <= 0 || bytes < size) {
                truncated = true;
                return false;
            }
            lines--;
            bytes -= size;
            return true;
        }

        private boolean available() throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("local log query interrupted");
            }
            if (nanoTime.getAsLong() - deadlineNanos >= 0) {
                truncated = true;
                return false;
            }
            return true;
        }

        private boolean truncated() {
            return truncated;
        }

        private ScanAllocation allocate(int remainingFiles) {
            return new ScanAllocation(Math.max(1, lines / remainingFiles),
                    Math.max(1L, bytes / remainingFiles));
        }

        private void markTruncated() {
            truncated = true;
        }
    }
}
