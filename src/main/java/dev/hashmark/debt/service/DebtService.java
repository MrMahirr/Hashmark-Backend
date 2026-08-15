package dev.hashmark.debt.service;

import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.dto.DebtFilterRequest;
import dev.hashmark.debt.dto.DebtStatsDto;
import dev.hashmark.debt.dto.PageResponse;
import dev.hashmark.debt.repository.DebtRepository;
import org.springframework.stereotype.Service;

@Service
public class DebtService {

    private final DebtRepository debtRepository;

    public DebtService(DebtRepository debtRepository) {
        this.debtRepository = debtRepository;
    }

    public PageResponse<DebtDto> listDebts(Long userId, DebtFilterRequest filter) {
        return debtRepository.findByFilter(userId, filter);
    }

    public DebtStatsDto getStats(Long userId, Long repoId) {
        return debtRepository.getStats(userId, repoId);
    }
}
