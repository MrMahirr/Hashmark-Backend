package dev.hashmark.scanner.job;

import dev.hashmark.debt.dto.ReplaceResult;
import dev.hashmark.debt.model.Debt;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.repository.ScanJobRepository;
import dev.hashmark.scanner.repository.ScanSnapshotRepository;
import dev.hashmark.scanner.service.GitHubFileService.ScanResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Tarama sonucunu tek bir transaction'da kalıcılaştırır: iş kapatma + borç çözme/ekleme + repo
 * damgaları + snapshot. Ya hepsi yazılır ya hiçbiri; yazım ortasında çöken iş RUNNING kalır ve
 * kalp atışı kurtarmasıyla yeniden denenir.
 *
 * <p>İdempotent: iş kapatma yalnızca RUNNING işte başarılı olur. İş başka bir çalıştırmayla zaten
 * kapatılmışsa (ya da yeniden kuyruğa alınmışsa) hiçbir şey yazılmaz ve boş döner.
 */
@Component
public class ScanResultWriter {

    private final TransactionTemplate tx;
    private final ScanJobRepository scanJobRepository;
    private final DebtRepository debtRepository;
    private final RepoRepository repoRepository;
    private final ScanSnapshotRepository scanSnapshotRepository;

    public ScanResultWriter(PlatformTransactionManager transactionManager, ScanJobRepository scanJobRepository,
                            DebtRepository debtRepository, RepoRepository repoRepository,
                            ScanSnapshotRepository scanSnapshotRepository) {
        this.tx = new TransactionTemplate(transactionManager);
        this.scanJobRepository = scanJobRepository;
        this.debtRepository = debtRepository;
        this.repoRepository = repoRepository;
        this.scanSnapshotRepository = scanSnapshotRepository;
    }

    /**
     * @param message iş kaydına yazılacak uyarı metni (kısmi tarama vb.); yoksa null
     * @return uygulanan sonuç; iş artık RUNNING değilse boş (hiçbir şey yazılmadı)
     */
    public Optional<ReplaceResult> write(Long jobId, Long repoId, List<Debt> debts, ScanResult result,
                                         String message) {
        return Optional.ofNullable(tx.execute(status -> {
            // Önce işi sahiplen: başarısızsa bu çalıştırma geç kalmıştır, borçlara dokunma.
            boolean closed = scanJobRepository.complete(jobId, "DONE", debts.size(), result.scanned(),
                    result.skipped(), result.failed(), result.commitSha(), message);
            if (!closed) {
                return null;
            }

            // Kısmi taramada yalnızca gerçekten taranan dosyaların borçları çözülür;
            // okunamayan veya çok büyük dosyaların borçları ise açık bırakılır.
            ReplaceResult replaced = debtRepository.replaceForRepo(
                    repoId, debts, result.commitSha(),
                    result.truncated() ? result.scannedPaths() : null,
                    result.keepOpenPaths());

            repoRepository.updateLastScannedAt(repoId, LocalDateTime.now());
            if (result.commitSha() != null) {
                repoRepository.updateLastScannedSha(repoId, result.commitSha());
            }
            scanSnapshotRepository.save(repoId, jobId, result.commitSha(), replaced.openTotal(),
                    replaced.openByLabel(), replaced.added(), replaced.resolved());
            return replaced;
        }));
    }
}
