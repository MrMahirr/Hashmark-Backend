package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.dto.ScanStartResponse;
import dev.hashmark.scanner.job.ScanJobProcessor;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import org.springframework.stereotype.Service;

@Service
public class ScanService {

    private final RepoRepository repoRepository;
    private final ScanJobProcessor scanJobProcessor;
    private final ScanJobRepository scanJobRepository;

    public ScanService(RepoRepository repoRepository, ScanJobProcessor scanJobProcessor, ScanJobRepository scanJobRepository) {
        this.repoRepository = repoRepository;
        this.scanJobProcessor = scanJobProcessor;
        this.scanJobRepository = scanJobRepository;
    }

    public ScanStartResponse startScan(Long repoId, Long userId) {
        Repo repo = repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found or not owned by user"));

        ScanJob job = ScanJob.builder()
                .repoId(repoId)
                .build();
        ScanJob savedJob = scanJobRepository.save(job);

        scanJobProcessor.processScan(repoId, userId, savedJob.getId());

        return ScanStartResponse.builder()
                .repoId(repo.getId())
                .jobId(savedJob.getId())
                .status("RUNNING")
                .build();
    }

    public ScanJob getScanStatus(Long repoId, Long userId) {
        repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found or not owned by user"));

        return scanJobRepository.findLatestByRepoId(repoId)
                .orElseThrow(() -> ApiException.notFound("No scan job found for this repo"));
    }
}
