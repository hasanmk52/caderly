package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.timeoff.LeaveRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Self-service leave booking (PRD §12.4 steps 1-3, UI_Guidelines §8.1). {@code @PreAuthorize
 * hasRole('EMPLOYEE')} is the floor — the {@code ADMIN > MANAGER > EMPLOYEE} role hierarchy
 * already passes Manager/Admin through. Every self-service call resolves the caller's own {@link
 * Employee} from the principal, never from request input (same rule {@code ProfileController}
 * follows) — booking always happens on behalf of yourself.
 */
@Controller
@PreAuthorize("hasRole('EMPLOYEE')")
class LeaveRequestController {

    private final LeaveRequestService leaveRequests;
    private final EmployeeService employees;
    private final MessageSource messages;

    LeaveRequestController(LeaveRequestService leaveRequests, EmployeeService employees, MessageSource messages) {
        this.leaveRequests = leaveRequests;
        this.employees = employees;
        this.messages = messages;
    }

    @GetMapping("/leave/new")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String newLeaveForm(
            @RequestParam(required = false) UUID leaveTypeId,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model) {
        // Without an employee record there is nothing to book against; say so inside the modal
        // rather than returning a 404 that htmx never swaps in (an empty, invisible modal).
        if (employees.findByUserId(principal.userId()).isEmpty()) {
            return "leave/book-time-off :: noRecord";
        }
        Employee own = ownEmployee(principal);
        UUID employeeId = own.requireId();
        model.addAttribute("bookableTypes", leaveRequests.bookableTypesForEmployee(employeeId));
        model.addAttribute("leaveForm", blankForm(leaveTypeId));
        model.addAttribute("preview", (Object) null);
        return "leave/book-time-off :: modalBody";
    }

    @PostMapping("/leave/preview")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String preview(
            @Valid @ModelAttribute("leaveForm") LeaveForms.BookLeaveForm form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model) {
        Employee own = ownEmployee(principal);
        model.addAttribute("bookableTypes", leaveRequests.bookableTypesForEmployee(own.requireId()));
        if (!binding.hasErrors()) {
            try {
                model.addAttribute(
                        "preview",
                        leaveRequests.preview(
                                own.requireId(),
                                form.leaveTypeId(),
                                form.startDate(),
                                form.endDate(),
                                form.startHalfDayPm(),
                                form.endHalfDayAm()));
            } catch (CaderlyException exception) {
                model.addAttribute(
                        "previewError", WebMessages.errorDetail(messages, exception, LocaleContextHolder.getLocale()));
            }
        }
        return "leave/book-time-off :: preview";
    }

    @PostMapping("/leave")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String book(
            @Valid @ModelAttribute("leaveForm") LeaveForms.BookLeaveForm form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        UUID employeeId = own.requireId();
        if (!binding.hasErrors()) {
            try {
                leaveRequests.bookAndListTypes(
                        employeeId,
                        form.leaveTypeId(),
                        form.startDate(),
                        form.endDate(),
                        form.startHalfDayPm(),
                        form.endHalfDayAm(),
                        form.note(),
                        RequestTenant.baseUrl());
                toast(response, "toast.leave-request.submitted", "Time off request submitted");
                model.addAttribute("bookableTypes", leaveRequests.bookableTypesForEmployee(employeeId));
                model.addAttribute("leaveForm", blankForm(null));
                model.addAttribute("preview", (Object) null);
                return "leave/book-time-off :: modalBody";
            } catch (CaderlyException exception) {
                binding.rejectValue(
                        "startDate",
                        exception.errorCode(),
                        WebMessages.errorDetail(messages, exception, LocaleContextHolder.getLocale()));
            }
        }
        model.addAttribute("bookableTypes", leaveRequests.bookableTypesForEmployee(employeeId));
        return "leave/book-time-off :: modalBody";
    }

    private Employee ownEmployee(AppUserPrincipal principal) {
        return employees
                .findByUserId(principal.userId())
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "EMPLOYEE_NOT_FOUND", "No employee record is linked to this account"));
    }

    private static LeaveForms.BookLeaveForm blankForm(@Nullable UUID leaveTypeId) {
        LocalDate today = LocalDate.now();
        return new LeaveForms.BookLeaveForm(leaveTypeId, today, today, false, false, null);
    }

    /** Resolves {@code key} through {@code messages.properties} (ADR 0013), then fires the toast. */
    private void toast(HttpServletResponse response, String key, String defaultMessage) {
        String message = messages.getMessage(key, null, defaultMessage, LocaleContextHolder.getLocale());
        response.setHeader("HX-Trigger", WebMessages.toastTrigger(message));
    }
}
