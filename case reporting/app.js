const API_BASE = window.HARBOR_API_URL || "";
const $ = (selector) => document.querySelector(selector);
let csrfToken = "";

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (csrfToken && !["GET", "HEAD", "OPTIONS"].includes((options.method || "GET").toUpperCase())) {
    headers.set("X-CSRF-TOKEN", csrfToken);
  }
  const response = await fetch(`${API_BASE}/api${path}`, {
    ...options,
    headers,
    credentials: "include",
    cache: "no-store"
  });
  if (response.status === 204) return null;
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.message || "We couldn’t complete that request. Please try again.");
  return payload;
}

async function refreshCsrf() {
  const result = await api("/auth/csrf");
  csrfToken = result.token;
}

function showMessage(element, message) {
  element.textContent = message;
  element.hidden = false;
}

function clearMessage(element) {
  element.textContent = "";
  element.hidden = true;
}

$("#start-report").addEventListener("click", () => {
  $("#report-panel").hidden = false;
  $("#report-panel").scrollIntoView({ behavior: "smooth", block: "start" });
  $("#category").focus({ preventScroll: true });
});
function closeReport() {
  $("#report-panel").hidden = true;
}
$("#close-report").addEventListener("click", closeReport);
$("#cancel-report").addEventListener("click", closeReport);

$("#report-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const error = $("#report-error");
  clearMessage(error);
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const result = await api("/reports", {
      method: "POST",
      body: JSON.stringify({
        category: $("#category").value,
        details: $("#details").value.trim(),
        person: $("#person").value.trim(),
        contact: $("#contact").value.trim()
      })
    });
    $("#report-reference").textContent = result.reference;
    $("#report-access").textContent = result.accessCode;
    $("#report-panel").hidden = true;
    $("#success-panel").hidden = false;
    $("#success-panel").scrollIntoView({ behavior: "smooth", block: "center" });
    $("#dismiss-success").focus({ preventScroll: true });
  } catch (exception) {
    showMessage(error, exception.message);
  } finally {
    button.disabled = false;
  }
});

$("#copy-codes").addEventListener("click", async (event) => {
  const copyText = `Reference: ${$("#report-reference").textContent}\nAccess code: ${$("#report-access").textContent}`;
  try {
    await navigator.clipboard.writeText(copyText);
    event.currentTarget.textContent = "Copied   keep them somewhere safe";
  } catch {
    event.currentTarget.textContent = "Copy unavailable   please write these down";
  }
});
$("#dismiss-success").addEventListener("click", () => {
  $("#success-panel").hidden = true;
  $("#track").scrollIntoView({ behavior: "smooth" });
});

$("#status-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const error = $("#status-error");
  const resultBox = $("#status-result");
  clearMessage(error);
  resultBox.hidden = true;
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const result = await api("/status", {
      method: "POST",
      body: JSON.stringify({
        reference: $("#reference").value.trim(),
        accessCode: $("#access-code").value
      })
    });
    resultBox.replaceChildren();
    const heading = document.createElement("strong");
    heading.textContent = `Current status: ${result.statusLabel}`;
    const description = document.createElement("span");
    description.textContent = result.updatedAt
      ? `Last updated ${new Date(result.updatedAt).toLocaleDateString()}. ${result.publicUpdate || "Your report is being handled by the support team."}`
      : "Your report is being handled by the support team.";
    resultBox.append(heading, description);
    resultBox.hidden = false;
  } catch (exception) {
    showMessage(error, exception.message);
  } finally {
    button.disabled = false;
  }
});

async function openStaff() {
  $("#staff-login").hidden = false;
  $("#staff-login").scrollIntoView({ behavior: "smooth", block: "center" });
  $("#login-email").focus({ preventScroll: true });
  try {
    await refreshCsrf();
  } catch {
    showMessage($("#login-error"), "The secure service is unavailable right now. Please try again later.");
  }
}
$("#staff-entry").addEventListener("click", openStaff);
$("#close-login").addEventListener("click", () => { $("#staff-login").hidden = true; });

