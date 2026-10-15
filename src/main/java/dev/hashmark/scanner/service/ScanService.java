package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.dto.ScanStartResponse;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ScanService {

    private final RepoRepository repoRepository;
    private final ScanJobRepository scanJobRepository;

    public ScanService(RepoRepository repoRepository, ScanJobRepository scanJobRepository) {
        this.repoRepository = repoRepository;
        this.scanJobRepository = scanJobRepository;
    }

    /** İşi kalıcı kuyruğa ekler; çalıştırmayı {@code ScanJobDispatcher} üstlenir. */
    public ScanStartResponse startScan(Long repoId, Long userId) {
        Repo repo = repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found or not owned by user"));

        ScanJob job;
        try {
            // Benzersiz kısmi indeks (PENDING/RUNNING) eşzamanlı taramayı atomik olarak engeller
            job = scanJobRepository.enqueue(repoId, userId, repo.getTrackedBranch());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A scan is already queued or running for this repository");
        }

        return ScanStartResponse.builder()
                .repoId(repo.getId())
                .jobId(job.getId())
                .status(job.getStatus())
                .build();
    }

    public ScanJob getScanStatus(Long repoId, Long userId) {
        repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found or not owned by user"));

        return scanJobRepository.findLatestByRepoId(repoId)
                .orElseThrow(() -> ApiException.notFound("No scan job found for this repo"));
    }
}
