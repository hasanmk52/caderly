package com.caderly.caderlyhr.e2e;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.org.DepartmentService;
import com.caderly.caderlyhr.org.DivisionService;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeForms;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.support.PlaywrightE2ETestBase;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import com.caderly.caderlyhr.timeoff.LeaveRequest;
import com.caderly.caderlyhr.timeoff.LeaveRequestService;
import com.caderly.caderlyhr.timeoff.LeaveTypeService;
import com.caderly.caderlyhr.timeoff.PublicHolidayService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.Clip;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Generates the product screenshots on the public site's feature cards (BRAND §8.4: show the real
 * product, not a mock). Not a test: skipped unless {@code -DsiteScreenshots=true}. Re-run it after
 * a UI change so the site never shows an out-of-date screen:
 *
 * <pre>./mvnw test -Dtest=SiteScreenshotsE2ETest -DsiteScreenshots=true</pre>
 *
 * Seeds a fictional workspace whose names and dates match the site copy (Priya's vacation, Mon 3 –
 * Mon 10 Aug 2026, skipping the weekend and Eid al-Adha on Fri 7 Aug), crops three screens at 2×
 * from a 600px-wide viewport so their text stays legible when scaled into a card,
 * and writes WebP files (via {@code cwebp}, which must be on the PATH) to {@code static/img/site/}.
 * It also refreshes the README's screenshots in {@code docs/screenshots/}.
 */
@EnabledIfSystemProperty(named = "siteScreenshots", matches = "true")
@Import(MutableClockConfiguration.class)
class SiteScreenshotsE2ETest extends PlaywrightE2ETestBase {

    private static final String PASSWORD = "SiteShots-Passphrase1";
    private static final Path PNG_DIR = Path.of("target/site-shots");
    private static final Path WEBP_DIR = Path.of("src/main/resources/static/img/site");

    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EmployeeService employeeService;
    @Autowired private DivisionService divisions;
    @Autowired private DepartmentService departments;
    @Autowired private LeaveTypeService leaveTypes;
    @Autowired private PublicHolidayService holidays;
    @Autowired private LeaveRequestService leaveRequests;
    @Autowired private MutableClock clock;

    private final Map<String, UUID> departmentIds = new HashMap<>();

    @Override
    protected Browser.NewContextOptions contextOptions() {
        return new Browser.NewContextOptions().setViewportSize(600, 900).setDeviceScaleFactor(2);
    }

    @Test
    void generateSiteScreenshots() throws Exception {
        String slug = "northwind" + UUID.randomUUID().toString().substring(0, 6);
        baseUrl = "http://" + slug + ".localhost:" + port;
        String aishaEmail = "aisha.rahman@northwind.example";
        seed(slug, aishaEmail);

        loginAs(aishaEmail, PASSWORD);
        Files.createDirectories(PNG_DIR);

        page.navigate(baseUrl + "/for-action");
        shot(page.locator("#pendingList"), "site-approvals", 0);

        page.navigate(baseUrl + "/calendar?month=2026-08");
        // At this width the grid scrolls inside .calendar-scroll; the shot is its visible first half
        // of the month, which keeps the day numbers legible once scaled into a site card.
        shot(page.locator(".calendar-scroll"), "site-calendar", 0);

        page.navigate(baseUrl + "/admin/reports/leave-utilization?from=2026-08-01&to=2026-08-31");
        shot(page.locator(".caderly-chart"), "site-report", 0);

        readmeScreenshots(aishaEmail);

    }

