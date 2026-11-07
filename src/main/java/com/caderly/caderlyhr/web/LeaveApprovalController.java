package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.timeoff.LeaveRequest;
import com.caderly.caderlyhr.timeoff.LeaveRequestService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * "For Action" inbox (PRD §6.8, §12.4 steps 3-6, §26; UI_Guidelines §8.6): a Tasks pane, open to
 * every signed-in user, alongside the Time off requests approval pane, which stays Manager/Admin
 * only. Manager approval authority is transitive (direct + indirect reports, via {@code
 * PeopleFacade#isManagerOf}) even though routing/notification at submit time stays direct-manager-
 * only (BR-2 MVP scope).
 *
 * <p>The class-level {@code @PreAuthorize} is {@code isAuthenticated()} (ADR 0015) so a plain
 * Employee can reach their own Tasks pane; every mutation endpoint (approve/reject) keeps its own
 * method-level {@code hasRole('MANAGER')}, which overrides the class level.
 */
@Controller
@PreAuthorize("isAuthenticated()")
class LeaveApprovalController {

    private final LeaveRequestService leaveRequests;
    private final EmployeeService employees;
    private final MessageSource messages;

    LeaveApprovalController(LeaveRequestService leaveRequests, EmployeeService employees, MessageSource messages) {
        this.leaveRequests = leaveRequests;
        this.employees = employees;
        this.messages = messages;
    }

    @GetMapping("/for-action")
    @PreAuthorize("isAuthenticated()")
    String forAction(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute(
                "profileTask", employees.findByUserId(principal.userId()).map(this::buildProfileTask).orElse(null));
        boolean isApprover = principal.roles().contains(Role.MANAGER) || principal.roles().contains(Role.ADMIN);
        // Time off requests stays the default-active pane for an approver (that's what they came
        // here to do); Tasks is only the default for a plain Employee, who has no approvals pane
        // at all — see for-action.html's th:classappend on both panes/pills.
        model.addAttribute("isApprover", isApprover);
        // An approver login with no employee record can't act as a decider (resolveDecider needs
        // one), so the approvals pane shows that state instead of failing the whole page.
        boolean noRecord = isApprover && employees.findByUserId(principal.userId()).isEmpty();
        model.addAttribute("noRecord", noRecord);
        if (isApprover && !noRecord) {
            Decider decider = resolveDecider(principal);
            model.addAttribute(
                    "pending",
                    toRows(leaveRequests.listPendingForApprover(decider.isAdmin(), decider.employeeId())));
            model.addAttribute("completed", toRows(leaveRequests.listCompletedByApprover(principal.userId())));
        } else {
            model.addAttribute("pending", List.<ApprovalRow>of());
            model.addAttribute("completed", List.<ApprovalRow>of());
        }
        return "for-action";
    }

    /**
     * Derived "Complete your profile" task (PRD FR-8.4, ADR 0015) — no {@code
     * Task} table exists; this is computed fresh from the employee's own blank self-service
     * fields every time the page loads, and disappears on its own once they're filled in.
     */
    private @Nullable ProfileTask buildProfileTask(Employee employee) {
        List<String> missing = employees.incompleteSelfServiceFields(employee.requireId());
        if (missing.isEmpty()) {
            return null;
        }
        String summary = missing.stream().map(this::fieldLabel).collect(Collectors.joining(", "));
        return new ProfileTask(summary, employee.requireId());
    }

    /**
     * Maps a missing-field key from {@link EmployeeService#incompleteSelfServiceFields} onto its
     * existing message key rather than minting new ones — {@code phone}/{@code city}/{@code
     * country} already have {@code common.field.*} labels, and {@code address-line1}/{@code
     * postal-code} already have {@code profile.personal.*-label} ones (people/profile.html).
     */
    private String fieldLabel(String key) {
        String messageKey =
                switch (key) {
                    case "phone" -> "common.field.phone";
                    case "address-line1" -> "profile.personal.address-line1-label";
                    case "city" -> "common.field.city";
                    case "country" -> "common.field.country";
                    case "postal-code" -> "profile.personal.postal-code-label";
                    default -> key;
                };
        return messages.getMessage(messageKey, null, key, LocaleContextHolder.getLocale());
    }

    @GetMapping("/for-action/leave-requests/{id}/reject-form")
    @PreAuthorize("hasRole('MANAGER')")
    String rejectForm(@PathVariable UUID id, Model model) {
        model.addAttribute("rejectRequestId", id);
        model.addAttribute("rejectForm", new LeaveForms.RejectForm(null));
        return "leave/for-action :: rejectForm";
    }

    @PostMapping("/for-action/leave-requests/{id}/approve")
    @PreAuthorize("hasRole('MANAGER')")
    String approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Decider decider = resolveDecider(principal);
        List<LeaveRequest> fresh =
                leaveRequests.approveAndListPending(
                        id, principal.userId(), decider.employeeId(), decider.name(), decider.isAdmin(), null);
        toast(response, "toast.leave-request.approved", "Request approved");
        addPendingAndCompleted(model, fresh, principal.userId());
        return "leave/for-action :: pendingListAndCounts";
    }

    @PostMapping("/for-action/leave-requests/{id}/reject")
    @PreAuthorize("hasRole('MANAGER')")
    String reject(
            @PathVariable UUID id,
            @Valid @ModelAttribute("rejectForm") LeaveForms.RejectForm form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Decider decider = resolveDecider(principal);
        if (!binding.hasErrors()) {
            List<LeaveRequest> fresh =
                    leaveRequests.rejectAndListPending(
                            id,
                            principal.userId(),
                            decider.employeeId(),
                            decider.name(),
                            decider.isAdmin(),
                            form.decisionNote());
            toast(response, "toast.leave-request.rejected", "Request rejected");
            addPendingAndCompleted(model, fresh, principal.userId());
            return "leave/for-action :: pendingListAndCounts";
        }
        model.addAttribute("rejectRequestId", id);
        return "leave/for-action :: rejectForm";
    }

    private Decider resolveDecider(AppUserPrincipal principal) {
        boolean isAdmin = principal.roles().contains(Role.ADMIN);
        Employee employee =
                employees
                        .findByUserId(principal.userId())
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "EMPLOYEE_NOT_FOUND", "No employee record is linked to this account"));
        return new Decider(employee.requireId(), employee.fullName(), isAdmin);
    }

    /**
     * {@link LeaveRequest#employeeId()} is a plain id, not a JPA relation ({@code timeoff} never
     * reaches into {@code people}'s entities — CLAUDE.md §4), so the employee's display name has
     * to be resolved here in the web layer rather than read off the entity in the template.
     */
    private List<ApprovalRow> toRows(List<LeaveRequest> requests) {
        return requests.stream().map(this::toRow).toList();
    }

    /**
     * Approve/reject both remove a row from Pending and add one to Completed, so both model
     * attributes are refreshed together here — the response fragment (pendingListAndCounts)
     * OOB-swaps the Completed table and both tab counts alongside the Pending list, instead of
     * leaving them stale until the next full page load.
     */
    private void addPendingAndCompleted(Model model, List<LeaveRequest> freshPending, UUID deciderUserId) {
        model.addAttribute("pending", toRows(freshPending));
        model.addAttribute("completed", toRows(leaveRequests.listCompletedByApprover(deciderUserId)));
    }

    private ApprovalRow toRow(LeaveRequest request) {
        Employee employee = employees.require(request.employeeId());
        return new ApprovalRow(
                request.requireId(),
                employee.fullName(),
                request.leaveType().name(),
                request.leaveType().icon(),
                request.leaveType().color(),
                request.startDate(),
                request.endDate(),
                request.durationDays(),
                request.note(),
                request.submittedAt(),
                request.status().label(),
                request.decisionNote());
    }

    /** Resolves {@code key} through {@code messages.properties} (ADR 0013), then fires the toast. */
    private void toast(HttpServletResponse response, String key, String defaultMessage) {
        String message = messages.getMessage(key, null, defaultMessage, LocaleContextHolder.getLocale());
        response.setHeader("HX-Trigger", WebMessages.toastTrigger(message));
    }

    private record Decider(UUID employeeId, String name, boolean isAdmin) {}

    /** Feeds {@code for-action.html}'s Tasks pane: one derived "Complete your profile" row. */
    record ProfileTask(String missingFieldsSummary, UUID employeeId) {}

    /** Feeds {@code for-action.html} (UI_Guidelines §8.6): a {@link LeaveRequest} plus its requester's name. */
    record ApprovalRow(
            UUID requestId,
            String employeeName,
            String leaveTypeName,
            @Nullable String icon,
            @Nullable String color,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal durationDays,
            @Nullable String note,
            Instant submittedAt,
            String statusLabel,
            @Nullable String decisionNote) {}
}
