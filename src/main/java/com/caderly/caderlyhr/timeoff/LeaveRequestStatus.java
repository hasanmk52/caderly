package com.caderly.caderlyhr.timeoff;

/**
 * Lifecycle of a {@link LeaveRequest} (PRD §12.4, §21). Legal transitions live in {@link
 * LeaveRequestStateMachine}, not here — matching the {@code EmployeeStatus}/{@code UserStatus}
 * precedent of a plain enum plus a separate transition-rule holder.
 */
public enum LeaveRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED;

    /**
     * Display label for UI status badges. REJECTED reads "Declined" (BRAND §3.2): same stored value,
     * a kinder word to read about a colleague.
     */
    public String label() {
        return switch (this) {
            case PENDING -> "Pending";
            case APPROVED -> "Approved";
            case REJECTED -> "Declined";
            case CANCELLED -> "Cancelled";
        };
    }
}
