package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.identity.InviteService;
import com.caderly.caderlyhr.identity.Role;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Admin-only, read-only list of every login account and its roles (PRD §26).
 *
 * <p>Creating a user happens exclusively through People &gt; Add Employee ({@link
 * com.caderly.caderlyhr.web.AdminEmployeeController}), which creates the {@link
 * com.caderly.caderlyhr.identity.AppUser} and its {@link com.caderly.caderlyhr.people.Employee} together
 * in one transaction (PRD §5: an Admin or Manager is also always an Employee). This page has no
 * invite form of its own: inviting from here would create an account with no Employee, so it
 * would not appear on the People page or have a profile.
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController {

    private final InviteService invites;

    AdminUserController(InviteService invites) {
        this.invites = invites;
    }

    /**
     * The class-level {@code @PreAuthorize} already covers this, but CLAUDE.md §6 A01 asks for an
     * annotation on every method and the ArchUnit rule checks for one — an inherited grant is
     * easy to lose by moving a method to another class.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    String listUsers(Model model) {
        model.addAttribute("users", userRows());
        return "admin/users";
    }

    /**
     * Flattens entities into a view record before they leave the transaction: templates never
     * touch entities (CLAUDE.md §7), and the roles association is lazy in the general case.
     */
    private List<UserRow> userRows() {
        return invites.listUsers().stream()
                .map(
                        user ->
                                new UserRow(
                                        user.email(),
                                        user.status().name(),
                                        user.status().label(),
                                        user.roles().stream().map(Role::label).sorted().toList(),
                                        user.lastLoginAt()))
                .toList();
    }

    record UserRow(
            String email, String status, String statusLabel, List<String> roles, @Nullable Instant lastLoginAt) {}
}
