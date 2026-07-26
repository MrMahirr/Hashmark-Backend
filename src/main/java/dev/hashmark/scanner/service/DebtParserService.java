package dev.hashmark.scanner.service;

import dev.hashmark.debt.dto.DebtDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DebtParserService {

    // Açık etiket tanımı içeren yorumlar (Örn: // TODO: fix this, // NOTE: some note)
    private static final Pattern EXPLICIT_LABEL_PATTERN = Pattern.compile(
            "^(?://|#|\\*|--|/\\*|<!--)\\s*(TODO|FIXME|HACK|XXX|NOTE|DOC|INFO)[:\\s]+(.*)$",
            Pattern.CASE_INSENSITIVE
    );

    // Genel yorum satırı başlangıçları
    private static final Pattern COMMENT_LINE_PATTERN = Pattern.compile(
            "^(?:\\s*)(//|#|--|/\\*|\\*|<!--)(.*)$"
    );

    public List<DebtDto> parse(String fileContent, String filePath) {
        List<DebtDto> debts = new ArrayList<>();
        if (fileContent == null || fileContent.isEmpty()) {
            return debts;
        }

        String[] lines = fileContent.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmedLine = line.trim();

            if (trimmedLine.isEmpty()) {
                continue;
            }

            // 1. Önce açık bir etiket (TODO, FIXME, HACK, XXX, NOTE, DOC, INFO) var mı kontrol et
            Matcher explicitMatcher = EXPLICIT_LABEL_PATTERN.matcher(trimmedLine);
            if (explicitMatcher.find()) {
                String label = explicitMatcher.group(1).toUpperCase();
                String content = cleanCommentContent(explicitMatcher.group(2));
                
                if (!content.isEmpty()) {
                    debts.add(createDebtDto(filePath, i + 1, label, content));
                }
                continue;
            }

            // 2. Açık etiket yoksa genel yorum satırı mı kontrol et ve sınıflandır (NOTE, DOC, INFO)
            Matcher commentMatcher = COMMENT_LINE_PATTERN.matcher(line);
            if (commentMatcher.find()) {
                String prefix = commentMatcher.group(1);
                String rawContent = commentMatcher.group(2);
                String cleanedContent = cleanCommentContent(rawContent);

                // Boş veya sadece sembolden ibaret satırları atla (Örn: Sadece /* veya */ veya * olan satırlar)
                if (cleanedContent.isEmpty() || cleanedContent.length() < 2 || isJustSymbols(cleanedContent)) {
                    continue;
                }

                String label = classifyGeneralComment(prefix, cleanedContent);
                debts.add(createDebtDto(filePath, i + 1, label, cleanedContent));
            }
        }
        return debts;
    }

    private String classifyGeneralComment(String prefix, String content) {
        String lowerContent = content.toLowerCase();

        // Javadoc veya blok yorum dokümantasyon etiketleri içeriyorsa -> DOC
        if (prefix.equals("/*") || prefix.equals("*") || prefix.equals("<!--") ||
                lowerContent.startsWith("@param") || lowerContent.startsWith("@return") ||
                lowerContent.startsWith("@author") || lowerContent.startsWith("@see") ||
                lowerContent.startsWith("@since") || lowerContent.startsWith("@throws") ||
                lowerContent.startsWith("@deprecated")) {
            return "DOC";
        }

        // Bilgilendirici veya konfigürasyon anahtar kelimeleri içeriyorsa -> INFO
        if (lowerContent.contains("config") || lowerContent.contains("setting") ||
                lowerContent.contains("url") || lowerContent.contains("port") ||
                lowerContent.contains("http") || lowerContent.contains("version") ||
                lowerContent.contains("environment") || lowerContent.contains("auth") ||
                lowerContent.contains("key:") || lowerContent.contains("default:")) {
            return "INFO";
        }

        // Diğer tüm standart açıklamalar -> NOTE
        return "NOTE";
    }

    private String cleanCommentContent(String rawContent) {
        if (rawContent == null) {
            return "";
        }
        String cleaned = rawContent.trim();
        // Blok yorum sonlandırıcısını (*/ veya -->) temizle
        if (cleaned.endsWith("*/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2).trim();
        } else if (cleaned.endsWith("-->")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
    }

    private boolean isJustSymbols(String content) {
        return content.matches("^[*\\-/#=._+<>!|~^]{1,5}$");
    }

    private DebtDto createDebtDto(String filePath, int lineNo, String label, String content) {
        return DebtDto.builder()
                .filePath(filePath)
                .lineNo(lineNo)
                .label(label)
                .content(content)
                .build();
    }
}
