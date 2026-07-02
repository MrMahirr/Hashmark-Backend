package dev.hashmark.scanner.service;

import dev.hashmark.debt.dto.DebtDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DebtParserService {

    private static final Pattern[] DEBT_PATTERNS = {
            Pattern.compile("//\\s*(TODO|FIXME|HACK|XXX)[:\\s]?(.*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("#\\s*(TODO|FIXME|HACK|XXX)[:\\s]?(.*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\*\\s*(TODO|FIXME|HACK|XXX)[:\\s]?(.*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("--\\s*(TODO|FIXME|HACK|XXX)[:\\s]?(.*)", Pattern.CASE_INSENSITIVE)
    };

    public List<DebtDto> parse(String fileContent, String filePath) {
        List<DebtDto> debts = new ArrayList<>();
        if (fileContent == null || fileContent.isEmpty()) {
            return debts;
        }

        String[] lines = fileContent.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (Pattern pattern : DEBT_PATTERNS) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String label = matcher.group(1).toUpperCase();
                    String content = matcher.group(2).trim();
                    
                    debts.add(DebtDto.builder()
                            .filePath(filePath)
                            .lineNo(i + 1)
                            .label(label)
                            .content(content)
                            .build());
                    break; // match bulununca diger patternlara bakma
                }
            }
        }
        return debts;
    }
}
