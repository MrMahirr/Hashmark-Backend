package dev.hashmark.scanner.service;

import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.debt.repository.DebtRepository.LineRef;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.dto.BlameRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Taramadan sonra, açık borçların yazarını ve ne zaman eklendiğini GitHub blame ile doldurur.
 * En iyi çabadır: sınır/yetki/ağ hatalarında sessizce durur ve sonraki taramada devam eder.
 */
@Service
public class BlameEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(BlameEnrichmentService.class);

    private final GitHubBlameService blameService;
    private final DebtRepository debtRepository;
    private final ScanProperties properties;

    public BlameEnrichmentService(GitHubBlameService blameService, DebtRepository debtRepository,
                                  ScanProperties properties) {
        this.blameService = blameService;
        this.debtRepository = debtRepository;
        this.properties = properties;
    }

    /** @return blame bilgisi doldurulan borç sayısı */
    public int enrich(Long repoId, String repoFullName, String token, String commitSha) {
        int limit = properties.getBlameMaxFiles();
        if (limit <= 0 || commitSha == null) {
            return 0;
        }

        int updated = 0;
        for (String filePath : debtRepository.findFilesNeedingBlame(repoId, limit)) {
            Optional<List<BlameRange>> ranges = blameService.fetchBlame(repoFullName, token, commitSha, filePath);
            if (ranges.isEmpty()) {
                log.info("Blame enrichment stopped for repo {} at {}", repoId, filePath);
                break; // limit/yetki sorunu olabilir; sonraki taramada tekrar denenir
            }
            for (LineRef line : debtRepository.findLinesNeedingBlame(repoId, filePath)) {
                Optional<BlameRange> range = ranges.get().stream().filter(r -> r.contains(line.lineNo())).findFirst();
                if (range.isPresent()) {
                    BlameRange r = range.get();
                    debtRepository.updateBlame(line.id(), r.authorLogin(), r.authorName(), r.authoredAt(), r.commitSha());
                    updated++;
                } else {
                    debtRepository.markBlameAttempted(line.id());
                }
            }
        }
        return updated;
    }
}
