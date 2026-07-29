package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.port.SecretScope;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolCatalog;
import com.anthropic.agentkit.domain.tool.ToolCatalogSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scope-partitioned MCP connection and atomic catalog lifecycle. */
public final class McpServerManager implements ToolCatalog, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, McpServerConfig> configs;
    private final McpSessionFactory sessionFactory;
    private final ConcurrentMap<SecretScope, ScopeState> scopes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpServerManager(List<McpServerConfig> configs) {
        this(configs, new LangChain4jMcpSessionFactory());
    }

    public McpServerManager(
            List<McpServerConfig> configs, McpSessionFactory sessionFactory) {
        this.configs = uniqueConfigs(configs);
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    @Override
    public ToolCatalogSnapshot snapshot(ExecutionContext context) {
        requireOpen();
        ScopeState scope = state(context);
        List<Tool> tools = new ArrayList<>();
        for (McpServerConfig config : configs.values()) {
            tools.addAll(scope.server(config).tools(context));
        }
        return new ToolCatalogSnapshot("mcp", tools);
    }

    public ToolCatalogSnapshot refresh(
            ExecutionContext context, String serverId) {
        requireOpen();
        ScopeState scope = state(context);
        scope.server(config(serverId)).refresh(context);
        return snapshot(context);
    }

    public void expose(
            ExecutionContext context, String serverId, Collection<String> rawNames) {
        requireOpen();
        state(context).server(config(serverId)).expose(context, rawNames);
    }

    public void close(ExecutionContext context) {
        SecretScope key = scopeOf(context);
        ScopeState removed = scopes.remove(key);
        if (removed != null) {
            removed.close();
            log.info("MCP scope closed: run={}, workspace={}", key.runId(), key.workspaceId());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<ScopeState> states = List.copyOf(scopes.values());
        scopes.clear();
        states.forEach(ScopeState::close);
        log.info("MCP manager closed: scopes={}", states.size());
    }

    private ScopeState state(ExecutionContext context) {
        SecretScope key = scopeOf(context);
        return scopes.computeIfAbsent(key, ScopeState::new);
    }

    private McpServerConfig config(String serverId) {
        McpServerConfig config = configs.get(serverId);
        if (config == null) {
            throw new IllegalArgumentException("unknown MCP server: " + serverId);
        }
        return config;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("MCP server manager is closed");
        }
    }

    private static Map<String, McpServerConfig> uniqueConfigs(
            List<McpServerConfig> declared) {
        Objects.requireNonNull(declared, "configs");
        Map<String, McpServerConfig> unique = new LinkedHashMap<>();
        for (McpServerConfig config : declared) {
            Objects.requireNonNull(config, "config");
            if (unique.putIfAbsent(config.id(), config) != null) {
                throw new IllegalArgumentException("duplicate MCP server id: " + config.id());
            }
        }
        return Map.copyOf(unique);
    }

    private static SecretScope scopeOf(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        return new SecretScope(context.runId(), context.workspaceId());
    }

    private final class ScopeState implements AutoCloseable {
        private final SecretScope scope;
        private final ConcurrentMap<String, ServerState> servers = new ConcurrentHashMap<>();

        private ScopeState(SecretScope scope) {
            this.scope = scope;
        }

        private ServerState server(McpServerConfig config) {
            return servers.computeIfAbsent(config.id(), ignored -> new ServerState(scope, config));
        }

        @Override
        public void close() {
            List<ServerState> states = List.copyOf(servers.values());
            servers.clear();
            states.forEach(ServerState::close);
        }
    }

    private final class ServerState implements AutoCloseable {
        private final SecretScope scope;
        private final McpServerConfig config;
        private final Object lifecycleLock = new Object();
        private final AtomicReference<CatalogGeneration> catalog = new AtomicReference<>();
        private final List<McpSession> retiredSessions = new ArrayList<>();
        private volatile McpSession session;

        private ServerState(SecretScope scope, McpServerConfig config) {
            this.scope = scope;
            this.config = config;
        }

        private List<Tool> tools(ExecutionContext context) {
            requireScope(context);
            ensureCatalog(context);
            return catalog.get().tools();
        }

        private void refresh(ExecutionContext context) {
            requireScope(context);
            synchronized (lifecycleLock) {
                refreshLocked(context);
            }
        }

        private void expose(ExecutionContext context, Collection<String> rawNames) {
            requireScope(context);
            ensureCatalog(context);
            synchronized (lifecycleLock) {
                CatalogGeneration current = catalog.get();
                Set<String> selected = new LinkedHashSet<>(current.selected());
                selected.addAll(validateSelection(current.descriptors(), rawNames));
                catalog.set(buildGeneration(current.descriptors(), selected));
            }
        }

        private McpCallResult call(
                McpToolDescriptor descriptor, ToolArguments arguments,
                ExecutionContext context) {
            requireScope(context);
            ensureCatalog(context);
            McpSession active = session;
            try {
                return active.call(descriptor.name(), arguments, context);
            } catch (McpConnectionException failure) {
                invalidate(active);
                throw failure;
            }
        }

        private void ensureCatalog(ExecutionContext context) {
            if (catalog.get() != null && session != null) {
                return;
            }
            synchronized (lifecycleLock) {
                if (catalog.get() == null || session == null) {
                    refreshLocked(context);
                }
            }
        }

        private void refreshLocked(ExecutionContext context) {
            McpSession active = session;
            boolean opened = active == null;
            if (opened) {
                active = sessionFactory.open(config, context);
            }
            try {
                List<McpToolDescriptor> descriptors = validate(active.discoverTools());
                Set<String> selected = previousSelection(descriptors);
                catalog.set(buildGeneration(descriptors, selected));
                session = active;
                closeRetired();
                log.info("MCP catalog refreshed: server={}, tools={}, exposed={}",
                        config.id(), descriptors.size(), catalog.get().tools().size());
            } catch (RuntimeException failure) {
                if (opened) {
                    closeQuietly(active);
                }
                throw failure;
            }
        }

        private CatalogGeneration buildGeneration(
                List<McpToolDescriptor> descriptors, Set<String> selected) {
            boolean deferred = descriptors.size() > config.eagerToolLimit();
            Set<String> effective = deferred ? Set.copyOf(selected) : namesOf(descriptors);
            List<Tool> tools = new ArrayList<>();
            if (deferred) {
                tools.add(new McpDiscoverTool(config.id(), this::discover));
            }
            descriptors.stream().filter(item -> effective.contains(item.name()))
                    .map(this::adapter).forEach(tools::add);
            return new CatalogGeneration(descriptors, effective, tools);
        }

        private McpToolAdapter adapter(McpToolDescriptor descriptor) {
            return new McpToolAdapter(config.id(), scope, descriptor, this::call);
        }

        private String discover(ToolArguments arguments, ExecutionContext context) {
            CatalogGeneration current = catalog.get();
            Set<String> matches = requestedNames(arguments);
            if (matches.isEmpty()) {
                matches = queryMatches(current.descriptors(), arguments);
            }
            expose(context, matches);
            return discoveryJson(matches);
        }

        private void invalidate(McpSession expected) {
            synchronized (lifecycleLock) {
                if (session == expected) {
                    session = null;
                    retiredSessions.add(expected);
                    log.warn("MCP session invalidated: server={}", config.id());
                }
            }
        }

        private Set<String> previousSelection(List<McpToolDescriptor> descriptors) {
            CatalogGeneration previous = catalog.get();
            if (previous == null) {
                return Set.of();
            }
            Set<String> available = namesOf(descriptors);
            return previous.selected().stream().filter(available::contains)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        private void requireScope(ExecutionContext context) {
            if (!scope.equals(scopeOf(context))) {
                throw new IllegalArgumentException("MCP session scope mismatch");
            }
        }

        @Override
        public void close() {
            synchronized (lifecycleLock) {
                McpSession active = session;
                session = null;
                catalog.set(null);
                closeQuietly(active);
                closeRetired();
            }
        }

        private void closeRetired() {
            List<McpSession> retired = List.copyOf(retiredSessions);
            retiredSessions.clear();
            retired.forEach(McpServerManager::closeQuietly);
        }

        private String discoveryJson(Set<String> selected) {
            try {
                return JSON.writeValueAsString(Map.of(
                        "server", config.id(),
                        "exposed", selected.stream()
                                .map(name -> config.id() + "." + name).toList()));
            } catch (Exception failure) {
                throw new McpProtocolException("failed to encode discovery result", failure);
            }
        }
    }

    private static List<McpToolDescriptor> validate(List<McpToolDescriptor> discovered) {
        Objects.requireNonNull(discovered, "MCP tool catalog");
        List<McpToolDescriptor> descriptors = List.copyOf(discovered);
        Set<String> names = new LinkedHashSet<>();
        for (McpToolDescriptor descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "MCP tool descriptor");
            validateSchema(descriptor);
            if (McpDiscoverTool.RAW_NAME.equals(descriptor.name()) || !names.add(descriptor.name())) {
                throw new McpProtocolException(
                        "duplicate or reserved MCP tool name: " + descriptor.name());
            }
        }
        return descriptors;
    }

    private static void validateSchema(McpToolDescriptor descriptor) {
        try {
            JsonNode schema = JSON.readTree(descriptor.inputSchema());
            if (schema == null || !schema.isObject()
                    || (!schema.path("type").isMissingNode()
                    && !"object".equals(schema.path("type").asText()))) {
                throw new McpProtocolException(
                        "MCP tool schema must be an object: " + descriptor.name());
            }
        } catch (McpProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new McpProtocolException(
                    "invalid MCP tool schema: " + descriptor.name(), failure);
        }
    }

    private static Set<String> validateSelection(
            List<McpToolDescriptor> descriptors, Collection<String> rawNames) {
        Objects.requireNonNull(rawNames, "rawNames");
        Set<String> available = namesOf(descriptors);
        Set<String> selected = new LinkedHashSet<>();
        for (String name : rawNames) {
            if (!available.contains(name)) {
                throw new IllegalArgumentException("unknown MCP tool selection: " + name);
            }
            selected.add(name);
        }
        return selected;
    }

    private static Set<String> requestedNames(ToolArguments arguments) {
        Object value = arguments.values().get("names");
        if (!(value instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        values.forEach(item -> names.add(String.valueOf(item)));
        return names;
    }

    private static Set<String> queryMatches(
            List<McpToolDescriptor> descriptors, ToolArguments arguments) {
        String query = arguments.getString("query", "").toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(arguments.getInt("limit", 8), 32));
        if (query.isBlank()) {
            throw new IllegalArgumentException("names or query is required");
        }
        return descriptors.stream().filter(descriptor -> matches(descriptor, query))
                .limit(limit).map(McpToolDescriptor::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean matches(McpToolDescriptor descriptor, String query) {
        return descriptor.name().toLowerCase(Locale.ROOT).contains(query)
                || descriptor.description().toLowerCase(Locale.ROOT).contains(query);
    }

    private static Set<String> namesOf(List<McpToolDescriptor> descriptors) {
        return descriptors.stream().map(McpToolDescriptor::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void closeQuietly(McpSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException failure) {
            log.warn("failed to close MCP session: errorType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private record CatalogGeneration(
            List<McpToolDescriptor> descriptors,
            Set<String> selected,
            List<Tool> tools) {
        private CatalogGeneration {
            descriptors = List.copyOf(descriptors);
            selected = Set.copyOf(selected);
            tools = List.copyOf(tools);
        }
    }
}
