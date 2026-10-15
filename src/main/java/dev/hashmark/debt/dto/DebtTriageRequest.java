package dev.hashmark.debt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** PATCH /debts/{id}: gönderilmeyen (null) alanlar değişmez; assignee "" ise atama kaldırılır. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DebtTriageRequest {
    /** OPEN, IGNORED veya WONT_FIX */
    private String status;
    private String assignee;
}
