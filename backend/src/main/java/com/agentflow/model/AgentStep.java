package com.agentflow.model;

import java.util.List;
import java.util.Map;

public class AgentStep {

    private final String kind;
    private final String tag;
    private final String title;
    private final ToolCall tool;
    private final List<String> lines;
    private final String resultType;
    private final Map<String, Object> result;
    private final List<String> list;
    private final long dur;

    public AgentStep(String kind, String tag, String title, ToolCall tool, List<String> lines,
                     String resultType, Map<String, Object> result, List<String> list, long dur) {
        this.kind = kind;
        this.tag = tag;
        this.title = title;
        this.tool = tool;
        this.lines = lines;
        this.resultType = resultType;
        this.result = result;
        this.list = list;
        this.dur = dur;
    }

    public String getKind() { return kind; }
    public String getTag() { return tag; }
    public String getTitle() { return title; }
    public ToolCall getTool() { return tool; }
    public List<String> getLines() { return lines; }
    public String getResultType() { return resultType; }
    public Map<String, Object> getResult() { return result; }
    public List<String> getList() { return list; }
    public long getDur() { return dur; }
}
