package dev.hashmark.debt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** PATCH /debts/{id}: gönderilmeyen (null) alanlar değişmez; assignee "" ise atama kaldırılır. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DebtTriageRequest {
    /** OPEN (yeniden aç), IGNORED, WONT_FIX veya RESOLVED (elle çözüldü) */
    private String status;
    private String assignee;
}
