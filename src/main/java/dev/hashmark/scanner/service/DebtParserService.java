package dev.hashmark.scanner.service;

import dev.hashmark.common.util.SecretMasker;
import dev.hashmark.debt.dto.DebtDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kaynak koddaki yorumları dil farkındalığıyla çıkarır ve etiketler.
 *
 * <ol>
 *   <li>Tarama: dosya uzantısına göre bir {@link LanguageProfile} seçilir; satır/blok yorumları,
 *       string literal'lar (çok satırlı olanlar dahil) ve Python docstring'leri ayrıştırılır.
 *       Böylece string içindeki {@code "// TODO"} veya C'deki {@code #include} yorum sayılmaz,
 *       {@code x = 1; // TODO: fix} gibi satır içi yorumlar ise yakalanır.</li>
 *   <li>Dönüşüm: yorumlar etiketlenir (TODO/FIXME/HACK/XXX/NOTE/DOC/INFO + {@code .hashmark.yml}
 *       özel etiketleri, bkz. {@link LabelRules}), çok satırlı borç
 *       açıklamaları birleştirilir, {@code TODO(mahir)}, {@code FIXME[P1]}, tarih ve issue
 *       referansı gibi üst veriler çıkarılır.</li>
 * </ol>
 */
@Service
public class DebtParserService {

    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int MAX_CONTINUATION_LINES = 5;


    private static final Pattern DOC_TAG_LINE = Pattern.compile(
            "^(?:@(?:param|return|returns|throws|exception|see|since|author|deprecated|version|example|link|inheritDoc|type|typedef|template)\\b|:(?:param|return|returns|raises|type|rtype)\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LICENSE_LINE = Pattern.compile(
            "copyright|licen[sc]e|spdx-license-identifier|all rights reserved",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INFO_LINE = Pattern.compile(
            "\\b(?:config(?:uration)?|settings?|env(?:ironment)?|defaults?|version|url|uri|endpoint|ports?|timeouts?|https?|api[ -]?key|auth(?:entication|orization)?)\\b|\\b(?:key|default):",
            Pattern.CASE_INSENSITIVE);

    // Linter/derleyici/editör direktifleri: bilgi içermez, listeye alınmaz.
    private static final Pattern DIRECTIVE = Pattern.compile(
            "^(?:eslint\\b|@ts-|prettier-ignore|istanbul\\b|c8\\b|nolint\\b|noqa\\b|pylint:|mypy:|type:\\s|fmt:|isort:|pragma\\b|noinspection\\b|region\\b|endregion\\b|</?editor-fold|go:|-\\*-|vim:|coding[:=]|!/)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PRIORITY_WORD = Pattern.compile(
            "^(?:p[0-4]|critical|urgent|blocker|high|medium|med|normal|low|minor)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIORITY_IN_CONTENT = Pattern.compile("\\bP([0-4])\\b");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
    private static final Pattern ISSUE_REF = Pattern.compile("(?<![A-Za-z0-9_])(#\\d+|[A-Z][A-Z0-9]{1,9}-\\d+)(?![A-Za-z0-9_])");
    private static final Pattern HANDLE = Pattern.compile("^@([A-Za-z0-9_][A-Za-z0-9_.-]{0,38})\\b");
    private static final Pattern USERNAME = Pattern.compile("^@?([A-Za-z0-9_][A-Za-z0-9_.-]{0,38})$");

    public List<DebtDto> parse(String fileContent, String filePath) {
        return parse(fileContent, filePath, LabelRules.DEFAULT);
    }

    /** @param rules depo yapılandırmasındaki özel / kapatılmış etiketler */
    public List<DebtDto> parse(String fileContent, String filePath, LabelRules rules) {
        List<DebtDto> debts = new ArrayList<>();
        if (fileContent == null || fileContent.isEmpty()) {
            return debts;
        }

        String[] lines = fileContent.split("\\r?\\n", -1);
        List<RawComment> comments = scan(lines, LanguageProfile.forPath(filePath));
        return toDebts(comments, filePath, rules);
    }

    // ------------------------------------------------------------------
    // Aşama 1: Tarama (yorumları string/kod içinden ayıkla)
    // ------------------------------------------------------------------

    private List<RawComment> scan(String[] lines, LanguageProfile profile) {
        List<RawComment> out = new ArrayList<>();

        String blockEnd = null;
        boolean blockDoc = false;
        boolean blockOwnLine = false;
        boolean blockStripStars = true;
        int blockId = 0;
        String stringEnd = null;
        boolean rubyBlock = false;

        int groupSeq = 0;
        int lastLineGroup = -1;
        int lastLineNo = -2;
        String lastToken = null;

        for (int ln = 0; ln < lines.length; ln++) {
            String line = lines[ln];
            int lineNo = ln + 1;
            int n = line.length();
            int i = (ln == 0 && n > 0 && line.charAt(0) == '﻿') ? 1 : 0;

            if (profile.rubyBlocks) {
                if (rubyBlock) {
                    if (line.startsWith("=end")) {
                        rubyBlock = false;
                    } else {
                        addBlock(out, lineNo, line, blockId, true, true, true);
                    }
                    continue;
                }
                if (line.startsWith("=begin")) {
                    rubyBlock = true;
                    blockId = ++groupSeq;
                    continue;
                }
            }

            scanLine:
            while (i < n) {
                if (blockEnd != null) {
                    int end = line.indexOf(blockEnd, i);
                    String segment = end >= 0 ? line.substring(i, end) : line.substring(i);
                    addBlock(out, lineNo, segment, blockId, blockDoc, blockOwnLine, blockStripStars);
                    if (end < 0) {
                        i = n;
                    } else {
                        i = end + blockEnd.length();
                        blockEnd = null;
                    }
                    continue;
                }

                if (stringEnd != null) {
                    int end = findStringEnd(line, i, stringEnd);
                    if (end < 0) {
                        i = n;
                    } else {
                        i = end;
                        stringEnd = null;
                    }
                    continue;
                }

                char c = line.charAt(i);
                if (c == '\\') { // kaçış: regex literal içindeki \/\/ gibi dizileri yorum sanma
                    i += 2;
                    continue;
                }

                boolean ownLine = line.substring(0, i).isBlank();

                // Blok yorum başlangıcı
                for (String[] pair : profile.blockPairs) {
                    if (line.startsWith(pair[0], i)) {
                        blockEnd = pair[1];
                        blockId = ++groupSeq;
                        blockOwnLine = ownLine;
                        blockDoc = ownLine || line.startsWith("/**", i);
                        blockStripStars = true;
                        i += pair[0].length();
                        continue scanLine;
                    }
                }

                // Satır yorumu
                for (LineToken lt : profile.lineTokens) {
                    if (!line.startsWith(lt.token, i)) {
                        continue;
                    }
                    if (lt.needsSpaceBefore && i > 0 && !Character.isWhitespace(line.charAt(i - 1))) {
                        continue;
                    }
                    String raw = line.substring(i + lt.token.length());
                    int group;
                    if (ownLine && lastLineNo == lineNo - 1 && lt.token.equals(lastToken)) {
                        group = lastLineGroup;
                    } else {
                        group = ++groupSeq;
                    }
                    if (ownLine) {
                        lastLineGroup = group;
                        lastLineNo = lineNo;
                        lastToken = lt.token;
                    }
                    boolean docLine = lt.token.equals("//") && (raw.startsWith("/") || raw.startsWith("!"));
                    out.add(new RawComment(lineNo, stripLineMarker(raw, lt.token.charAt(0)), ownLine, group, docLine));
                    break scanLine;
                }

                // Üçlü tırnak (Python docstring, Java/Kotlin text block ...)
                if (profile.tripleQuotes && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i))) {
                    String delimiter = line.substring(i, i + 3);
                    if (profile.docstrings && ownLine) {
                        blockEnd = delimiter;
                        blockId = ++groupSeq;
                        blockOwnLine = true;
                        blockDoc = true;
                        blockStripStars = false;
                    } else {
                        stringEnd = delimiter;
                    }
                    i += 3;
                    continue;
                }

                // Tek satırlık / backtick string
                if (profile.quotes.indexOf(c) >= 0) {
                    int end = findStringEnd(line, i + 1, String.valueOf(c));
                    if (end < 0) {
                        if (c == '`') {
                            stringEnd = "`"; // şablon string'ler satır aşabilir
                        }
                        i = n;
                    } else {
                        i = end;
                    }
                    continue;
                }

                i++;
            }
        }
        return out;
    }

    private static void addBlock(List<RawComment> out, int lineNo, String segment, int blockId,
                                 boolean docLike, boolean ownLine, boolean stripStars) {
        String text = stripStars
                ? segment.replaceAll("^[\\s*]+", "").replaceAll("[\\s*]+$", "")
                : segment.trim();
        if (text.isEmpty()) {
            return;
        }
        out.add(new RawComment(lineNo, text, ownLine, blockId, docLike));
    }

    private static String stripLineMarker(String raw, char markerChar) {
        int k = 0;
        while (k < raw.length() && (raw.charAt(k) == markerChar || (markerChar == '/' && raw.charAt(k) == '!'))) {
            k++;
        }
        return raw.substring(k).trim();
    }

    private static int findStringEnd(String line, int from, String delimiter) {
        int i = from;
        while (i < line.length()) {
            if (line.charAt(i) == '\\') {
                i += 2;
                continue;
            }
            if (line.startsWith(delimiter, i)) {
                return i + delimiter.length();
            }
            i++;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Aşama 2: Dönüşüm (etiketleme, birleştirme, üst veri)
    // ------------------------------------------------------------------

    private List<DebtDto> toDebts(List<RawComment> comments, String filePath, LabelRules rules) {
        // Bir satır için tek kayıt (DB'de UNIQUE(repo, file, line) var); borç etiketleri öncelikli.
        Map<Integer, DebtDto> byLine = new LinkedHashMap<>();
        boolean[] consumed = new boolean[comments.size()];

        for (int i = 0; i < comments.size(); i++) {
            if (consumed[i]) {
                continue;
            }
            RawComment current = comments.get(i);
            DebtDto dto = null;

            ParsedLabel parsed = matchExplicitLabel(current.text, rules);
            if (parsed != null) {
                StringBuilder content = new StringBuilder(parsed.content);
                int previousLine = current.line;
                int appended = 0;
                for (int j = i + 1; j < comments.size() && appended < MAX_CONTINUATION_LINES; j++) {
                    RawComment next = comments.get(j);
                    if (next.group != current.group || next.line != previousLine + 1) {
                        break;
                    }
                    if (matchExplicitLabel(next.text, rules) != null || DOC_TAG_LINE.matcher(next.text).find()) {
                        break;
                    }
                    if (content.length() > 0) {
                        content.append(' ');
                    }
                    content.append(next.text);
                    consumed[j] = true;
                    previousLine = next.line;
                    appended++;
                }
                String text = content.toString().trim();
                if (!text.isEmpty()) {
                    dto = buildDto(filePath, current.line, parsed.label, text, parsed.tag);
                }
            } else {
                dto = toPlainComment(current, filePath);
            }

            if (dto != null && rules.isEnabled(dto.getLabel())) {
                putPreferred(byLine, dto);
            }
        }
        return new ArrayList<>(byLine.values());
    }

    private DebtDto toPlainComment(RawComment comment, String filePath) {
        String text = comment.text;
        if (alphanumericCount(text) < 2 || DIRECTIVE.matcher(text).find()) {
            return null;
        }
        return buildDto(filePath, comment.line, classify(comment), text, null);
    }

    private String classify(RawComment comment) {
        String text = comment.text;
        if (LICENSE_LINE.matcher(text).find()) {
            return "INFO";
        }
        if (comment.docLike || DOC_TAG_LINE.matcher(text).find()) {
            return "DOC";
        }
        if (INFO_LINE.matcher(text).find()) {
            return "INFO";
        }
        return "NOTE";
    }

    private ParsedLabel matchExplicitLabel(String text, LabelRules rules) {
        Matcher m = rules.explicitLabel().matcher(text);
        if (!m.matches()) {
            return null;
        }
        String word = m.group(1);
        String label = word.toUpperCase(Locale.ROOT);
        boolean upper = word.equals(label);
        boolean hasTag = m.group(2) != null;
        boolean colon = ":".equals(m.group(3));

        boolean accepted = switch (label) {
            case "TODO", "FIXME" -> true;
            case "HACK", "XXX" -> upper || colon || hasTag;
            case "NOTE", "DOC", "INFO" -> upper || colon; // "Note that..." gibi düz cümleleri etiket sayma
            default -> upper || colon || hasTag; // özel etiketler (.hashmark.yml): "Refactor this" etiket değil
        };
        if (!accepted) {
            return null;
        }
        return new ParsedLabel(label, m.group(2), cleanContent(m.group(4)));
    }

    private static String cleanContent(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.endsWith("*/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2).trim();
        } else if (cleaned.endsWith("-->")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
    }

    private DebtDto buildDto(String filePath, int line, String label, String rawContent, String tag) {
        // Yorumda yanlışlıkla bulunan sırlar veritabanına yazılmadan önce maskelenir.
        String content = SecretMasker.mask(rawContent);
        String clipped = content.length() > MAX_CONTENT_LENGTH
                ? content.substring(0, MAX_CONTENT_LENGTH - 1) + "…"
                : content;

        DebtDto.DebtDtoBuilder builder = DebtDto.builder()
                .filePath(filePath)
                .lineNo(line)
                .label(label)
                .content(clipped);

        if (tag != null || LabelRules.isDebt(label)) {
            applyMetadata(builder, tag, content);
        }
        return builder.build();
    }

    private void applyMetadata(DebtDto.DebtDtoBuilder builder, String tag, String content) {
        String assignee = null;
        String priority = null;
        String issueRef = null;
        LocalDate dueDate = null;

        if (tag != null) {
            for (String rawToken : tag.split("[,;/]")) {
                String token = rawToken.trim();
                if (token.isEmpty()) {
                    continue;
                }
                if (PRIORITY_WORD.matcher(token).matches()) {
                    priority = normalizePriority(token);
                } else if (ISO_DATE.matcher(token).matches()) {
                    dueDate = parseDate(token);
                } else if (ISSUE_REF.matcher(token).matches()) {
                    issueRef = token;
                } else if (assignee == null) {
                    Matcher user = USERNAME.matcher(token);
                    if (user.matches()) {
                        assignee = user.group(1);
                    }
                }
            }
        }

        if (assignee == null) {
            Matcher handle = HANDLE.matcher(content);
            if (handle.find()) {
                assignee = handle.group(1);
            }
        }
        if (priority == null) {
            Matcher p = PRIORITY_IN_CONTENT.matcher(content);
            if (p.find()) {
                priority = normalizePriority("P" + p.group(1));
            }
        }
        if (dueDate == null) {
            Matcher d = ISO_DATE.matcher(content);
            if (d.find()) {
                dueDate = parseDate(d.group(1));
            }
        }
        if (issueRef == null) {
            Matcher r = ISSUE_REF.matcher(content);
            if (r.find()) {
                issueRef = r.group(1);
            }
        }

        builder.assignee(assignee).priority(priority).dueDate(dueDate).issueRef(issueRef);
    }

    private static String normalizePriority(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "p0", "critical", "urgent", "blocker" -> "CRITICAL";
            case "p1", "high" -> "HIGH";
            case "p2", "medium", "med", "normal" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int alphanumericCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static void putPreferred(Map<Integer, DebtDto> byLine, DebtDto dto) {
        DebtDto existing = byLine.get(dto.getLineNo());
        if (existing == null
                || (!LabelRules.isDebt(existing.getLabel()) && LabelRules.isDebt(dto.getLabel()))) {
            byLine.put(dto.getLineNo(), dto);
        }
    }

    // ------------------------------------------------------------------
    // Yardımcı tipler
    // ------------------------------------------------------------------

    private record RawComment(int line, String text, boolean ownLine, int group, boolean docLike) {
    }

    private record ParsedLabel(String label, String tag, String content) {
    }

    private record LineToken(String token, boolean needsSpaceBefore) {
    }

    /** Dosya uzantısına göre yorum ve string sözdizimi. */
    private static final class LanguageProfile {

        private final List<LineToken> lineTokens;
        private final List<String[]> blockPairs;
        private final String quotes;
        private final boolean tripleQuotes;
        private final boolean docstrings;
        private final boolean rubyBlocks;

        private LanguageProfile(List<LineToken> lineTokens, List<String[]> blockPairs, String quotes,
                                boolean tripleQuotes, boolean docstrings, boolean rubyBlocks) {
            this.lineTokens = lineTokens;
            this.blockPairs = blockPairs;
            this.quotes = quotes;
            this.tripleQuotes = tripleQuotes;
            this.docstrings = docstrings;
            this.rubyBlocks = rubyBlocks;
        }

        private static final String[] C_BLOCK = {"/*", "*/"};
        private static final String[] HTML_BLOCK = {"<!--", "-->"};

        private static final LanguageProfile C_LIKE = new LanguageProfile(
                List.of(new LineToken("//", false)), List.<String[]>of(C_BLOCK), "\"'", false, false, false);
        private static final LanguageProfile JVM_LIKE = new LanguageProfile(
                List.of(new LineToken("//", false)), List.<String[]>of(C_BLOCK), "\"'", true, false, false);
        private static final LanguageProfile JS_LIKE = new LanguageProfile(
                List.of(new LineToken("//", false)), List.<String[]>of(C_BLOCK), "\"'`", false, false, false);
        private static final LanguageProfile RUST = new LanguageProfile(
                List.of(new LineToken("//", false)), List.<String[]>of(C_BLOCK), "\"", false, false, false);
        private static final LanguageProfile CSS = new LanguageProfile(
                List.of(), List.<String[]>of(C_BLOCK), "\"'", false, false, false);
        private static final LanguageProfile PYTHON = new LanguageProfile(
                List.of(new LineToken("#", false)), List.of(), "\"'", true, true, false);
        private static final LanguageProfile RUBY = new LanguageProfile(
                List.of(new LineToken("#", false)), List.of(), "\"'", false, false, true);
        private static final LanguageProfile HASH = new LanguageProfile(
                List.of(new LineToken("#", true)), List.of(), "\"'", false, false, false);
        private static final LanguageProfile SQL = new LanguageProfile(
                List.of(new LineToken("--", false)), List.<String[]>of(C_BLOCK), "\"'", false, false, false);
        private static final LanguageProfile MARKUP = new LanguageProfile(
                List.of(), List.<String[]>of(HTML_BLOCK), "", false, false, false);
        private static final LanguageProfile VUE = new LanguageProfile(
                List.of(new LineToken("//", false)), List.of(C_BLOCK, HTML_BLOCK), "\"'`", false, false, false);
        private static final LanguageProfile GENERIC = new LanguageProfile(
                List.of(new LineToken("//", true), new LineToken("#", true), new LineToken("--", true)),
                List.of(C_BLOCK, HTML_BLOCK), "\"'", false, false, false);

        static LanguageProfile forPath(String filePath) {
            if (filePath == null) {
                return GENERIC;
            }
            int dot = filePath.lastIndexOf('.');
            if (dot < 0 || dot == filePath.length() - 1) {
                return GENERIC;
            }
            return switch (filePath.substring(dot + 1).toLowerCase(Locale.ROOT)) {
                case "java", "kt", "kts", "cs", "swift", "scala", "dart" -> JVM_LIKE;
                case "ts", "tsx", "js", "jsx", "mjs", "cjs", "go" -> JS_LIKE;
                case "c", "h", "cpp", "cc", "cxx", "hpp", "php", "scss", "less" -> C_LIKE;
                case "rs" -> RUST;
                case "css" -> CSS;
                case "py", "pyw" -> PYTHON;
                case "rb" -> RUBY;
                case "sh", "bash", "zsh", "yml", "yaml", "toml" -> HASH;
                case "sql" -> SQL;
                case "html", "htm", "xml", "svg" -> MARKUP;
                case "vue", "svelte" -> VUE;
                default -> GENERIC;
            };
        }
    }
}
