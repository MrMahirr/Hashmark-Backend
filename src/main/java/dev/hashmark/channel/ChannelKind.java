package dev.hashmark.channel;

import dev.hashmark.common.exception.ApiException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Bildirim kanalı türü ve izin verilen gelen-webhook adresleri.
 *
 * <p>Adres kullanıcı girdisidir ve sunucu ona POST atar: SSRF'e karşı yalnızca sağlayıcının kendi alan adları,
 * yalnızca HTTPS ve varsayılan port kabul edilir; kullanıcı bilgisi, IP adresi ve parça içeren adres reddedilir.
 * Taşıyıcı yönlendirme izlemez ({@link JdkChannelTransport}), böylece izinli bir adres başka bir yere sektirilemez.
 */
public enum ChannelKind {

    /** {@code https://hooks.slack.com/services/...} (gelen webhook) ya da {@code /triggers/...} (iş akışı) */
    SLACK(List.of("hooks.slack.com"), List.of(), List.of("/services/", "/triggers/", "/workflows/")),
    /** {@code https://discord.com/api/webhooks/<id>/<token>} */
    DISCORD(List.of("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com"), List.of(),
            List.of("/api/webhooks/")),
    /**
     * Teams: Workflows (Power Automate) {@code *.logic.azure.com} / {@code *.api.powerplatform.com} ya da eski
     * Office 365 bağlayıcısı {@code *.webhook.office.com}.
     */
    TEAMS(List.of(), List.of(".webhook.office.com", ".logic.azure.com", ".api.powerplatform.com"), List.of("/"));

    public static final int MAX_URL_LENGTH = 500;

    private final List<String> hosts;
    private final List<String> hostSuffixes;
    private final List<String> pathPrefixes;

    ChannelKind(List<String> hosts, List<String> hostSuffixes, List<String> pathPrefixes) {
        this.hosts = hosts;
        this.hostSuffixes = hostSuffixes;
        this.pathPrefixes = pathPrefixes;
    }

    public static ChannelKind parse(String raw) {
        if (raw == null) {
            throw ApiException.badRequest("kind must be one of SLACK, DISCORD, TEAMS");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("kind must be one of SLACK, DISCORD, TEAMS");
        }
    }

    /**
     * @return normalize edilmiş adres
     * @throws ApiException 400, adres bu tür için izinli değilse (mesaj adresi içermez)
     */
    public String validate(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH) {
            throw invalid();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalid();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || !hostAllowed(host) || path.contains("..")) {
            throw invalid();
        }
        if (pathPrefixes.stream().noneMatch(path::startsWith) || path.length() <= 1) {
            throw invalid();
        }
        return uri.toString();
    }

    private boolean hostAllowed(String host) {
        if (hosts.contains(host)) {
            return true;
        }
        // "evil.webhook.office.com.attacker.net" eşleşmez: sonek, alan adının sonu olmalı
        return hostSuffixes.stream().anyMatch(s -> host.endsWith(s) && host.length() > s.length());
    }

    private ApiException invalid() {
        return ApiException.badRequest("url is not a valid " + name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT)
                + " incoming webhook address");
    }

    /** Arayüzde gösterilecek maskeli ipucu: alan adı + yolun son 4 karakteri (sır kısmı gösterilmez). */
    public static String hint(String url) {
        URI uri = URI.create(url);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String tail = path.length() > 4 ? path.substring(path.length() - 4) : "";
        String hint = uri.getHost() + "/…" + tail;
        return hint.length() > 80 ? hint.substring(0, 80) : hint;
    }
}
