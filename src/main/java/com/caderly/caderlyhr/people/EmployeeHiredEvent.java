package com.caderly.caderlyhr.people;

import java.util.UUID;

/**
 * Published after a new {@link Employee} is created, and again when an Admin first gives one a
 * hire date (PRD §14.2) — the grant it triggers is idempotent. An event rather than a direct
 * call for the same reason as {@link com.caderly.caderlyhr.identity.UserInviteAcceptedEvent}: {@code
 * timeoff} needs to react to hires (initial balance grant, PRD §12.2), but {@code timeoff} also
 * needs to read {@code people} data for its own scheduled jobs via {@code PeopleFacade} — a
 * direct call in this direction too would be a package cycle. {@code people} does not know {@code
 * timeoff} exists.
 */
public record EmployeeHiredEvent(UUID employeeId) {}
