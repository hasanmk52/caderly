package com.caderly.caderlyhr.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells the sidebar which item is the current page (UI Guidelines §1) as {@code activeNav}. Derived
 * from the path so no controller has to remember to set it; {@link ProfileController} overrides it
 * because {@code /profile/{id}} is "My Profile" or "People" depending on whose profile it is.
 */
@ControllerAdvice
class NavModelAdvice {

    @ModelAttribute("activeNav")
    @Nullable String activeNav(HttpServletRequest request) {
        return forPath(request.getRequestURI());
    }

    static @Nullable String forPath(String path) {
        if (path.equals("/")) {
            return "home";
        }
        if (path.startsWith("/admin/employees") || path.startsWith("/profile/")) {
            return "people";
        }
        if (path.equals("/profile")) {
            return "my-profile";
        }
        if (path.startsWith("/admin/reports")) {
            return "reports";
        }
        for (String item : new String[] {
            "files", "calendar", "for-action", "admin/users", "admin/organization", "admin/leave-types",
            "admin/holidays", "admin/notifications", "admin/audit-log"
        }) {
            if (path.equals("/" + item) || path.startsWith("/" + item + "/")) {
                return item.replace("admin/", "");
            }
        }
        return null;
    }
}
