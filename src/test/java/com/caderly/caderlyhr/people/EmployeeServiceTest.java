package com.caderly.caderlyhr.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.caderly.caderlyhr.common.ConflictException;
import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserDetailsService;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.InviteService;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.org.Division;
import com.caderly.caderlyhr.org.DivisionRepository;
import com.caderly.caderlyhr.org.Department;
import com.caderly.caderlyhr.org.DepartmentRepository;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.net.URLDecoder;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;

/**
 * Employee CRUD, field-permission boundaries, status/manager history, termination, and
 * view-authorization (PRD §14, §26). Extends {@link TenantIsolationTestBase} directly against
 * Testcontainers Postgres, matching {@code OrganizationServiceTest}/{@code
 * InviteAndResetServiceTest}'s convention.
 */
@Import(MutableClockConfiguration.class)
class EmployeeServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeRepository employees;
    @Autowired private EmployeeStatusHistoryRepository statusHistory;
    @Autowired private EmployeeManagerHistoryRepository managerHistory;
    @Autowired private PeopleFacade peopleFacade;
    @Autowired private DivisionRepository divisionRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private AppUserRepository appUsers;
    @Autowired private AppUserDetailsService userDetailsService;
    @Autowired private MutableClock clock;
    @Autowired private InviteService inviteService;
    @Autowired private EmailOutboxRepository emailOutbox;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_%\\-]+)");

    @Test
    void create_startsInvitedWithOpenStatusHistoryAndLinksAnAppUser() {
        Employee employee = asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", uniqueEmail()), BASE_URL, TENANT_NAME));

        assertThat(employee.status()).isEqualTo(EmployeeStatus.INVITED);
        assertThat(employee.userId()).isNotNull();

        AppUser linkedUser = asTenant(tenantA, () -> appUsers.findById(employee.userId())).orElseThrow();
        assertThat(linkedUser.roles()).containsExactly(Role.EMPLOYEE);

        var openHistory =
                asTenant(
                        tenantA,
                        () ->
                                statusHistory.findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(
                                        employee.requireId()));
        assertThat(openHistory).isPresent();
        assertThat(openHistory.orElseThrow().status()).isEqualTo(EmployeeStatus.INVITED);
    }

    /**
     * Regression test for a real bug: goes through {@link InviteService#acceptInvite}, not {@link
     * EmployeeService#activateForUser} directly. {@code EmployeeInviteAcceptedListener} runs
     * {@code activateForUser} from an {@code AFTER_COMMIT} transactional event listener, where the
     * default {@code @Transactional} propagation joined the already-physically-committed
     * triggering transaction instead of opening a new one — {@code TenantSessionVariableListener}
     * never re-armed RLS's {@code app.tenant_id} (transaction-scoped via {@code SET LOCAL}), so the
     * lookup inside {@code activateForUser} silently found nothing and the Employee stayed
     * INVITED forever, even though the linked AppUser correctly went ACTIVE. Fixed with {@code
     * Propagation.REQUIRES_NEW}. A test calling {@code activateForUser} directly (as every other
     * test in this class effectively did, via the create/require path) cannot catch this class of
     * bug — it has to run the real event.
     */
    @Test
    void acceptInvite_transitionsTheLinkedEmployeeFromInvitedToActive() {
        String email = uniqueEmail();
        Employee employee = asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", email), BASE_URL, TENANT_NAME));
        assertThat(employee.status()).isEqualTo(EmployeeStatus.INVITED);
        String rawToken = tokenFromLastEmailTo(email);

        asTenant(tenantA, () -> inviteService.acceptInvite(rawToken, "Str0ngPassphrase"));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.status()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    /** email_outbox is system-scoped, so reading it needs system mode rather than a tenant. */
    private EmailOutbox lastEmailTo(String email) {
        List<EmailOutbox> found =
                TenantContext.runAsSystem(
                        "test: read outbox",
                        () -> emailOutbox.findAll().stream().filter(row -> row.toEmail().equals(email)).toList());
        assertThat(found).as("queued emails to %s", email).isNotEmpty();
        return found.getLast();
    }

    /** Pulls the raw token back out of the delivered link, the way a real recipient would. */
    private String tokenFromLastEmailTo(String email) {
        Matcher matcher = TOKEN_IN_LINK.matcher(lastEmailTo(email).bodyHtml());
        assertThat(matcher.find()).as("token in email body").isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    @Test
    void create_withDuplicateEmailInSameTenant_throwsConflict() {
        String email = uniqueEmail();
        asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", email), BASE_URL, TENANT_NAME));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () -> employeeService.create(form("Other", "Person", email), BASE_URL, TENANT_NAME)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void activateForUser_flipsInvitedEmployeeToActive() {
        Employee employee =
                asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", uniqueEmail()), BASE_URL, TENANT_NAME));

        asTenant(tenantA, () -> employeeService.activateForUser(employee.userId()));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.status()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void create_whenTheEmailHasAnActiveLoginWithNoEmployee_linksItAsActiveAndSendsNoInvite() {
        String email = uniqueEmail();
        AppUser login = asTenant(tenantA, () -> appUsers.save(AppUser.active(email, "{noop}unused")));

        Employee employee =
                asTenant(tenantA, () -> employeeService.create(form("Aisha", "Rahman", email.toUpperCase()), BASE_URL, TENANT_NAME));

        assertThat(employee.userId()).isEqualTo(login.requireId());
        assertThat(employee.status()).isEqualTo(EmployeeStatus.ACTIVE);
        var openHistory =
                asTenant(
                        tenantA,
                        () ->
                                statusHistory.findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(
                                        employee.requireId()));
        assertThat(openHistory.orElseThrow().status()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(emailsTo(email)).isEmpty();
    }

    @Test
    void create_whenTheEmailHasAnInvitedLogin_linksItAndActivatesWhenTheInviteIsAccepted() {
        String email = uniqueEmail();
        UUID loginId = asTenant(tenantA, () -> inviteService.invite(email, java.util.Set.of(Role.MANAGER), BASE_URL, TENANT_NAME));

        Employee employee = asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", email), BASE_URL, TENANT_NAME));

        assertThat(employee.userId()).isEqualTo(loginId);
        assertThat(employee.status()).isEqualTo(EmployeeStatus.INVITED);
        assertThat(emailsTo(email)).as("only the original invite").hasSize(1);

        asTenant(tenantA, () -> inviteService.acceptInvite(tokenFromLastEmailTo(email), "Str0ngPassphrase"));

        assertThat(asTenant(tenantA, () -> employeeService.require(employee.requireId())).status())
                .isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void create_whenTheLoginAlreadyBelongsToAnotherEmployee_throwsLoginTaken() {
        String email = uniqueEmail();
        Employee first = asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", email), BASE_URL, TENANT_NAME));
        // The first employee's contact email moves on, but their login keeps the original address.
        asTenant(
                tenantA,
                () ->
                        employeeService.updateAdminFields(
                                first.requireId(),
                                new EmployeeForms.AdminEmployeePatch(
                                        null, "Jane", "Doe", uniqueEmail(), null, null, null, null, null, null, null,
                                        null, null, null, null, null, BigDecimal.valueOf(8), null, null)));

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.create(form("Other", "Person", email), BASE_URL, TENANT_NAME)))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo("EMPLOYEE_LOGIN_TAKEN");
    }

    @Test
    void create_whenTheLoginIsDisabled_throwsUserEmailTaken() {
        String email = uniqueEmail();
        asTenant(
                tenantA,
                () -> {
                    AppUser login = AppUser.active(email, "{noop}unused");
                    login.disable();
                    return appUsers.save(login);
                });

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", email), BASE_URL, TENANT_NAME)))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo("USER_EMAIL_TAKEN");
    }

    @Test
    void create_whenOnlyAnotherTenantHasThatLogin_invitesAFreshLoginInThisTenant() {
        String email = uniqueEmail();
        AppUser otherTenantsLogin = asTenant(tenantB, () -> appUsers.save(AppUser.active(email, "{noop}unused")));

        Employee employee = asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", email), BASE_URL, TENANT_NAME));

        assertThat(employee.userId()).isNotEqualTo(otherTenantsLogin.requireId());
        assertThat(employee.status()).isEqualTo(EmployeeStatus.INVITED);
        assertThat(emailsTo(email)).hasSize(1);
        assertThat(asTenant(tenantB, () -> employees.findByUserId(otherTenantsLogin.requireId()))).isEmpty();
    }

    @Test
    void inviteFounder_createsAnInvitedAdminLinkedToAnEmployeeNamedFromTheSignup() {
        String email = uniqueEmail();

        InviteService.IssuedInvite invite =
                asTenant(tenantA, () -> employeeService.inviteFounder("Aisha  Al Rahman ", email, BASE_URL, TENANT_NAME));

        Employee founder = asTenant(tenantA, () -> employees.findByUserId(invite.userId())).orElseThrow();
        assertThat(founder.firstName()).isEqualTo("Aisha Al");
        assertThat(founder.lastName()).isEqualTo("Rahman");
        assertThat(founder.email()).isEqualTo(email);
        assertThat(founder.status()).isEqualTo(EmployeeStatus.INVITED);
        assertThat(founder.hireDate()).isEqualTo(LocalDate.now(clock));
        assertThat(asTenant(tenantA, () -> appUsers.findById(invite.userId())).orElseThrow().roles())
                .containsExactly(Role.ADMIN);
    }

    @Test
    void inviteFounder_withAOneWordName_leavesTheLastNameEmpty() {
        InviteService.IssuedInvite invite =
                asTenant(tenantA, () -> employeeService.inviteFounder("Aisha", uniqueEmail(), BASE_URL, TENANT_NAME));

        Employee founder = asTenant(tenantA, () -> employees.findByUserId(invite.userId())).orElseThrow();
        assertThat(founder.firstName()).isEqualTo("Aisha");
        assertThat(founder.lastName()).isEmpty();
    }

    @Test
    void inviteFounder_withANameLongerThanTheColumns_capsEachPart() {
        String longWord = "a".repeat(150);

        InviteService.IssuedInvite invite =
                asTenant(tenantA, () -> employeeService.inviteFounder(longWord + " " + longWord, uniqueEmail(), BASE_URL, TENANT_NAME));

        Employee founder = asTenant(tenantA, () -> employees.findByUserId(invite.userId())).orElseThrow();
        assertThat(founder.firstName()).hasSize(100);
        assertThat(founder.lastName()).hasSize(100);
    }

    private List<EmailOutbox> emailsTo(String email) {
        return TenantContext.runAsSystem(
                "test: read outbox",
                () -> emailOutbox.findAll().stream().filter(row -> row.toEmail().equals(email)).toList());
    }

    @Test
    void updateSelfServiceFields_onlyTouchesContactAndAddressFields() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        asTenant(
                tenantA,
                () ->
                        employeeService.updateSelfServiceFields(
                                employee.requireId(),
                                new EmployeeForms.SelfProfilePatch("+1000", "1 Main St", null, "Dubai", "UAE", "00000")));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.phone()).isEqualTo("+1000");
        assertThat(reloaded.city()).isEqualTo("Dubai");
        // Job/compensation fields are untouched — SelfProfilePatch has no such fields at all
        // (enforced by construction, not a runtime filter).
        assertThat(reloaded.jobTitle()).isNull();
    }

    @Test
    void incompleteSelfServiceFields_whenAllBlank_returnsEveryTrackedField() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        List<String> missing = asTenant(tenantA, () -> employeeService.incompleteSelfServiceFields(employee.requireId()));

        assertThat(missing).containsExactlyInAnyOrder("phone", "address-line1", "city", "country", "postal-code");
    }

    @Test
    void incompleteSelfServiceFields_whenPartiallyFilled_returnsOnlyTheBlankOnes() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        asTenant(
                tenantA,
                () ->
                        employeeService.updateSelfServiceFields(
                                employee.requireId(),
                                new EmployeeForms.SelfProfilePatch("+1000", null, null, "Dubai", null, null)));

        List<String> missing = asTenant(tenantA, () -> employeeService.incompleteSelfServiceFields(employee.requireId()));

        assertThat(missing).containsExactlyInAnyOrder("address-line1", "country", "postal-code");
    }

    /**
     * Fully filled means empty even with {@code addressLine2} left null: it is deliberately not
     * tracked — legitimately empty for most addresses, so counting it would make the task
     * permanently unclearable (ADR 0015).
     */
    @Test
    void incompleteSelfServiceFields_whenFullyFilledExceptAddressLine2_returnsEmpty() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        asTenant(
                tenantA,
                () ->
                        employeeService.updateSelfServiceFields(
                                employee.requireId(),
                                new EmployeeForms.SelfProfilePatch("+1000", "1 Main St", null, "Dubai", "UAE", "00000")));

        List<String> missing = asTenant(tenantA, () -> employeeService.incompleteSelfServiceFields(employee.requireId()));

        assertThat(missing).isEmpty();
    }

    @Test
    void updateAdminFields_setsJobAndEncryptedCompensation() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        UUID departmentId = seedDepartment();

        asTenant(
                tenantA,
                () ->
                        employeeService.updateAdminFields(
                                employee.requireId(),
                                new EmployeeForms.AdminEmployeePatch(
                                        "EMP-1",
                                        employee.firstName(),
                                        employee.lastName(),
                                        employee.email(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        EmploymentType.FULL_TIME,
                                        departmentId,
                                        null,
                                        "Engineer",
                                        "Dubai HQ",
                                        BigDecimal.valueOf(8),
                                        "AED",
                                        "12000.00")));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.jobTitle()).isEqualTo("Engineer");
        assertThat(reloaded.department()).isNotNull();
        // The encrypted value decrypts correctly through the entity — the raw-column tamper-proof
        // property itself is covered by CryptoConverterTest.
        assertThat(reloaded.baseCompensation()).isEqualTo("12000.00");
    }

    @Test
    void reassignManager_opensNewHistoryRowAndClosesThePrevious() {
        Employee manager1 = createActiveEmployee("Manager", "One");
        Employee manager2 = createActiveEmployee("Manager", "Two");
        Employee report = createActiveEmployee("Report", "Ee");

        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager1.requireId()));
        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager2.requireId()));

        var history =
                asTenant(
                        tenantA,
                        () -> managerHistory.findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(report.requireId()));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).effectiveTo()).isNull(); // most recent row still open
        assertThat(history.get(1).effectiveTo()).isNotNull(); // superseded row closed

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(report.requireId()));
        assertThat(reloaded.manager()).isNotNull();
    }

    @Test
    void reassignManager_grantsManagerRoleToTheNewManager() {
        Employee manager = createActiveEmployee("New", "Manager");
        Employee report = createActiveEmployee("A", "Report");

        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager.requireId()));

        AppUser managerUser = asTenant(tenantA, () -> appUsers.findById(manager.userId())).orElseThrow();
        assertThat(managerUser.roles()).contains(Role.MANAGER);
    }

    @Test
    void reassignManager_awayFromAManagerWithNoOtherReports_revokesManagerRole() {
        Employee manager = createActiveEmployee("Old", "Manager");
        Employee report = createActiveEmployee("A", "Report");
        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager.requireId()));
        Employee newManager = createActiveEmployee("New", "Manager");

        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), newManager.requireId()));

        AppUser oldManagerUser = asTenant(tenantA, () -> appUsers.findById(manager.userId())).orElseThrow();
        assertThat(oldManagerUser.roles()).doesNotContain(Role.MANAGER);
    }

    @Test
    void reassignManager_awayFromAManagerWhoStillHasOtherReports_keepsManagerRole() {
        Employee manager = createActiveEmployee("Old", "Manager");
        Employee reportOne = createActiveEmployee("First", "Report");
        Employee reportTwo = createActiveEmployee("Second", "Report");
        asTenant(tenantA, () -> employeeService.reassignManager(reportOne.requireId(), manager.requireId()));
        asTenant(tenantA, () -> employeeService.reassignManager(reportTwo.requireId(), manager.requireId()));
        Employee newManager = createActiveEmployee("New", "Manager");

        asTenant(tenantA, () -> employeeService.reassignManager(reportOne.requireId(), newManager.requireId()));

        AppUser managerUser = asTenant(tenantA, () -> appUsers.findById(manager.userId())).orElseThrow();
        assertThat(managerUser.roles()).contains(Role.MANAGER); // still manages reportTwo
    }

    @Test
    void reassignManager_toSelf_throwsValidation() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.reassignManager(employee.requireId(), employee.requireId())))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void reassignManager_toAManagerWhoAlreadyReportsToTheEmployee_throwsValidation() {
        Employee boss = createActiveEmployee("Boss", "Person");
        Employee report = createActiveEmployee("Report", "Person");
        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), boss.requireId()));

        // Closing the loop the other way round: boss would report to their own report.
        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.reassignManager(boss.requireId(), report.requireId())))
                .isInstanceOf(ValidationException.class);
    }

    /** The same rule two levels up: the loop does not have to be direct to be a loop. */
    @Test
    void reassignManager_toAManagerWhoReportsIndirectlyToTheEmployee_throwsValidation() {
        Employee top = createActiveEmployee("Top", "Person");
        Employee middle = createActiveEmployee("Middle", "Person");
        Employee bottom = createActiveEmployee("Bottom", "Person");
        asTenant(tenantA, () -> employeeService.reassignManager(middle.requireId(), top.requireId()));
        asTenant(tenantA, () -> employeeService.reassignManager(bottom.requireId(), middle.requireId()));

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.reassignManager(top.requireId(), bottom.requireId())))
                .isInstanceOf(ValidationException.class);
    }

    /**
     * The other half of the cycle fix, and the reason the guard alone is not enough: the guard
     * cannot repair a loop written before it existed, and {@code isManagerOf} is itself the query
     * that walks the chain.
     *
     * <p>The failing case is specifically a <em>false</em> answer. {@code EXISTS} short-circuits
     * the moment it finds a match, so a question whose answer is "yes" returns even from a cyclic
     * chain; a question whose answer is "no" has to exhaust the recursion, which under {@code
     * UNION ALL} never ends. That is the access-<em>denied</em> path — a manager viewing someone
     * who is not their report — so the hang landed on the branch that protects data, not the one
     * that serves it.
     *
     * <p>Wrapped in a preemptive timeout because the pre-fix failure mode is an unbounded query,
     * not a wrong answer: without it a regression here would hang the build instead of failing it.
     */
    @Test
    void isManagerOf_whenTheChainAlreadyContainsACycle_terminatesAndReturnsFalse() {
        Employee a = createActiveEmployee("Cycle", "Aye");
        Employee b = createActiveEmployee("Cycle", "Bee");
        Employee outsider = createActiveEmployee("Outside", "Person");
        asTenant(tenantA, () -> employeeService.reassignManager(a.requireId(), b.requireId()));

        // Deliberately bypasses reassignManager's new guard, writing the entity field directly:
        // this reproduces a loop that predates the guard, which is exactly what the guard cannot
        // help with.
        asTenant(
                tenantA,
                () ->
                        transactions.execute(
                                status -> {
                                    Employee managed = employees.findById(b.requireId()).orElseThrow();
                                    managed.reassignManager(employees.findById(a.requireId()).orElseThrow());
                                    return null;
                                }));

        boolean reachable =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(20),
                        () -> asTenant(tenantA, () -> employees.isManagerOf(outsider.requireId(), a.requireId())));
        assertThat(reachable).isFalse();
    }

    /**
     * Regression test for two defects that shared six lines of {@code create}. The manager was
     * assigned by {@code applyAdminFields} <em>before</em> {@code employees.save(employee)}, so
     * {@code reassignManagerInternal}'s {@code employee.requireId()} blew up on an entity whose
     * {@code @UuidGenerator} id is not assigned until {@code persist()} — every attempt to create
     * an employee with a manager was a 500. Behind that, {@code create} then wrote a *second*
     * manager-history row for the manager {@code reassignManagerInternal} had already recorded.
     *
     * <p>Both are asserted here on purpose: fixing only the ordering turns a loud failure into a
     * silent duplicate, which is strictly worse. No test created an employee with a manager
     * before this one — every other manager test reassigns on an already-persisted record, which
     * is exactly the shape that cannot catch either bug.
     */
    @Test
    void create_withManager_linksManagerAndOpensExactlyOneHistoryRow() {
        Employee manager = createActiveEmployee("Manager", "One");

        Employee created =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        formWithManager("Report", "Ee", uniqueEmail(), manager.requireId()),
                                        BASE_URL,
                                        TENANT_NAME));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(created.requireId()));
        assertThat(reloaded.manager()).isNotNull();
        assertThat(reloaded.manager().requireId()).isEqualTo(manager.requireId());

        var history =
                asTenant(
                        tenantA,
                        () -> managerHistory.findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(created.requireId()));
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().effectiveTo()).isNull();
    }

    /**
     * What the duplicate row actually costs. {@code
     * findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc} closes one open row, so
     * a create that opened two leaves one open forever and the history stops being a chain.
     */
    @Test
    void create_withManager_thenReassign_leavesExactlyOneOpenHistoryRow() {
        Employee manager1 = createActiveEmployee("Manager", "One");
        Employee manager2 = createActiveEmployee("Manager", "Two");
        Employee report =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        formWithManager("Report", "Ee", uniqueEmail(), manager1.requireId()),
                                        BASE_URL,
                                        TENANT_NAME));

        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager2.requireId()));

        var history =
                asTenant(
                        tenantA,
                        () -> managerHistory.findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(report.requireId()));
        assertThat(history).hasSize(2);
        assertThat(history).filteredOn(row -> row.effectiveTo() == null).hasSize(1);
    }

    /** Pins the no-manager path: a new hire without one must not get a null-manager history row. */
    @Test
    void create_withoutManager_writesNoManagerHistoryRow() {
        Employee created =
                asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", uniqueEmail()), BASE_URL, TENANT_NAME));

        var history =
                asTenant(
                        tenantA,
                        () -> managerHistory.findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(created.requireId()));
        assertThat(history).isEmpty();
    }

    /**
     * CLAUDE.md §5 rule 8. The manager is resolved through {@code require(managerId)}, which is
     * {@code @TenantId}-scoped, so another tenant's employee id must read as not-found rather
     * than linking across the boundary.
     */
    @Test
    void create_withManagerFromAnotherTenant_throwsNotFound() {
        Employee foreignManager =
                asTenant(
                        tenantB,
                        () -> employeeService.create(form("Foreign", "Manager", uniqueEmail()), BASE_URL, TENANT_NAME));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                employeeService.create(
                                                        formWithManager("Report", "Ee", uniqueEmail(), foreignManager.requireId()),
                                                        BASE_URL,
                                                        TENANT_NAME)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void terminate_withPastDate_appliesImmediatelyAndClosesStatusHistory() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        asTenant(tenantA, () -> employeeService.terminate(employee.requireId(), LocalDate.now(clock).minusDays(1)));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.status()).isEqualTo(EmployeeStatus.TERMINATED);
    }

    @Test
    void terminate_withFutureDate_staysActiveUntilTheJobApplies() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        LocalDate future = LocalDate.now(clock).plusDays(5);

        asTenant(tenantA, () -> employeeService.terminate(employee.requireId(), future));
        Employee stillActive = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(stillActive.status()).isEqualTo(EmployeeStatus.ACTIVE);

        clock.advance(java.time.Duration.ofDays(6));
        int applied = asTenant(tenantA, () -> employeeService.applyDueTerminations(LocalDate.now(clock)));

        assertThat(applied).isGreaterThanOrEqualTo(1);
        Employee terminated = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(terminated.status()).isEqualTo(EmployeeStatus.TERMINATED);
    }

    @Test
    void getProfileForViewer_self_isAllowed() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        AppUserPrincipal ownPrincipal = loadPrincipal(employee.email());

        Employee viewed = asTenant(tenantA, () -> employeeService.getProfileForViewer(employee.requireId(), ownPrincipal));

        assertThat(viewed.requireId()).isEqualTo(employee.requireId());
    }

    @Test
    void getProfileForViewer_directManager_isAllowed() {
        Employee manager = createActiveEmployee("Manager", "One");
        grantRole(manager, Role.MANAGER);
        Employee report = createActiveEmployee("Report", "Ee");
        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager.requireId()));
        AppUserPrincipal managerPrincipal = loadPrincipal(manager.email());

        Employee viewed = asTenant(tenantA, () -> employeeService.getProfileForViewer(report.requireId(), managerPrincipal));

        assertThat(viewed.requireId()).isEqualTo(report.requireId());
    }

    @Test
    void getProfileForViewer_unrelatedEmployee_isDenied() {
        Employee viewer = createActiveEmployee("Viewer", "Ee");
        Employee target = createActiveEmployee("Target", "Ee");
        AppUserPrincipal viewerPrincipal = loadPrincipal(viewer.email());

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.getProfileForViewer(target.requireId(), viewerPrincipal)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void peopleFacade_countActiveEmployeesInDepartment_excludesTerminated() {
        UUID departmentId = seedDepartment();
        Employee active = createActiveEmployee("Active", "One");
        Employee terminated = createActiveEmployee("Terminated", "One");
        assignDepartment(active, departmentId);
        assignDepartment(terminated, departmentId);
        asTenant(tenantA, () -> employeeService.terminate(terminated.requireId(), LocalDate.now(clock).minusDays(1)));

        long count = asTenant(tenantA, () -> peopleFacade.countActiveEmployeesInDepartment(departmentId));

        assertThat(count).isEqualTo(1);
    }

    private Employee createActiveEmployee(String firstName, String lastName) {
        Employee employee =
                asTenant(tenantA, () -> employeeService.create(form(firstName, lastName, uniqueEmail()), BASE_URL, TENANT_NAME));
        asTenant(tenantA, () -> employeeService.activateForUser(employee.userId()));
        return asTenant(tenantA, () -> employeeService.require(employee.requireId()));
    }

    private void grantRole(Employee employee, Role role) {
        asTenant(
                tenantA,
                () -> {
                    AppUser user = appUsers.findById(employee.userId()).orElseThrow();
                    user.grant(role);
                    return appUsers.save(user);
                });
    }

    private AppUserPrincipal loadPrincipal(String email) {
        return (AppUserPrincipal) asTenant(tenantA, () -> userDetailsService.loadUserByUsername(email));
    }

    private UUID seedDepartment() {
        return asTenant(
                tenantA,
                () -> {
                    Division division = divisionRepository.save(Division.create("Engineering-" + UUID.randomUUID(), null));
                    Department department =
                            departmentRepository.save(Department.create("Backend-" + UUID.randomUUID(), null, division));
                    return department.requireId();
                });
    }

    private void assignDepartment(Employee employee, UUID departmentId) {
        asTenant(
                tenantA,
                () ->
                        employeeService.updateAdminFields(
                                employee.requireId(),
                                new EmployeeForms.AdminEmployeePatch(
                                        null,
                                        employee.firstName(),
                                        employee.lastName(),
                                        employee.email(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        departmentId,
                                        null,
                                        null,
                                        null,
                                        BigDecimal.valueOf(8),
                                        null,
                                        null)));
    }

    private static EmployeeForms.CreateEmployee form(String firstName, String lastName, String email) {
        return new EmployeeForms.CreateEmployee(
                firstName, lastName, email, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    /** {@link #form} with the 14th field, {@code managerId}, filled in — the Add Employee form's Manager select. */
    private static EmployeeForms.CreateEmployee formWithManager(
            String firstName, String lastName, String email, UUID managerId) {
        return new EmployeeForms.CreateEmployee(
                firstName, lastName, email, null, null, null, null, null, null, null, null, null, null, managerId,
                null, null, null, null, null);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.test";
    }
}
