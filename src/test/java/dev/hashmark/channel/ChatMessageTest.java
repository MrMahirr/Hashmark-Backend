package dev.hashmark.channel;

import dev.hashmark.report.dto.DebtInsights;
import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.dto.TrendDataPoint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ChatMessage message = new ChatMessage("Title", List.of("<!channel> @everyone *bold* repo"),
            "Open", "https://app.example/dashboard");

    @Test
    void slackEscapesControlCharacters() {
        JsonNode body = JSON.readTree(message.toJson(ChannelKind.SLACK));
        String text = body.get("text").asString();
        assertTrue(text.startsWith("*Title*"));
        assertTrue(text.contains("&lt;!channel&gt;"), text);
        assertFalse(text.contains("<!channel>"));
        assertTrue(text.endsWith("<https://app.example/dashboard|Open>"));
    }

    @Test
    void discordDisablesMentionsAndEscapesMarkdown() {
        JsonNode body = JSON.readTree(message.toJson(ChannelKind.DISCORD));
        assertEquals(0, body.get("allowed_mentions").get("parse").size());
        String content = body.get("content").asString();
        assertTrue(content.contains("\\*bold\\*"), content);
        assertTrue(content.contains("[Open](<https://app.example/dashboard>)"));
    }

    @Test
    void discordContentIsCappedAt2000() {
        ChatMessage longMessage = new ChatMessage("T", List.of("x".repeat(3000)), null, null);
        JsonNode body = JSON.readTree(longMessage.toJson(ChannelKind.DISCORD));
        assertEquals(2000, body.get("content").asString().length());
    }

    @Test
    void teamsSendsAnAdaptiveCard() {
        JsonNode body = JSON.readTree(message.toJson(ChannelKind.TEAMS));
        assertEquals("message", body.get("type").asString());
        JsonNode card = body.get("attachments").get(0);
        assertEquals("application/vnd.microsoft.card.adaptive", card.get("contentType").asString());
        assertEquals("AdaptiveCard", card.get("content").get("type").asString());
        assertEquals(2, card.get("content").get("body").size());
        assertEquals("https://app.example/dashboard", card.get("content").get("actions").get(0).get("url").asString());
    }

    @Test
    void digestSummarizesInsightsInTurkishAndEnglish() {
        DebtInsights insights = DebtInsights.builder().openDebts(12).weightedDebt(20).staleCount(4).secretCount(1)
                .priorityCounts(Map.of("CRITICAL", 1, "HIGH", 2)).build();
        LabelStats labels = new LabelStats(8, 4, 0, 0, 3, 0, 0);
        SummaryResponse summary = SummaryResponse.builder().insights(insights).labelStats(labels)
                .trendData(List.of(new TrendDataPoint(LocalDate.of(2026, 10, 12), 12, 3, 1))).build();

        ChatMessage tr = new ChannelMessages("https://app.example/", "tr").digest(summary, "DAILY");
        assertEquals("Hashmark · günlük borç özeti", tr.title());
        assertEquals("12 açık borç (bu hafta +3 yeni, 1 çözüldü)", tr.lines().get(0));
        assertEquals("Sağlık B (70/100) · 90+ gün: 4 · acil: 3", tr.lines().get(1));
        assertTrue(tr.lines().get(2).contains("1 sızan sır"));
        assertEquals("TODO 8 · FIXME 4", tr.lines().get(3));
        assertEquals("https://app.example/dashboard", tr.linkUrl());

        ChatMessage en = new ChannelMessages("https://app.example", "en").digest(summary, null);
        assertEquals("Hashmark · weekly debt summary", en.title());
    }

    @Test
    void alertsLinkToTheRepository() {
        ChannelMessages messages = new ChannelMessages("https://app.example", "tr");
        assertEquals("https://app.example/dashboard/repos/7", messages.threshold("o/r", 7, 51, 50).linkUrl());
        assertEquals("o/r: 51 açık borç (eşik 50)", messages.threshold("o/r", 7, 51, 50).lines().get(0));
        assertEquals("https://app.example/dashboard/debts?repoId=7&secret=1", messages.secrets("o/r", 7, 2).linkUrl());
    }
}
