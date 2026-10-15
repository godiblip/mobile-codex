package dev.mobilecodex.app;

import org.json.*;
import org.junit.Test;
import java.util.List;
import java.util.Set;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

public class AgentGroupTest {
    static JSONObject session(String... agents) throws Exception {
        JSONArray list = new JSONArray();
        for (String id : agents) list.put(AgentGroup.newAgent(id, "m", "", "chat"));
        return obj("agents", list, "messages", new JSONArray());
    }
    static JSONObject message(String id, String role, String agent, String text) throws Exception {
        JSONObject m = obj("id", id, "role", role, "text", text, "status", "complete");
        if (agent != null) m.put("agent", agent).put("agentName", AgentGroup.name(agent));
        return m;
    }
    @Test public void mentionsFollowCallOrderAndIgnoreNonCallingText() throws Exception {
        List<String> people = AgentGroup.participants(session("claude", "gemini"));
        assertEquals(List.of("gemini", "codex"), AgentGroup.mentions("@제미나이야 먼저, 그다음 @코덱스", people));
        assertEquals(List.of("claude"), AgentGroup.mentions("@claude는 어때?", people));
        assertEquals(List.of(), AgentGroup.mentions("mail me@claude.ai, `@claude`, ```\n@gemini\n``` @file:claude.md @claudette @claude/x", people));
        assertEquals(List.of("codex", "claude", "gemini"), AgentGroup.mentions("@all 의견", people));
        assertEquals(List.of("gemini"), AgentGroup.mentions("@agy 확인", people));
    }
    @Test public void everyoneAnswersWithoutMentionAndCodexAlwaysLeads() throws Exception {
        JSONObject s = session("claude", "gemini");
        assertEquals(List.of("codex", "claude", "gemini"), AgentGroup.targets("의견 주세요", s));
        assertEquals(List.of("codex", "gemini"), AgentGroup.targets("@gemini 먼저 그리고 @codex", s));
        assertEquals(List.of("claude"), AgentGroup.targets("@claude 만", s));
        JSONObject removed = session("claude");
        removed.getJSONArray("agents").getJSONObject(0).put("enabled", false);
        assertFalse(AgentGroup.grouped(removed));
        assertEquals(List.of(), AgentGroup.mentions("@claude", AgentGroup.participants(removed)));
    }
    @Test public void agentPromptCarriesOnlyMessagesAfterItsCursor() throws Exception {
        JSONObject s = session("claude");
        JSONArray messages = s.getJSONArray("messages");
        messages.put(message("1", "user", null, "첫 질문")).put(message("2", "assistant", null, "Codex 답"))
            .put(message("3", "assistant", "claude", "Claude 답")).put(message("4", "user", null, "둘째 질문"));
        JSONObject claude = s.getJSONArray("agents").getJSONObject(0);
        String first = AgentGroup.agentPrompt(s, claude, messages);
        assertTrue(first.contains("[사용자] 첫 질문")); assertTrue(first.contains("[@codex (Codex)] Codex 답"));
        claude.put("cursorId", "3");
        String next = AgentGroup.agentPrompt(s, claude, messages);
        assertFalse(next.contains("첫 질문")); assertTrue(next.contains("[사용자] 둘째 질문"));
        assertTrue(next.contains("@claude (Claude)입니다"));
    }
    @Test public void unfinishedAgentAnswersAreNotShared() throws Exception {
        JSONArray messages = new JSONArray().put(message("1", "assistant", "gemini", "부분 답").put("status", "error"))
            .put(message("2", "user", null, "질문"));
        String text = AgentGroup.transcript(messages, 0, messages.length(), Set.of(), 1000);
        assertFalse(text.contains("부분 답")); assertTrue(text.contains("질문"));
    }
    @Test public void longBacklogKeepsNewestAndSaysWhatWasOmitted() throws Exception {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < 10; i++) messages.put(message(String.valueOf(i), "user", null, "메시지" + i + "x".repeat(40)));
        String text = AgentGroup.transcript(messages, 0, messages.length(), Set.of(), 200);
        assertTrue(text.startsWith("(앞선 메시지 ")); assertTrue(text.contains("메시지9")); assertFalse(text.contains("메시지0"));
    }
    @Test public void codexContextNamesOtherParticipants() throws Exception {
        JSONObject s = session("claude", "gemini").put("codexCursorId", "1");
        JSONArray messages = s.getJSONArray("messages").put(message("1", "user", null, "본 메시지")).put(message("2", "assistant", "claude", "새 의견"));
        String context = AgentGroup.codexContext(s, messages, messages.length(), Set.of());
        assertTrue(context.contains("@claude (Claude), @gemini (Gemini)"));
        assertTrue(context.contains("[@claude (Claude)] 새 의견")); assertFalse(context.contains("본 메시지"));
    }
}
