package dev.hashmark.channel;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kanala gidecek düz metin mesaj: başlık, satırlar ve isteğe bağlı bağlantı. Her sağlayıcının biçimine burada
 * çevrilir. Satırlar depo adı gibi kullanıcı verisi içerebilir: Slack'te {@code & < >}, Discord'da markdown kaçışlanır
 * ve bahsetmeler ({@code @everyone}) {@code allowed_mentions} ile kapatılır.
 */
public record ChatMessage(String title, List<String> lines, String linkLabel, String linkUrl) {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int DISCORD_LIMIT = 2000;

    public ChatMessage {
        lines = List.copyOf(lines);
    }

    public String toJson(ChannelKind kind) {
        return JSON.writeValueAsString(switch (kind) {
            case SLACK -> slack();
            case DISCORD -> discord();
            case TEAMS -> teams();
        });
    }

    private Map<String, Object> slack() {
        StringBuilder text = new StringBuilder("*").append(slackEscape(title)).append('*');
        for (String line : lines) {
            text.append('\n').append(slackEscape(line));
        }
        if (linkUrl != null) {
            text.append("\n<").append(linkUrl).append('|').append(slackEscape(linkLabel)).append('>');
        }
        return Map.of("text", text.toString());
    }

    private Map<String, Object> discord() {
        StringBuilder text = new StringBuilder("**").append(markdownEscape(title)).append("**");
        for (String line : lines) {
            text.append('\n').append(markdownEscape(line));
        }
        if (linkUrl != null) {
            text.append("\n[").append(markdownEscape(linkLabel)).append("](<").append(linkUrl).append(">)");
        }
        String content = text.length() > DISCORD_LIMIT ? text.substring(0, DISCORD_LIMIT - 1) + "…" : text.toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("allowed_mentions", Map.of("parse", List.of()));
        return body;
    }

    /** Teams: Workflows ve eski bağlayıcıların ikisinin de kabul ettiği Adaptive Card eki. */
    private Map<String, Object> teams() {
        List<Object> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "TextBlock", "text", markdownEscape(title), "weight", "Bolder", "size", "Medium",
                "wrap", true));
        for (String line : lines) {
            blocks.add(Map.of("type", "TextBlock", "text", markdownEscape(line), "wrap", true, "spacing", "Small"));
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("body", blocks);
        if (linkUrl != null) {
            card.put("actions", List.of(Map.of("type", "Action.OpenUrl", "title", linkLabel, "url", linkUrl)));
        }
        return Map.of("type", "message", "attachments",
                List.of(Map.of("contentType", "application/vnd.microsoft.card.adaptive", "content", card)));
    }

    static String slackEscape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String markdownEscape(String text) {
        return text == null ? "" : text.replaceAll("([\\\\*_~`|>\\[\\]()#])", "\\\\$1");
    }
}
