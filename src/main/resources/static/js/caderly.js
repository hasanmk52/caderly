/*
 * Global htmx glue (UI Guidelines §13: no inline JavaScript in templates).
 *
 * Five responsibilities, the first applying site-wide and the rest specific to admin/timeoff
 * screens but written generically enough to serve any future offcanvas- or modal-based screen:
 *   0. Attaching the CSRF header to every htmx state-changing request. Spring Security's
 *      Thymeleaf dialect only auto-injects a hidden CSRF field into forms it decorates via
 *      th:action; these forms submit through hx-post/hx-patch/hx-delete instead, so nothing
 *      would carry the token without this — see fragments/head.html's meta tags.
 *   1. Opening an offcanvas after htmx swaps a form into its body (Add/Edit both work this way —
 *      see AdminOrganizationController's Javadoc for why the offcanvas element itself is never
 *      part of an htmx response).
 *   2. Showing a toast and closing any open offcanvas/modal when the server signals success via
 *      the HX-Trigger response header (UI Guidelines §7.4).
 *   3. Book Time Off / Reject leave request (Phase 1.6, UI Guidelines §8.1/§8.6) open via a plain
 *      Bootstrap data-bs-toggle on their trigger — the modal shell is always present, unlike the
 *      offcanvas forms — so they need no open-on-swap glue, only the close-on-success handling
 *      responsibility 2 already covers.
 *   4. Drag-and-drop upload dropzones (Files page, profile Documents tab — UI Guidelines §8.7).
 *      Plain DOM wiring here, not an Alpine expression: this app's CSP-safe Alpine build (needed
 *      because SecurityConfig's CSP has no 'unsafe-eval') rejects both `$refs` and `$el` inside a
 *      compound `@drop` handler with a parser error — confirmed by actually triggering a drop and
 *      reading the browser console, not assumed — so the input-assignment logic lives here
 *      instead. Delegated on `document.body`, matching every other handler in this file, so it
 *      keeps working on a dropzone htmx swaps in later without any rebinding.
 *   5. Hover tooltips/popovers (Team Calendar leave bars, UI Guidelines §8.4; Admin
 *      Notifications' Retry button). Bootstrap does not auto-initialize either widget.
 *      initDisclosureWidgets() runs once on DOMContentLoaded for the page's initial markup and
 *      again after every htmx swap, scoped to just the swapped subtree — the calendar page never
 *      swaps so only the first call ever does anything there, but a delivery-log row (Retry) is
 *      replaced by its own htmx response and would otherwise render its next tooltip un-wired.
 *   6. Copy-to-clipboard (Settings -> Calendar integration). Plain `navigator.clipboard` call
 *      delegated on `document.body`, same reasoning as responsibility 4 — not an Alpine
 *      expression, so there is no first-mover risk on the CSP-safe Alpine build's constraints.
 *   7. Auto-submitting filter controls (People, Team Calendar, Admin Notifications delivery
 *      log) marked `data-auto-submit`. These used to carry `onchange="this.form.submit()"`
 *      directly on the element — inline JS, which this file's own opening line already forbids,
 *      and which SecurityConfig's CSP (`script-src 'self'`, no `unsafe-inline`) silently refuses
 *      to compile into a callable handler: the browser accepts the markup, `getAttribute` still
 *      returns the string, but `element.onchange` reads back `null` and the attribute never
 *      runs. No console error, no thrown exception — the control just does nothing on change.
 *      Confirmed live (`element.onchange === null` despite the attribute being present) before
 *      writing this fix, not assumed from reading the CSP line.
 */