$("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const error = $("#login-error");
  clearMessage(error);
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const form = new URLSearchParams({
      username: $("#login-email").value.trim(),
      password: $("#login-password").value
    });
    await api("/auth/login", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: form });
    $("#login-password").value = "";
    await refreshCsrf();
    $("#staff-login").hidden = true;
    await showStaff();
  } catch (exception) {
    showMessage(error, exception.message);
  } finally {
    button.disabled = false;
  }
});

async function showStaff() {
  const result = await api("/auth/me");
  const isCommittee = result.role === "COMMITTEE";
  $("#staff-role").textContent = result.role === "COMMITTEE"
    ? "Authorized committee workspace · pattern suggestions are leads for human review only"
    : "Authorized case staff workspace";
  $("#staff-view").hidden = false;
  $("#staff-view").scrollIntoView({ behavior: "smooth", block: "start" });
  const patternTab = document.querySelector('[data-view="patterns"]');
  const casesTab = document.querySelector('[data-view="cases"]');
  patternTab.hidden = !isCommittee;
  casesTab.hidden = isCommittee;
  casesTab.classList.toggle("active", !isCommittee);
  patternTab.classList.toggle("active", isCommittee);
  $("#case-list").hidden = isCommittee;
  $("#pattern-list").hidden = !isCommittee;
  $("#retention-note").textContent = `Reports are scheduled for deletion after ${result.reportRetentionDays} days; audit records after ${result.auditRetentionDays} days. Retention is configured by your institution.`;
  if (isCommittee) await loadPatterns();
  else await loadCases();
}

async function loadCases() {
  const list = $("#case-list");
  list.replaceChildren();
  try {
    const result = await api("/cases");
    if (!result.length) {
      list.textContent = "There are no reports to review at this time.";
      return;
    }
    result.forEach((item) => {
      const card = document.createElement("article");
      card.className = "case-card";
      const heading = document.createElement("h3");
      heading.textContent = `${item.category} · ${item.reference}`;
      const meta = document.createElement("p");
      meta.className = "case-meta";
      meta.textContent = `Received ${new Date(item.createdAt).toLocaleDateString()} · ${item.statusLabel}`;
      const details = document.createElement("p");
      details.textContent = item.preview;
      const open = document.createElement("button");
      open.className = "button button-quiet";
      open.type = "button";
      open.textContent = "Open report details";
      const privateDetails = document.createElement("div");
      privateDetails.hidden = true;
      open.addEventListener("click", async () => {
        if (!privateDetails.hidden) {
          privateDetails.hidden = true;
          open.textContent = "Open report details";
          return;
        }
        try {
          const report = await api(`/cases/${encodeURIComponent(item.id)}`);
          privateDetails.replaceChildren();
          [["Report", report.details], ["Involved person", report.person || "Not provided"], ["Safe contact", report.contact || "Not provided"]].forEach(([label, value]) => {
            const section = document.createElement("p");
            const heading = document.createElement("strong");
            heading.textContent = `${label}: `;
            section.append(heading, document.createTextNode(value));
            privateDetails.append(section);
          });
          const historyHeading = document.createElement("strong");
          historyHeading.textContent = "Action history";
          privateDetails.append(historyHeading);
          report.actions.forEach((action) => {
            const entry = document.createElement("p");
            entry.textContent = `${new Date(action.createdAt).toLocaleString()} · ${action.actor} · ${action.action}${action.note ? ` · ${action.note}` : ""}`;
            privateDetails.append(entry);
          });
          privateDetails.hidden = false;
          open.textContent = "Hide report details";
        } catch (exception) {
          showMessage($("#staff-error"), exception.message);
        }
      });
      const controls = document.createElement("div");
      controls.className = "case-controls";
      const status = document.createElement("select");
      status.setAttribute("aria-label", `Update status for ${item.reference}`);
      [["RECEIVED", "Received"], ["REVIEWING", "Under review"], ["FOLLOW_UP", "Follow-up"], ["CLOSED", "Closed"]].forEach(([value, label]) => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = label;
        option.selected = value === item.status;
        status.append(option);
      });
      const note = document.createElement("input");
      note.placeholder = "Brief internal action note";
      note.setAttribute("aria-label", `Internal note for ${item.reference}`);
      const assignee = document.createElement("input");
      assignee.value = item.assignedTo || "";
      assignee.placeholder = "Assign to staff email (blank clears)";
      assignee.setAttribute("aria-label", `Assign ${item.reference} to case staff`);
      const save = document.createElement("button");
      save.className = "button button-primary";
      save.type = "button";
      save.textContent = "Save update";
      save.addEventListener("click", async () => {
        try {
          await api(`/cases/${encodeURIComponent(item.id)}/update`, {
            method: "POST",
            body: JSON.stringify({ status: status.value, note: note.value.trim(), assignedTo: assignee.value.trim() })
          });
          await loadCases();
        } catch (exception) {
          showMessage($("#staff-error"), exception.message);
        }
      });
      controls.append(status, assignee, note, save);
      card.append(heading, meta, details, open, privateDetails, controls);
      list.append(card);
    });
  } catch (exception) {
    showMessage($("#staff-error"), exception.message);
  }
}

