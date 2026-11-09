/*
 * Public site behaviour (ADR 0022 decision G). Progressive enhancement only: the sign-up form
 * submits and validates on the server without any of this. Served from 'self' (CSP script-src).
 */
(function () {
  "use strict";

  document.documentElement.classList.add("js");

  document.addEventListener("DOMContentLoaded", function () {
    // Phone menu: <details> already opens and closes without this; these close it the way a menu
    // is expected to, since an in-page link jump would otherwise leave it covering the section.
    var menu = document.querySelector(".nav-menu");
    if (menu) {
      var toggle = menu.querySelector("summary");
      menu.querySelectorAll(".nav-menu-panel a").forEach(function (link) {
        link.addEventListener("click", function () {
          menu.open = false;
        });
      });
      document.addEventListener("keydown", function (event) {
        if (event.key === "Escape" && menu.open) {
          menu.open = false;
          toggle.focus();
        }
      });
      document.addEventListener("click", function (event) {
        if (menu.open && !menu.contains(event.target)) {
          menu.open = false;
        }
      });
    }

    var form = document.getElementById("signup-form");
    if (!form) {
      return;
    }

    var email = document.getElementById("adminEmail");
    var company = document.getElementById("companyName");
    var slug = document.getElementById("slug");
    var status = document.getElementById("slug-status");
    var step2 = document.getElementById("signup-step2");
    var next = document.getElementById("signup-continue");
    var csrf = form.querySelector("input[name='_csrf']");
    var hint = status ? status.textContent : "";
    var slugEdited = slug && slug.value.trim() !== "";

    // Two steps, like TalentHR's single-field start: email first, the rest after Continue.
    // Skipped when the server sent the form back with errors, so every error stays visible.
    if (form.dataset.hasErrors !== "true" && email && email.value.trim() === "" && step2 && next) {
      step2.hidden = true;
      next.hidden = false;
      next.addEventListener("click", function () {
        if (!email.value.trim() || !email.checkValidity()) {
          email.reportValidity();
          return;
        }
        step2.hidden = false;
        next.hidden = true;
        company.focus();
      });
      email.addEventListener("keydown", function (event) {
        if (event.key === "Enter" && step2.hidden) {
          event.preventDefault();
          next.click();
        }
      });
    }

    function slugify(value) {
      return value
        .toLowerCase()
        .replace(/\b(llc|ltd|inc|fzco|fze|fz-llc|gmbh|pvt|limited|co)\b\.?/g, "")
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "")
        .split("-")
        .slice(0, 2)
        .join("-")
        .slice(0, 50);
    }

    var timer = null;
    var lastChecked = null;

    function check() {
      var value = slug.value.trim().toLowerCase();
      if (value === lastChecked) {
        return;
      }
      lastChecked = value;
      if (!value) {
        status.textContent = hint;
        status.className = "addr-status";
        return;
      }
      var body = new URLSearchParams();
      body.set("slug", value);
      if (csrf) {
        body.set("_csrf", csrf.value);
      }
      fetch("/signup/check-address", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
        body: body.toString(),
        credentials: "same-origin"
      })
        .then(function (response) {
          return response.ok ? response.json() : null;
        })
        .then(function (result) {
          if (!result || slug.value.trim().toLowerCase() !== value) {
            return;
          }
          status.textContent = result.message;
          status.className = "addr-status " + (result.available ? "ok" : "no");
        })
        .catch(function () {
          // The server re-checks on submit; a failed live check just leaves the hint in place.
        });
    }

    function scheduleCheck() {
      clearTimeout(timer);
      timer = setTimeout(check, 400);
    }

    if (company && slug) {
      company.addEventListener("input", function () {
        if (!slugEdited) {
          slug.value = slugify(company.value);
          scheduleCheck();
        }
      });
    }
    if (slug && status) {
      slug.addEventListener("input", function () {
        slugEdited = slug.value.trim() !== "";
        var cleaned = slug.value.toLowerCase().replace(/[^a-z0-9-]/g, "-").replace(/-{2,}/g, "-");
        if (cleaned !== slug.value) {
          slug.value = cleaned;
        }
        scheduleCheck();
      });
    }

    // Pre-select the browser's own zone when the server sent the default.
    var zone = document.getElementById("timezone");
    if (zone && zone.value === "UTC" && window.Intl) {
      var guess = Intl.DateTimeFormat().resolvedOptions().timeZone;
      if (guess && zone.querySelector("option[value='" + guess + "']")) {
        zone.value = guess;
      }
    }
  });
})();