(function () {
  "use strict";

  document.body.addEventListener("htmx:configRequest", function (event) {
    var token = document.querySelector('meta[name="_csrf"]');
    var header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header) {
      event.detail.headers[header.content] = token.content;
    }
  });

  var OFFCANVAS_BODY_IDS = [
    "divisionOffcanvasBody",
    "departmentOffcanvasBody",
    "employeeOffcanvasBody",
    "leaveTypeOffcanvasBody",
    "holidayOffcanvasBody",
  ];

  var MODAL_IDS = ["bookLeaveModal", "rejectLeaveModal"];

  document.body.addEventListener("htmx:afterSwap", function (event) {
    if (OFFCANVAS_BODY_IDS.indexOf(event.detail.target.id) === -1) {
      return;
    }
    // Only GET (new-form/edit-form) opens the panel. A POST/PATCH swap into the same target
    // is a save response resetting the form to blank — showing here would fight the
    // organization-toast handler's hide() on the very same round trip.
    if (event.detail.requestConfig.verb !== "get") {
      return;
    }
    var offcanvasEl = event.detail.target.closest(".offcanvas");
    if (offcanvasEl) {
      bootstrap.Offcanvas.getOrCreateInstance(offcanvasEl).show();
    }
  });

  document.body.addEventListener("organization-toast", function (event) {
    OFFCANVAS_BODY_IDS.forEach(function (id) {
      var body = document.getElementById(id);
      var offcanvasEl = body && body.closest(".offcanvas");
      var instance = offcanvasEl && bootstrap.Offcanvas.getInstance(offcanvasEl);
      if (instance) {
        instance.hide();
      }
    });
    MODAL_IDS.forEach(function (id) {
      var modalEl = document.getElementById(id);
      var instance = modalEl && bootstrap.Modal.getInstance(modalEl);
      if (instance) {
        instance.hide();
      }
    });
    showToast(event.detail.message);
  });

  document.body.addEventListener("dragover", function (event) {
    var zone = event.target.closest(".upload-dropzone");
    if (!zone) {
      return;
    }
    event.preventDefault();
    zone.classList.add("upload-dropzone-active");
  });

  document.body.addEventListener("dragleave", function (event) {
    var zone = event.target.closest(".upload-dropzone");
    // relatedTarget is null when the pointer leaves the window entirely, and is some element
    // still inside the zone when only moving between the icon/text children — only clear the
    // active state once the pointer has genuinely left the zone's bounds.
    if (zone && !zone.contains(event.relatedTarget)) {
      zone.classList.remove("upload-dropzone-active");
    }
  });

  document.body.addEventListener("drop", function (event) {
    var zone = event.target.closest(".upload-dropzone");
    if (!zone) {
      return;
    }
    event.preventDefault();
    zone.classList.remove("upload-dropzone-active");
    var input = zone.querySelector('input[type="file"]');
    if (!input || !event.dataTransfer || event.dataTransfer.files.length === 0) {
      return;
    }
    input.files = event.dataTransfer.files;
    showDroppedFileName(zone, input.files[0].name);
  });

  document.body.addEventListener("change", function (event) {
    if (!event.target.matches(".upload-dropzone input[type=file]")) {
      return;
    }
    var zone = event.target.closest(".upload-dropzone");
    if (zone && event.target.files.length > 0) {
      showDroppedFileName(zone, event.target.files[0].name);
    }
  });

  function showDroppedFileName(zone, name) {
    var label = zone.querySelector(".upload-dropzone-filename");
    if (label) {
      label.textContent = name;
      label.classList.remove("d-none");
    }
  }

  function showToast(message) {
    var container = document.getElementById("toast-container");
    if (!container) {
      return;
    }
    var toastEl = document.createElement("div");
    toastEl.className = "toast align-items-center text-bg-success border-0";
    toastEl.setAttribute("role", "status");
    toastEl.setAttribute("aria-live", "polite");
    toastEl.setAttribute("aria-atomic", "true");

    var flex = document.createElement("div");
    flex.className = "d-flex";

    var body = document.createElement("div");
    body.className = "toast-body";
    body.textContent = message;

    var closeButton = document.createElement("button");
    closeButton.type = "button";
    closeButton.className = "btn-close btn-close-white me-2 m-auto";
    closeButton.setAttribute("data-bs-dismiss", "toast");
    closeButton.setAttribute("aria-label", "Close");

    flex.appendChild(body);
    flex.appendChild(closeButton);
    toastEl.appendChild(flex);
    container.appendChild(toastEl);

    var toast = new bootstrap.Toast(toastEl, { delay: 4000 });
    toastEl.addEventListener("hidden.bs.toast", function () {
      toastEl.remove();
    });
    toast.show();
  }

  function initDisclosureWidgets(root) {
    root.querySelectorAll('[data-bs-toggle="popover"]').forEach(function (el) {
      bootstrap.Popover.getOrCreateInstance(el, { trigger: "hover focus", html: false });
    });
    root.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(function (el) {
      bootstrap.Tooltip.getOrCreateInstance(el);
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    initDisclosureWidgets(document);
  });

  document.body.addEventListener("htmx:afterSwap", function (event) {
    initDisclosureWidgets(event.detail.target);
  });

  document.body.addEventListener("change", function (event) {
    if (event.target.matches("[data-auto-submit]") && event.target.form) {
      event.target.form.submit();
    }
  });

  document.body.addEventListener("click", function (event) {
    var button = event.target.closest("[data-copy-target]");
    if (!button) {
      return;
    }
    var target = document.getElementById(button.getAttribute("data-copy-target"));
    if (!target || !navigator.clipboard) {
      return;
    }
    navigator.clipboard.writeText(target.value).then(function () {
      showToast(button.getAttribute("data-copied-message") || "Copied");
    });
  });

  // Home Customize mode (ADR 0021). Plain DOM, not Alpine: the CSP build cannot run compound
  // expressions, and drag/reorder needs imperative node moves anyway. The saved layout is just
  // the DOM order plus each column's data-hidden flag.
  var dashboardSortable = null;

  function dashboardGrid() {
    return document.querySelector("[data-dashboard-grid]");
  }

  function setDashboardEditing(editing) {
    var grid = dashboardGrid();
    if (!grid) {
      return;
    }
    grid.classList.toggle("is-editing", editing);
    document.querySelector("[data-dashboard-bar]").classList.toggle("d-none", !editing);
    document.querySelector("[data-dashboard-customize]").classList.toggle("d-none", editing);
    if (editing && !dashboardSortable && window.Sortable) {
      dashboardSortable = window.Sortable.create(grid, {
        draggable: "[data-widget]",
        handle: ".dashboard-handle",
        animation: 150,
      });
    }
  }

  function saveDashboardLayout(button) {
    var columns = Array.prototype.slice.call(dashboardGrid().querySelectorAll("[data-widget]"));
    htmx.ajax("POST", button.getAttribute("data-save-url"), {
      swap: "none",
      values: {
        order: columns.map(function (c) { return c.getAttribute("data-widget"); }),
        hidden: columns
          .filter(function (c) { return c.getAttribute("data-hidden") === "true"; })
          .map(function (c) { return c.getAttribute("data-widget"); }),
      },
    });
  }

  document.body.addEventListener("click", function (event) {
    var grid = dashboardGrid();
    if (!grid) {
      return;
    }
    var target = event.target;
    if (target.closest("[data-dashboard-customize]")) {
      setDashboardEditing(true);
    } else if (target.closest("[data-dashboard-cancel]")) {
      window.location.reload();
    } else if (target.closest("[data-dashboard-save]")) {
      saveDashboardLayout(target.closest("[data-dashboard-save]"));
    } else if (target.closest("[data-dashboard-reset]")) {
      htmx.ajax("POST", target.closest("[data-dashboard-reset]").getAttribute("data-reset-url"), { swap: "none" });
    } else if (target.closest("[data-dashboard-toggle]")) {
      var column = target.closest("[data-widget]");
      var hidden = column.getAttribute("data-hidden") === "true";
      column.setAttribute("data-hidden", hidden ? "false" : "true");
      column.querySelector("[data-dashboard-toggle] i").className = hidden ? "bi bi-eye" : "bi bi-eye-slash";
    } else if (target.closest("[data-dashboard-move]")) {
      var moveButton = target.closest("[data-dashboard-move]");
      var col = moveButton.closest("[data-widget]");
      var sibling = moveButton.getAttribute("data-dashboard-move") === "-1" ? col.previousElementSibling : col.nextElementSibling;
      if (sibling) {
        if (moveButton.getAttribute("data-dashboard-move") === "-1") {
          grid.insertBefore(col, sibling);
        } else {
          grid.insertBefore(sibling, col);
        }
        // Moving a node drops focus in some browsers; a keyboard user must keep their place.
        moveButton.focus();
      }
    }
  });

  // Report chart tooltips (fragments/charts.html): one floating label for any [data-chart-tip]
  // mark, on hover and on keyboard focus. textContent only, since department and leave-type
  // names are tenant data. Without JavaScript each mark still has its aria-label and the table.
  var chartTip = null;
  function showChartTip(target) {
    var text = target.getAttribute("data-chart-tip");
    if (!text) {
      return;
    }
    if (!chartTip) {
      chartTip = document.createElement("div");
      chartTip.className = "caderly-chart-tip";
      chartTip.setAttribute("role", "presentation");
      document.body.appendChild(chartTip);
    }
    chartTip.textContent = text;
    chartTip.hidden = false;
    var box = target.getBoundingClientRect();
    var left = box.left + box.width / 2 + window.scrollX - chartTip.offsetWidth / 2;
    var maxLeft = document.documentElement.clientWidth - chartTip.offsetWidth - 8;
    chartTip.style.left = Math.max(8, Math.min(left, maxLeft)) + "px";
    chartTip.style.top = box.top + window.scrollY - chartTip.offsetHeight - 8 + "px";
  }
  function hideChartTip() {
    if (chartTip) {
      chartTip.hidden = true;
    }
  }
  ["mouseover", "focusin"].forEach(function (type) {
    document.addEventListener(type, function (event) {
      var mark = event.target.closest && event.target.closest("[data-chart-tip]");
      if (mark) {
        showChartTip(mark);
      }
    });
  });
  ["mouseout", "focusout"].forEach(function (type) {
    document.addEventListener(type, function (event) {
      if (event.target.closest && event.target.closest("[data-chart-tip]")) {
        hideChartTip();
      }
    });
  });
})();