async function loadPatterns() {
  const list = $("#pattern-list");
  list.replaceChildren();
  try {
    const suggestions = await api("/patterns");
    if (!suggestions.length) {
      list.textContent = "No unreviewed pattern leads are available. Any suggestions shown here require a human decision.";
      return;
    }
    suggestions.forEach((item) => {
      const card = document.createElement("article");
      card.className = "pattern-card";
      const heading = document.createElement("h3");
      heading.textContent = `Possible similarity · ${item.similarity}%`;
      const text = document.createElement("p");
      text.textContent = `${item.firstReference} (${item.firstCategory}, ${item.firstDate}) and ${item.secondReference} (${item.secondCategory}, ${item.secondDate}) share broad wording or category signals. This is a lead, not a finding or proof. Review each report separately; no reporter identity or story is shown here.`;
      const confirm = document.createElement("button");
      confirm.className = "button button-primary";
      confirm.type = "button";
      confirm.textContent = "Confirm link after review";
      const dismiss = document.createElement("button");
      dismiss.className = "button button-quiet";
      dismiss.type = "button";
      dismiss.textContent = "Dismiss suggestion";
      const review = async (decision) => {
        try {
          await api(`/patterns/${encodeURIComponent(item.firstId)}/${encodeURIComponent(item.secondId)}/review`, {
            method: "POST",
            body: JSON.stringify({ decision })
          });
          await loadPatterns();
        } catch (exception) {
          showMessage($("#staff-error"), exception.message);
        }
      };
      confirm.addEventListener("click", () => review("CONFIRM"));
      dismiss.addEventListener("click", () => review("DISMISS"));
      card.append(heading, text, confirm, dismiss);
      list.append(card);
    });
  } catch (exception) {
    showMessage($("#staff-error"), exception.message);
  }
}

$("#staff-tabs").addEventListener("click", async (event) => {
  const tab = event.target.closest(".tab");
  if (!tab || tab.hidden) return;
  document.querySelectorAll(".tab").forEach((item) => item.classList.toggle("active", item === tab));
  const patterns = tab.dataset.view === "patterns";
  $("#case-list").hidden = patterns;
  $("#pattern-list").hidden = !patterns;
  clearMessage($("#staff-error"));
  if (patterns) await loadPatterns();
});

$("#staff-logout").addEventListener("click", async () => {
  try {
    await api("/auth/logout", { method: "POST" });
    $("#staff-view").hidden = true;
  } catch (exception) {
    showMessage($("#staff-error"), exception.message);
    return;
  }
  csrfToken = "";
});

window.addEventListener("DOMContentLoaded", async () => {
  try {
    await refreshCsrf();
    const me = await api("/auth/me");
    if (me.role) await showStaff();
  } catch {
    // An unauthenticated visitor is expected; the session is created when needed.
  }
});
