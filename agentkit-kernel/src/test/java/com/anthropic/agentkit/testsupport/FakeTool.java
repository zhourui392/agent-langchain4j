package com.anthropic.agentkit.testsupport;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class FakeTool implements Tool {

    private final String name;
    private final boolean readOnly;
    private final Function<ToolArguments, ToolResult> behavior;
    private final AtomicInteger calls = new AtomicInteger(0);
    private final List<ToolArguments> receivedArgs = new ArrayList<>();

    private FakeTool(String name, boolean readOnly, Function<ToolArguments, ToolResult> behavior) {
        this.name = name;
        this.readOnly = readOnly;
        this.behavior = behavior;
    }

    public static FakeTool returning(String name, String output) {
        return new FakeTool(name, false, args -> ToolResult.ok(output));
    }

    public static FakeTool readOnlyReturning(String name, String output) {
        return new FakeTool(name, true, args -> ToolResult.ok(output));
    }

    public static FakeTool withBehavior(String name, Function<ToolArguments, ToolResult> behavior) {
        return new FakeTool(name, false, behavior);
    }

    public int callCount() {
        return calls.get();
    }

    public List<ToolArguments> receivedArgs() {
        return List.copyOf(receivedArgs);
    }

    @Override public String name() { return name; }
    @Override public String description() { return "fake " + name; }
    @Override public String inputSchema() { return "{}"; }
    @Override public boolean isReadOnly() { return readOnly; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        calls.incrementAndGet();
        receivedArgs.add(args);
        return behavior.apply(args);
    }
}