    /** The README's 1280×800 screens, from the same fictional workspace (no real data in docs). */
    private void readmeScreenshots(String email) throws IOException {
        Path dir = Path.of("docs/screenshots");
        try (var context = page.context().browser().newContext(
                new Browser.NewContextOptions().setViewportSize(1280, 800).setDeviceScaleFactor(1)
                        .setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE))) {
            Page readme = context.newPage();
            readme.navigate(baseUrl + "/login");
            readme.fill("#email", email);
            readme.fill("#password", PASSWORD);
            readme.click("button[type=submit]");
            readme.waitForURL(baseUrl + "/");
            Map<String, String> screens = new java.util.LinkedHashMap<>();
            screens.put("dashboard.png", "/");
            screens.put("employees.png", "/admin/employees");
            screens.put("for-action.png", "/for-action");
            screens.put("calendar.png", "/calendar?month=2026-08");
            screens.put("reports.png", "/admin/reports/leave-utilization?from=2026-08-01&to=2026-08-31");
            for (var screen : screens.entrySet()) {
                readme.navigate(baseUrl + screen.getValue());
                readme.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
                readme.screenshot(new Page.ScreenshotOptions().setPath(dir.resolve(screen.getKey())));
            }
            System.out.println("README-SHOTS written to " + dir);
        }
    }

    private void seed(String slug, String aishaEmail) {
        UUID tenantId =
                TenantContext.runAsSystem(
                        "site screenshots: seed tenant",
                        () -> tenants.save(new Tenant(slug, "Northwind", "Asia/Dubai", 96, null)).getId());
        TenantContext.set(tenantId);
        try {
            UUID company = divisions.create("Company", null).requireId();
            for (String name : new String[] {"Engineering", "Operations", "Sales", "People", "Finance"}) {
                departmentIds.put(name, departments.create(name, null, company).requireId());
            }

            Employee aisha = person("Aisha", "Rahman", aishaEmail, "People", null, "People Operations Lead", Role.ADMIN);
            Employee rahul = person("Rahul", "Menon", "rahul.menon@northwind.example", "Engineering", null, "Engineering Manager", Role.MANAGER);
            Employee priya = person("Priya", "Nair", "priya.nair@northwind.example", "Operations", rahul.requireId(), "Operations Analyst", null);
            Employee karim = person("Karim", "Saleh", "karim.saleh@northwind.example", "Sales", null, "Account Executive", null);
            Employee lena = person("Lena", "Fischer", "lena.fischer@northwind.example", "Engineering", rahul.requireId(), "Backend Engineer", null);
            Employee omar = person("Omar", "Haddad", "omar.haddad@northwind.example", "Finance", null, "Finance Associate", null);
            Employee sara = person("Sara", "Lopez", "sara.lopez@northwind.example", "Engineering", rahul.requireId(), "Product Designer", null);
            Employee daniel = person("Daniel", "Okafor", "daniel.okafor@northwind.example", "Sales", null, "Sales Manager", null);

            UUID vacation = leaveTypes.create("Vacation", "bi-airplane", null, true, true, false, true, new BigDecimal("30"), null).requireId();
            UUID sick = leaveTypes.create("Sick", "bi-bandaid", null, true, true, true, false, new BigDecimal("10"), null).requireId();
            holidays.create(LocalDate.of(2026, 8, 7), "Eid al-Adha");
            // An upcoming one for the dashboard widget; outside August, so the counts above are unaffected.
            holidays.create(LocalDate.of(2026, 12, 2), "UAE National Day");

            // The clock reads Sun 2 Aug 2026, so every request below is in the future when booked.
            book(priya, vacation, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10), "Family visit");
            approve(book(karim, vacation, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 14), null), aisha, true);
            approve(book(lena, vacation, LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 21), null), rahul, false);
            approve(book(omar, vacation, LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 6), null), aisha, true);
            approve(book(sara, vacation, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11), null), rahul, false);
            approve(book(daniel, vacation, LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 20), null), aisha, true);
            book(rahul, vacation, LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 26), "Conference");
            // A few sick days so the utilization chart stacks more than one leave type.
            approve(book(omar, sick, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12), null), aisha, true);
            approve(book(lena, sick, LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 26), null), rahul, false);
            approve(book(daniel, sick, LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 27), null), aisha, true);
        } finally {
            TenantContext.clear();
        }
        // Screens are captured "on" Tue 11 Aug, in the middle of the seeded month.
        clock.advance(Duration.ofDays(9));
    }

    private Employee person(
            String first, String last, String email, String department, UUID managerId, String jobTitle, Role extraRole) {
        Employee employee =
                employeeService.create(
                        new EmployeeForms.CreateEmployee(
                                first, last, email,
                                null, null, null, null, null, null, null,
                                LocalDate.of(2023, 3, 1),
                                null,
                                departmentIds.get(department),
                                managerId,
                                jobTitle,
                                null, null, null, null),
                        baseUrl,
                        "Northwind");
        AppUser user = appUsers.findById(employee.userId()).orElseThrow();
        if (extraRole != null) {
            user.grant(extraRole);
        }
        user.acceptInvite(passwordEncoder.encode(PASSWORD));
        appUsers.save(user);
        employeeService.activateForUser(user.requireId());
        return employee;
    }

    private LeaveRequest book(Employee who, UUID typeId, LocalDate start, LocalDate end, String note) {
        return leaveRequests.book(who.requireId(), typeId, start, end, false, false, note, baseUrl);
    }

    private void approve(LeaveRequest request, Employee decider, boolean deciderIsAdmin) {
        leaveRequests.approve(
                request.requireId(), decider.userId(), decider.requireId(), decider.fullName(), deciderIsAdmin, null);
    }

    /** Element screenshot at 2×; {@code maxWidth} > 0 crops the element's left part only. */
    private void shot(Locator element, String name, int maxWidth) throws IOException, InterruptedException {
        element.waitFor();
        Path png = PNG_DIR.resolve(name + ".png");
        if (maxWidth > 0) {
            BoundingBox box = element.boundingBox();
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(png)
                    .setClip(new Clip(box.x, box.y, Math.min(box.width, maxWidth), box.height)));
        } else {
            element.screenshot(new Locator.ScreenshotOptions().setPath(png));
        }
        Path webp = WEBP_DIR.resolve(name + ".webp");
        Process cwebp = new ProcessBuilder("cwebp", "-quiet", "-q", "82", png.toString(), "-o", webp.toString())
                .inheritIO()
                .start();
        if (cwebp.waitFor() != 0) {
            throw new IllegalStateException("cwebp failed for " + name);
        }
        System.out.println("SITE-SHOT " + webp + " " + Files.size(webp) / 1024 + " KB");
    }
}
