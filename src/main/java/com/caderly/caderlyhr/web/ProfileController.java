package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.documents.DocumentVisibility;
import com.caderly.caderlyhr.documents.DownloadableFile;
import com.caderly.caderlyhr.documents.EmployeeDocumentService;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeForms.SelfProfilePatch;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.people.GovernmentIdType;
import com.caderly.caderlyhr.timeoff.LeaveRequestService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Self-service profile and cross-employee profile viewing (PRD §14.3, §26). Self-service
 * mutations never take an id from the request — they always resolve the caller's own Employee
 * (see {@link #ownEmployee}) — so field-permission and ownership are enforced by construction,
 * not a runtime "is this really you" check (CLAUDE.md's stated preference, and the same principle
 * {@link com.caderly.caderlyhr.people.EmployeeForms} applies to the Self/Admin DTO split).
 *
 * <p>Viewing another employee's profile (Admin, or a manager viewing a report) goes through
 * {@link EmployeeService#getProfileForViewer}, which throws {@code AccessDeniedException} for
 * anyone else — the same 403 page every other access-control failure renders.
 *
 * <p>{@link #view} renders the full page; every mutation handler re-populates the same tab
 * model via {@link #populateTabModel} and returns only the {@code tabContent} fragment, since
 * their {@code hx-target} is {@code #tab-content} — returning the full "people/profile" template
 * there would swap an entire HTML document into that div (see {@code people/profile.html}'s
 * {@code th:fragment="tabContent"}).
 */
@Controller
@PreAuthorize("isAuthenticated()")
class ProfileController {

    private final EmployeeService employees;
    private final LeaveRequestService leaveRequests;
    private final EmployeeDocumentService employeeDocuments;
    private final MessageSource messages;

    ProfileController(
            EmployeeService employees,
            LeaveRequestService leaveRequests,
            EmployeeDocumentService employeeDocuments,
            MessageSource messages) {
        this.employees = employees;
        this.leaveRequests = leaveRequests;
        this.employeeDocuments = employeeDocuments;
        this.messages = messages;
    }

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    String ownProfile(@AuthenticationPrincipal AppUserPrincipal principal) {
        // A login with no employee record gets a page that says so, not a 404.
        return employees.findByUserId(principal.userId())
                .map(employee -> "redirect:/profile/" + employee.requireId())
                .orElse("people/no-record");
    }

    @GetMapping("/profile/{id}")
    @PreAuthorize("isAuthenticated()")
    String view(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "personal") String tab,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model) {
        populateTabModel(id, tab, principal, model);
        return "people/profile";
    }

    @PatchMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    String updateSelf(
            @Valid @ModelAttribute("selfPatch") SelfProfilePatch form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        if (!binding.hasErrors()) {
            employees.updateSelfServiceFields(own.requireId(), form);
            toast(response, "toast.profile.updated", "Profile updated");
        }
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    @PostMapping("/profile/emergency-contacts")
    @PreAuthorize("isAuthenticated()")
    String addEmergencyContact(
            @Valid @ModelAttribute("emergencyContactForm") EmergencyContactForm form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        if (!binding.hasErrors()) {
            employees.addEmergencyContact(
                    own.requireId(), form.name(), form.relationship(), form.phone(), form.email());
            toast(response, "toast.emergency-contact.added", "Emergency contact added");
        }
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    @DeleteMapping("/profile/emergency-contacts/{contactId}")
    @PreAuthorize("isAuthenticated()")
    String removeEmergencyContact(
            @PathVariable UUID contactId,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        employees.removeEmergencyContact(own.requireId(), contactId);
        toast(response, "toast.emergency-contact.removed", "Emergency contact removed");
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    @PostMapping("/profile/government-ids")
    @PreAuthorize("isAuthenticated()")
    String addGovernmentId(
            @Valid @ModelAttribute("governmentIdForm") GovernmentIdForm form,
            BindingResult binding,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        if (!binding.hasErrors()) {
            employees.addGovernmentId(
                    own.requireId(), form.idType(), form.idNumber(), form.country(), form.issueDate(), form.expiryDate());
            toast(response, "toast.government-id.added", "Government ID added");
        }
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    @DeleteMapping("/profile/government-ids/{governmentIdId}")
    @PreAuthorize("isAuthenticated()")
    String removeGovernmentId(
            @PathVariable UUID governmentIdId,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        employees.removeGovernmentId(own.requireId(), governmentIdId);
        toast(response, "toast.government-id.removed", "Government ID removed");
        populateTabModel(own.requireId(), "personal", principal, model);
        return "people/profile :: tabContent";
    }

    /**
     * Self-cancel only — an Admin cancelling someone
     * else's request is service-layer only, no screen, same precedent as {@code
     * BalanceService.adjustManually}. {@code actingIsAdmin} is always {@code false} here: {@code
     * ownEmployee} already guarantees this call can only act on the caller's own record, and
     * {@code LeaveRequestService.cancel} rejects a request that isn't theirs regardless of role.
     */
    @PostMapping("/profile/leave-requests/{requestId}/cancel")
    @PreAuthorize("isAuthenticated()")
    String cancelOwnLeaveRequest(
            @PathVariable UUID requestId,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        Employee own = ownEmployee(principal);
        leaveRequests.cancelAndListForEmployee(requestId, principal.userId(), own.requireId(), false);
        toast(response, "toast.leave-request.cancelled", "Request cancelled");
        populateTabModel(own.requireId(), "timeoff", principal, model);
        return "people/profile :: tabContent";
    }

    /**
     * {@code employeeId} is trusted from the path here — unlike every self-service mutation above
     * — because this endpoint also serves the Admin-on-behalf path, which by definition targets
     * someone other than the caller. Authorization is a plain equality/role check against the
     * caller's own resolved identity, never the request body: a self-uploader has no {@code
     * visibility} field to bind at all ({@link EmployeeDocumentService#uploadOwnAndList}), and an
     * Admin's choice only reaches {@link EmployeeDocumentService#uploadOnBehalfAndList} once this
     * method has independently confirmed the caller is an Admin (PRD §26 "Upload document on
     * behalf" = Admin only).
     */
    @PostMapping("/profile/{employeeId}/documents")
    @PreAuthorize("isAuthenticated()")
    String uploadDocument(
            @PathVariable UUID employeeId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "visibility", required = false) @Nullable DocumentVisibility visibility,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        boolean isSelf = employees.findByUserId(principal.userId()).map(e -> e.requireId().equals(employeeId)).orElse(false);
        boolean isAdmin = principal.roles().contains(Role.ADMIN);
        if (!isSelf && !isAdmin) {
            throw new AccessDeniedException("Not authorized to upload documents for this employee");
        }
        try {
            if (isSelf) {
                employeeDocuments.uploadOwnAndList(employeeId, file, principal.userId());
            } else {
                employeeDocuments.uploadOnBehalfAndList(
                        employeeId,
                        file,
                        visibility == null ? DocumentVisibility.EMPLOYEE_PRIVATE : visibility,
                        principal.userId());
            }
            toast(response, "toast.document.uploaded", "Document uploaded");
        } catch (CaderlyException exception) {
            model.addAttribute(
                    "documentUploadError", WebMessages.errorDetail(messages, exception, LocaleContextHolder.getLocale()));
        }
        populateTabModel(employeeId, "documents", principal, model);
        return "people/profile :: tabContent";
    }

    @GetMapping("/profile/documents/{docId}/download")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<InputStreamResource> downloadDocument(
            @PathVariable UUID docId, @AuthenticationPrincipal AppUserPrincipal principal) {
        UUID callerEmployeeId = employees.findByUserId(principal.userId()).map(Employee::requireId).orElse(null);
        boolean isAdmin = principal.roles().contains(Role.ADMIN);
        DownloadableFile file = employeeDocuments.download(docId, callerEmployeeId, isAdmin);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.filename()))
                .contentType(MediaType.parseMediaType(file.mime()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    @DeleteMapping("/profile/documents/{docId}")
    @PreAuthorize("isAuthenticated()")
    String deleteDocument(
            @PathVariable UUID docId,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        UUID callerEmployeeId = employees.findByUserId(principal.userId()).map(Employee::requireId).orElse(null);
        boolean isAdmin = principal.roles().contains(Role.ADMIN);
        EmployeeDocumentService.DeletionResult result = employeeDocuments.deleteAndList(docId, callerEmployeeId, isAdmin);
        toast(response, "toast.document.deleted", "Document deleted");
        populateTabModel(result.employeeId(), "documents", principal, model);
        return "people/profile :: tabContent";
    }

    private void populateTabModel(UUID id, String tab, AppUserPrincipal principal, Model model) {
        Employee employee = employees.getProfileForViewer(id, principal);
        boolean isSelf = employee.userId() != null && employee.userId().equals(principal.userId());
        boolean isAdmin = principal.roles().contains(Role.ADMIN);
        model.addAttribute("employee", employee);
        model.addAttribute("isSelf", isSelf);
        model.addAttribute("activeNav", isSelf ? "my-profile" : "people");
        model.addAttribute("tenureYears", tenureYears(employee));
        model.addAttribute("activeTab", tab);
        model.addAttribute("visibleTabs", visibleTabs(isSelf, isAdmin));
        populateTab(tab, employee, isSelf, isAdmin, model);
        if (!model.containsAttribute("selfPatch")) {
            model.addAttribute(
                    "selfPatch",
                    new SelfProfilePatch(
                            employee.phone(),
                            employee.addressLine1(),
                            employee.addressLine2(),
                            employee.city(),
                            employee.country(),
                            employee.postalCode()));
        }
    }

    /**
     * PRD FR-3.9 scopes documents to the owning employee and Admin only — a Manager viewing a
     * report's profile sees every other tab but not this one. {@code canViewDocuments} guards
     * both what data this method loads and, via {@link #visibleTabs}, whether the tab even
     * appears in the nav; the actual access control lives in {@code EmployeeDocumentService}
     * regardless of what this page renders (a Manager hitting the download/delete URL directly is
     * denied there, independent of this check).
     */
    private void populateTab(String tab, Employee employee, boolean isSelf, boolean isAdmin, Model model) {
        UUID employeeId = employee.requireId();
        switch (tab) {
            case "education" -> model.addAttribute("education", employees.listEducation(employeeId));
            case "job" -> model.addAttribute("benefits", employees.listBenefits(employeeId));
            case "timeoff" -> {
                model.addAttribute("bookableTypes", leaveRequests.bookableTypesForEmployee(employeeId));
                model.addAttribute("leaveRequestHistory", leaveRequests.listForEmployee(employeeId));
            }
            case "documents" -> {
                boolean canViewDocuments = isSelf || isAdmin;
                model.addAttribute(
                        "employeeDocuments",
                        canViewDocuments ? employeeDocuments.listVisibleTo(employeeId, isAdmin) : List.of());
                model.addAttribute("canUploadDocuments", canViewDocuments);
                model.addAttribute("canChooseDocumentVisibility", isAdmin && !isSelf);
                model.addAttribute("documentVisibilityOptions", DocumentVisibility.values());
            }
            default -> {
                model.addAttribute("emergencyContacts", employees.listEmergencyContacts(employeeId));
                model.addAttribute("governmentIds", employees.listGovernmentIds(employeeId));
                model.addAttribute("governmentIdTypeOptions", GovernmentIdType.values());
                model.addAttribute("bankDetail", employees.findBankDetail(employeeId).orElse(null));
                if (!model.containsAttribute("emergencyContactForm")) {
                    model.addAttribute("emergencyContactForm", new EmergencyContactForm("", null, null, null));
                }
                if (!model.containsAttribute("governmentIdForm")) {
                    model.addAttribute(
                            "governmentIdForm", new GovernmentIdForm(GovernmentIdType.PASSPORT, "", null, null, null));
                }
            }
        }
    }

    /** Every self-service mutation resolves the caller's own record this way — never from a path id. */
    private Employee ownEmployee(AppUserPrincipal principal) {
        return employees
                .findByUserId(principal.userId())
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "EMPLOYEE_NOT_FOUND",
                                        "No employee record is linked to this account"));
    }

    private static @Nullable Integer tenureYears(Employee employee) {
        LocalDate hireDate = employee.hireDate();
        return hireDate == null ? null : Period.between(hireDate, LocalDate.now()).getYears();
    }

    /** Documents is omitted for anyone but the owning employee or an Admin (PRD FR-3.9). */
    private static List<String> visibleTabs(boolean isSelf, boolean isAdmin) {
        List<String> tabs = new ArrayList<>(List.of("personal", "education", "job"));
        if (isSelf || isAdmin) {
            tabs.add("documents");
        }
        tabs.add("timeoff");
        return tabs;
    }

    /**
     * RFC 5987 {@code filename*=} form, percent-encoded: the stored name is user-supplied and
     * must never be interpolated into a header raw (CLAUDE.md §6 A03), matching {@code
     * FilesController}'s identical helper.
     */
    private static String contentDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }

    /** Resolves {@code key} through {@code messages.properties} (ADR 0013), then fires the toast. */
    private void toast(HttpServletResponse response, String key, String defaultMessage) {
        String message = messages.getMessage(key, null, defaultMessage, LocaleContextHolder.getLocale());
        response.setHeader("HX-Trigger", WebMessages.toastTrigger(message));
    }

    record EmergencyContactForm(
            @NotBlank(message = "{validation.emergency-contact-form.name.required}")
                    @jakarta.validation.constraints.Size(
                            max = 150,
                            message = "{validation.emergency-contact-form.name.too-long}")
                    String name,
            @Nullable String relationship,
            @Nullable String phone,
            @Nullable String email) {}

    record GovernmentIdForm(
            GovernmentIdType idType,
            @NotBlank(message = "{validation.government-id-form.id-number.required}") String idNumber,
            @Nullable String country,
            @Nullable LocalDate issueDate,
            @Nullable LocalDate expiryDate) {}
}
