package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.dto.ScanStartResponse;
import dev.hashmark.scanner.job.ScanJobProcessor;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ScanService {

    private final RepoRepository repoRepository;
    private final ScanJobProcessor scanJobProcessor;
    private final ScanJobRepository scanJobRepository;
    private final ScanProperties scanProperties;

    public ScanService(RepoRepository repoRepository, ScanJobProcessor scanJobProcessor,
                       ScanJobRepository scanJobRepository, ScanProperties scanProperties) {
        this.repoRepository = repoRepository;
        this.scanJobProcessor = scanJobProcessor;
        this.scanJobRepository = scanJobRepository;
        this.scanProperties = scanProperties;
    }

    public ScanStartResponse startScan(Long repoId, Long userId) {
        Repo repo = repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found or not owned by user"));

        // Takılı kalmış eski işler yeni taramayı sonsuza dek engellemesin
        scanJobRepository.failStaleRunningJobs(repoId, scanProperties.getStaleJobMinutes());

        ScanJob savedJob;
        try {
            // (repo_id) WHERE status = 'RUNNING' benzersiz indeksi eşzamanlı taramayı atomik olarak engeller
            savedJob = scanJobRepository.save(ScanJob.builder().repoId(repoId).build());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A scan is already running for this repository");
        }

        try {
            scanJobProcessor.processScan(repoId, userId, savedJob.getId());
        } catch (TaskRejectedException e) {
            scanJobRepository.complete(savedJob.getId(), "FAILED", 0, 0, 0, 0, "Scan queue is full");
            throw ApiException.serviceUnavailable("Scan queue is full, please try again shortly");
        }

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
