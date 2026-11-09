package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.ConflictException;
import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.InviteService;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.identity.UserStatus;
import com.caderly.caderlyhr.org.Department;
import com.caderly.caderlyhr.org.OrgFacade;
import com.caderly.caderlyhr.security.SessionRevoker;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee CRUD, lifecycle transitions, and view-authorization (PRD §14, §21). {@code @Transactional}
 * write-then-read methods here are what {@code AdminEmployeeController}/{@code ProfileController}
 * call instead of composing repository calls themselves (ADR 0007 — mirrors {@code
 * OrganizationAdminService}).
 */
@Service
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);
    /** {@code employee.first_name} and {@code last_name} are {@code varchar(100)}. */
    private static final int NAME_MAX = 100;

    private final EmployeeRepository employees;
    private final EmployeeStatusHistoryRepository statusHistory;
    private final EmployeeManagerHistoryRepository managerHistory;
    private final EmergencyContactRepository emergencyContacts;
    private final GovernmentIdRepository governmentIds;
    private final BankDetailRepository bankDetails;
    private final EducationRepository educations;
    private final BenefitRepository benefits;
    private final InviteService inviteService;
    private final AppUserRepository appUsers;
    private final OrgFacade orgFacade;
    private final SessionRevoker sessionRevoker;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TenantFacade tenants;

    EmployeeService(
            EmployeeRepository employees,
            EmployeeStatusHistoryRepository statusHistory,
            EmployeeManagerHistoryRepository managerHistory,
            EmergencyContactRepository emergencyContacts,
            GovernmentIdRepository governmentIds,
            BankDetailRepository bankDetails,
            EducationRepository educations,
            BenefitRepository benefits,
            InviteService inviteService,
            AppUserRepository appUsers,
            OrgFacade orgFacade,
            SessionRevoker sessionRevoker,
            Clock clock,
            ApplicationEventPublisher events,
            TenantFacade tenants) {
        this.employees = employees;
        this.statusHistory = statusHistory;
        this.managerHistory = managerHistory;
        this.emergencyContacts = emergencyContacts;
        this.governmentIds = governmentIds;
        this.bankDetails = bankDetails;
        this.educations = educations;
        this.benefits = benefits;
        this.inviteService = inviteService;
        this.appUsers = appUsers;
        this.orgFacade = orgFacade;
        this.sessionRevoker = sessionRevoker;
        this.clock = clock;
        this.events = events;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public List<Employee> list(@Nullable UUID departmentId, @Nullable EmployeeStatus status) {
        if (departmentId != null && status != null) {
            return employees.findAllByDepartmentIdAndStatusOrderByLastNameAscFirstNameAsc(departmentId, status);
        }
        if (departmentId != null) {
            return employees.findAllByDepartmentIdOrderByLastNameAscFirstNameAsc(departmentId);
        }
        if (status != null) {
            return employees.findAllByStatusOrderByLastNameAscFirstNameAsc(status);
        }
        return employees.findAllByOrderByLastNameAscFirstNameAsc();
    }

    @Transactional(readOnly = true)
    public Employee require(UUID id) {
        return employees
                .findById(id)
                .orElseThrow(() -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
    }

    @Transactional(readOnly = true)
    public Optional<Employee> findByUserId(UUID userId) {
        return employees.findByUserId(userId);
    }

    /**
     * Blank/null fields among exactly the set {@link EmployeeForms.SelfProfilePatch} lets an
     * employee edit themselves — the derived "Complete your profile" task on For Action (PRD
     * FR-8.4, ADR 0015). {@code addressLine2} is deliberately excluded: it is
     * legitimately empty for most addresses, so counting it would make the task permanently
     * unclearable. Returns field keys (matching {@code common.field.*} message keys), not prose —
     * the web layer resolves the label.
     */
    @Transactional(readOnly = true)
    public List<String> incompleteSelfServiceFields(UUID employeeId) {
        Employee employee = require(employeeId);
        List<String> missing = new ArrayList<>();
        if (isBlank(employee.phone())) {
            missing.add("phone");
        }
        if (isBlank(employee.addressLine1())) {
            missing.add("address-line1");
        }
        if (isBlank(employee.city())) {
            missing.add("city");
        }
        if (isBlank(employee.country())) {
            missing.add("country");
        }
        if (isBlank(employee.postalCode())) {
            missing.add("postal-code");
        }
        return List.copyOf(missing);
    }

    /**
     * Self-serve workspaces are capped (ADR 0022 decision F). Terminated employees free their seat;
     * invited ones hold it, because an invite is a hire the Admin has already decided on.
     */
    private void requireSeatAvailable() {
        tenants.currentSeatLimit()
                .ifPresent(
                        limit -> {
                            if (employees.countByStatusNot(EmployeeStatus.TERMINATED) >= limit) {
                                throw new ValidationException(
                                        "EMPLOYEE_SEAT_LIMIT_REACHED",
                                        "This workspace has reached its limit of " + limit + " employees");
                            }
                        });
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /**
     * Creates an Employee and gives them a login (PRD §14.2). Write-then-read-back-via-invite in
     * one transaction (ADR 0007).
     *
     * <p>An email with no login in this workspace gets an {@code INVITED} employee and a fresh
     * invite. An email that already has a login no employee uses — a self-serve founder, a
     * Super-Admin-provisioned Admin, a user invited from the Users page — is linked instead, with
     * no second invite: the employee starts {@code ACTIVE} if that login is, otherwise
     * {@code INVITED} until the pending invite is accepted. A disabled login is not revived.
     */
    @Transactional
    public Employee create(EmployeeForms.CreateEmployee form, String appBaseUrl, String tenantName) {
        requireSeatAvailable();
        if (employees.existsByEmailIgnoreCase(form.email())) {
            throw new ConflictException(
                    "EMPLOYEE_EMAIL_TAKEN", "An employee with that email already exists");
        }
        // @TenantId scopes this lookup, so another workspace's login with the same email is
        // never found here and never linked (BR-12).
        Optional<AppUser> login = appUsers.findByEmail(form.email().trim().toLowerCase(Locale.ROOT));
        if (login.isEmpty()) {
            UUID userId = inviteService.invite(form.email(), Set.of(Role.EMPLOYEE), appBaseUrl, tenantName);
            return hire(form, userId, EmployeeStatus.INVITED);
        }
        AppUser existing = login.get();
        if (existing.status() == UserStatus.DISABLED) {
            throw new ConflictException(
                    "USER_EMAIL_TAKEN", "A user with that email already exists in this workspace");
        }
        if (employees.findByUserId(existing.requireId()).isPresent()) {
            throw new ConflictException(
                    "EMPLOYEE_LOGIN_TAKEN", "That login already belongs to another employee");
        }
        EmployeeStatus status =
                existing.status() == UserStatus.ACTIVE ? EmployeeStatus.ACTIVE : EmployeeStatus.INVITED;
        return hire(form, existing.requireId(), status);
    }

    /**
     * A self-serve workspace's first Admin: their login and invite, and an employee record named
     * from the sign-up form, so they land with a profile like anyone they later hire (ADR 0022).
     * Activated on accept by {@link EmployeeInviteAcceptedListener}, as for any invite.
     */
    @Transactional
    public InviteService.IssuedInvite inviteFounder(
            String fullName, String email, String appBaseUrl, String tenantName) {
        InviteService.IssuedInvite invite =
                inviteService.issueInvite(email, Set.of(Role.ADMIN), appBaseUrl, tenantName);
        // The form asks for one name. The last word is taken as the surname, so most names read
        // right on the profile; a one-word name leaves it blank for the founder to fill in. Each
        // part is capped to its column, since sign-up accepts a longer name than either holds.
        String name = fullName.trim().replaceAll("\\s+", " ");
        int lastSpace = name.lastIndexOf(' ');
        String firstName = capName(lastSpace < 0 ? name : name.substring(0, lastSpace));
        String lastName = capName(lastSpace < 0 ? "" : name.substring(lastSpace + 1));
        var form =
                new EmployeeForms.CreateEmployee(
                        firstName, lastName, email, null, null, null, null, null, null, null,
                        LocalDate.now(clock), null, null, null, null, null, null, null, null);
        hire(form, invite.userId(), EmployeeStatus.INVITED);
        return invite;
    }

    private static String capName(String part) {
        return part.length() <= NAME_MAX ? part : part.substring(0, NAME_MAX).strip();
    }

    private Employee hire(EmployeeForms.CreateEmployee form, UUID userId, EmployeeStatus status) {
        LocalDate today = LocalDate.now(clock);

        Employee employee = Employee.create(form.firstName(), form.lastName(), form.email());
        applyAdminFields(employee, form.toAdminPatch());
        employee.changeStatus(status);
        employee.linkUser(userId);
        employees.save(employee);

        statusHistory.save(EmployeeStatusHistory.open(employee, status, form.employmentType(), today));
        // After save(), because the manager transition needs the employee's id (see
        // applyManagerChange). reassignManagerInternal is the only place that writes the
        // manager-history row on create — do not add a second managerHistory.save() here, or
        // two rows end up open on the same day.
        applyManagerChange(employee, form.managerId());

        // Published, not called directly: people must not depend on timeoff (see
        // EmployeeHiredEvent's doc comment). Plain @EventListener, not @TransactionalEventListener
        // AFTER_COMMIT like EmployeeInviteAcceptedListener — the initial balance grant is an
        // internal DB write with no reason to survive this employee record failing to commit, so
        // it belongs in the same transaction, not a REQUIRES_NEW one after it.
        events.publishEvent(new EmployeeHiredEvent(employee.requireId()));

        log.info("Created employee {}", employee.requireId());
        return employee;
    }

    /**
     * Called by {@link EmployeeInviteAcceptedListener} after the invite's AppUser activates.
     *
     * <p>{@code REQUIRES_NEW}, not the default propagation: this runs from an {@code
     * AFTER_COMMIT} transactional event listener, where Spring's transaction-synchronization
     * bookkeeping for the triggering transaction is still bound to the thread (cleanup hasn't run
     * yet) even though it has already physically committed. Default {@code REQUIRED} propagation
     * would join that finishing transaction instead of opening a real new one — {@link
     * com.caderly.caderlyhr.tenant.TenantSessionVariableListener}'s {@code afterBegin} would never
     * fire, RLS's transaction-scoped {@code app.tenant_id} (set via {@code SET LOCAL}, which dies
     * at the original transaction's {@code COMMIT}) would read as unset, and {@code
     * findByUserId} below would silently see zero rows — exactly what happened before this was
     * forced to open its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void activateForUser(UUID userId) {
        employees
                .findByUserId(userId)
                .filter(employee -> employee.status() == EmployeeStatus.INVITED)
                .ifPresent(employee -> transitionStatus(employee, EmployeeStatus.ACTIVE));
    }

    @Transactional
    public Employee updateSelfServiceFields(UUID employeeId, EmployeeForms.SelfProfilePatch patch) {
        Employee employee = require(employeeId);
        employee.updateSelfServiceFields(
                patch.phone(),
                patch.addressLine1(),
                patch.addressLine2(),
                patch.city(),
                patch.country(),
                patch.postalCode());
        return employee;
    }

    @Transactional
    public Employee updateAdminFields(UUID employeeId, EmployeeForms.AdminEmployeePatch patch) {
        Employee employee = require(employeeId);
        if (employees.existsByEmailIgnoreCaseAndIdNot(patch.email(), employeeId)) {
            throw new ConflictException(
                    "EMPLOYEE_EMAIL_TAKEN", "An employee with that email already exists");
        }
        applyAdminFields(employee, patch);
        applyManagerChange(employee, patch.managerId());
        return employee;
    }

    private void applyAdminFields(Employee employee, EmployeeForms.AdminEmployeePatch patch) {
        Department department =
                patch.departmentId() == null ? null : orgFacade.requireActiveDepartment(patch.departmentId());
        employee.updateAdminFields(
                patch.employeeCode(),
                patch.firstName(),
                patch.lastName(),
                patch.email(),
                patch.phone(),
                patch.birthDate(),
                patch.gender(),
                patch.maritalStatus(),
                patch.nationality(),
                patch.citizenship(),
                patch.hireDate(),
                patch.employmentType(),
                department,
                patch.jobTitle(),
                patch.workLocation(),
                patch.workingHoursPerDay() == null ? BigDecimal.valueOf(8.0) : patch.workingHoursPerDay(),
                patch.currency(),
                patch.baseCompensation());
    }

    /**
     * Deliberately not part of {@link #applyAdminFields}: this needs a <em>persisted</em>
     * employee, and that one does not. Both the self-management guard and the open-history
     * lookup below call {@code requireId()}, and {@code @UuidGenerator} does not assign an id
     * until {@code persist()} — so running this before {@code save()} threw "Employee has not
     * been persisted" and made every create-with-a-manager a 500. {@link #create} therefore
     * calls it after {@code save()}; {@link #updateAdminFields} calls it straight after
     * {@link #applyAdminFields} on a record that is already persisted.
     */
    private void applyManagerChange(Employee employee, @Nullable UUID managerId) {
        UUID currentManagerId = employee.manager() == null ? null : employee.manager().requireId();
        if (!java.util.Objects.equals(currentManagerId, managerId)) {
            reassignManagerInternal(employee, managerId);
        }
    }

    @Transactional
    public Employee changeStatus(UUID employeeId, EmployeeStatus status) {
        Employee employee = require(employeeId);
        transitionStatus(employee, status);
        return employee;
    }

    private void transitionStatus(Employee employee, EmployeeStatus status) {
        LocalDate today = LocalDate.now(clock);
        statusHistory
                .findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(employee.requireId())
                .ifPresent(open -> open.close(today));
        statusHistory.save(EmployeeStatusHistory.open(employee, status, employee.employmentType(), today));
        employee.changeStatus(status);
    }

    @Transactional
    public Employee reassignManager(UUID employeeId, @Nullable UUID managerId) {
        Employee employee = require(employeeId);
        reassignManagerInternal(employee, managerId);
        return employee;
    }

    private void reassignManagerInternal(Employee employee, @Nullable UUID managerId) {
        if (managerId != null && managerId.equals(employee.requireId())) {
            throw new ValidationException(
                    "EMPLOYEE_CANNOT_MANAGE_SELF", "An employee cannot be their own manager");
        }
        if (managerId != null && wouldCloseAReportingLoop(employee.requireId(), managerId)) {
            throw new ValidationException(
                    "EMPLOYEE_MANAGER_CYCLE",
                    "That person already reports to this employee, directly or through their team");
        }
        Employee manager = managerId == null ? null : require(managerId);

        // Captured before employee.reassignManager(manager) mutates in-memory state below, and
        // excluding this employee explicitly by id rather than relying on flush timing (ADR 0011).
        Employee oldManager = employee.manager();
        boolean oldManagerRetainsOtherReports =
                oldManager != null
                        && employees.countByManagerIdAndIdNotAndStatusNot(
                                        oldManager.requireId(), employee.requireId(), EmployeeStatus.TERMINATED)
                                > 0;

        LocalDate today = LocalDate.now(clock);
        managerHistory
                .findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(employee.requireId())
                .ifPresent(open -> open.close(today));
        managerHistory.save(EmployeeManagerHistory.open(employee, manager, today));
        employee.reassignManager(manager);

        // Published, not applied directly here, mirroring EmployeeHiredEvent/EmployeeTerminatedEvent
        // above (ADR 0011: keeps MANAGER role sync out of org-chart-reassignment logic).
        events.publishEvent(
                new ManagerRoleSyncEvent(
                        oldManager == null ? null : oldManager.userId(),
                        oldManagerRetainsOtherReports,
                        manager == null ? null : manager.userId()));
    }

    /**
     * Schedules (or immediately applies) termination (PRD §14.4). Past/today applies immediately,
     * including session revocation; future just records the date — {@link EmployeeTerminationJob}
     * applies it when the date arrives.
     */
    @Transactional
    public Employee terminate(UUID employeeId, LocalDate terminationDate) {
        Employee employee = require(employeeId);
        employee.scheduleTermination(terminationDate);
        if (!terminationDate.isAfter(LocalDate.now(clock))) {
            applyTermination(employee);
        }
        return employee;
    }

    /** {@link EmployeeTerminationJob}'s entry point — applies every termination whose date has arrived. */
    @Transactional
    public int applyDueTerminations(LocalDate today) {
        List<Employee> due =
                employees.findAllByTerminationDateLessThanEqualAndStatusNot(today, EmployeeStatus.TERMINATED);
        due.forEach(this::applyTermination);
        return due.size();
    }

    private void applyTermination(Employee employee) {
        transitionStatus(employee, EmployeeStatus.TERMINATED);
        if (employee.userId() != null) {
            // Both halves of "login blocked" (PRD §14.4): disable() stops any *future* login
            // attempt (AppUserDetailsService#isEnabled), revokeAllFor kills sessions already in
            // progress. Neither alone is sufficient — a disabled-but-not-revoked account stays
            // logged in until its session naturally expires; a revoked-but-not-disabled account
            // can just log back in again immediately.
            appUsers.findById(employee.userId()).ifPresent(AppUser::disable);
            sessionRevoker.revokeAllFor(employee.userId());
        }
        // Published, not called directly: people must not depend on timeoff (see
        // EmployeeTerminatedEvent's doc comment, mirrors EmployeeHiredEvent/ADR 0009). Plain
        // @EventListener, not @TransactionalEventListener AFTER_COMMIT — cancelling future leave
        // requests is an internal DB write with no reason to survive this transaction failing to
        // commit, so it belongs in the same transaction as the termination itself.
        LocalDate effectiveDate = java.util.Objects.requireNonNull(employee.terminationDate());
        events.publishEvent(new EmployeeTerminatedEvent(employee.requireId(), effectiveDate));
        log.info("Terminated employee {}", employee.requireId());
    }

    /**
     * Whether pointing {@code employeeId} at {@code proposedManagerId} would close a loop in the
     * reporting line — which it does exactly when the proposed manager already reports up to this
     * employee, so that the employee would end up an ancestor of their own manager.
     *
     * <p>Reads as the mirror image of {@link EmployeeRepository#isManagerOf}, and exists to name
     * that inversion: the arguments are the caller's swapped round, which is easy to get backwards
     * and impossible to notice at the call site.
     *
     * <p>Only reachable from a reassignment. A newly created employee has no reports yet, so the
     * create path cannot close a loop however its manager is chosen.
     */
    private boolean wouldCloseAReportingLoop(UUID employeeId, UUID proposedManagerId) {
        return employees.isManagerOf(employeeId, proposedManagerId);
    }

    /**
     * Self, Admin, or a direct/indirect manager may view a profile (PRD §26); anyone else is
     * denied. {@code AccessDeniedException} here is caught by the same {@code
     * ExceptionTranslationFilter} that handles {@code @PreAuthorize} failures, so it renders the
     * same 403 page (CLAUDE.md §6 A05).
     */
    @Transactional(readOnly = true)
    public Employee getProfileForViewer(UUID targetId, AppUserPrincipal viewer) {
        Employee target = require(targetId);
        if (viewer.roles().contains(Role.ADMIN)) {
            return target;
        }
        if (target.userId() != null && target.userId().equals(viewer.userId())) {
            return target;
        }
        if (viewer.roles().contains(Role.MANAGER)) {
            Optional<Employee> viewerEmployee = employees.findByUserId(viewer.userId());
            if (viewerEmployee.isPresent()
                    && employees.isManagerOf(viewerEmployee.get().requireId(), targetId)) {
                return target;
            }
        }
        throw new AccessDeniedException("Not authorized to view this profile");
    }

    // ---- Emergency contacts (self- or admin-editable; ownership enforced by the caller only
    // ever resolving employeeId to the caller's own record on the self-service path) ----

    @Transactional(readOnly = true)
    public List<EmergencyContact> listEmergencyContacts(UUID employeeId) {
        return emergencyContacts.findAllByEmployeeIdOrderByNameAsc(employeeId);
    }

    @Transactional
    public EmergencyContact addEmergencyContact(
            UUID employeeId,
            String name,
            @Nullable String relationship,
            @Nullable String phone,
            @Nullable String email) {
        Employee employee = require(employeeId);
        return emergencyContacts.save(EmergencyContact.create(employee, name, relationship, phone, email));
    }

    @Transactional
    public void removeEmergencyContact(UUID employeeId, UUID contactId) {
        EmergencyContact contact = requireOwnedBy(emergencyContacts.findById(contactId), employeeId, "EMERGENCY_CONTACT_NOT_FOUND");
        emergencyContacts.delete(contact);
    }

    @Transactional(readOnly = true)
    public List<GovernmentId> listGovernmentIds(UUID employeeId) {
        return governmentIds.findAllByEmployeeIdOrderByIdTypeAsc(employeeId);
    }

    @Transactional
    public GovernmentId addGovernmentId(
            UUID employeeId,
            GovernmentIdType type,
            String idNumber,
            @Nullable String country,
            @Nullable LocalDate issueDate,
            @Nullable LocalDate expiryDate) {
        Employee employee = require(employeeId);
        return governmentIds.save(GovernmentId.create(employee, type, idNumber, country, issueDate, expiryDate));
    }

    @Transactional
    public void removeGovernmentId(UUID employeeId, UUID governmentIdId) {
        GovernmentId governmentId =
                requireOwnedBy(governmentIds.findById(governmentIdId), employeeId, "GOVERNMENT_ID_NOT_FOUND");
        governmentIds.delete(governmentId);
    }

    // ---- Bank detail (one row per employee) ----

    @Transactional(readOnly = true)
    public Optional<BankDetail> findBankDetail(UUID employeeId) {
        return bankDetails.findByEmployeeId(employeeId);
    }

    @Transactional
    public BankDetail upsertBankDetail(
            UUID employeeId,
            @Nullable String bankName,
            @Nullable String accountName,
            @Nullable String accountNumber,
            @Nullable String iban,
            @Nullable String swiftBic) {
        BankDetail bankDetail =
                bankDetails
                        .findByEmployeeId(employeeId)
                        .orElseGet(() -> BankDetail.create(require(employeeId)));
        bankDetail.update(bankName, accountName, accountNumber, iban, swiftBic);
        return bankDetails.save(bankDetail);
    }

    // ---- Education (Admin- or self-editable, same route-scoping rule as emergency contacts) ----

    @Transactional(readOnly = true)
    public List<Education> listEducation(UUID employeeId) {
        return educations.findAllByEmployeeIdOrderByStartYearDesc(employeeId);
    }

    @Transactional
    public Education addEducation(
            UUID employeeId,
            @Nullable String institution,
            @Nullable String degree,
            @Nullable String field,
            @Nullable Integer startYear,
            @Nullable Integer endYear) {
        Employee employee = require(employeeId);
        return educations.save(Education.create(employee, institution, degree, field, startYear, endYear));
    }

    @Transactional
    public void removeEducation(UUID employeeId, UUID educationId) {
        Education education = requireOwnedBy(educations.findById(educationId), employeeId, "EDUCATION_NOT_FOUND");
        educations.delete(education);
    }

    // ---- Benefits (Admin-managed) ----

    @Transactional(readOnly = true)
    public List<Benefit> listBenefits(UUID employeeId) {
        return benefits.findAllByEmployeeIdOrderByStartDateDesc(employeeId);
    }

    @Transactional
    public Benefit addBenefit(
            UUID employeeId,
            @Nullable String name,
            @Nullable String category,
            @Nullable LocalDate startDate,
            @Nullable LocalDate endDate) {
        Employee employee = require(employeeId);
        return benefits.save(Benefit.create(employee, name, category, startDate, endDate));
    }

    /**
     * Guards against IDOR: a sub-entity id that exists but belongs to a different employee reads
     * as not-found, same as a truly missing id — never leaks that the row exists elsewhere.
     */
    private static <T> T requireOwnedBy(
            Optional<T> candidate, UUID employeeId, String errorCode) {
        return candidate
                .filter(entry -> ownerId(entry).equals(employeeId))
                .orElseThrow(() -> new NotFoundException(errorCode, "Not found"));
    }

    private static UUID ownerId(Object entry) {
        return switch (entry) {
            case EmergencyContact contact -> contact.employee().requireId();
            case GovernmentId governmentId -> governmentId.employee().requireId();
            case Education education -> education.employee().requireId();
            default -> throw new IllegalArgumentException("Unsupported sub-entity: " + entry.getClass());
        };
    }
}
