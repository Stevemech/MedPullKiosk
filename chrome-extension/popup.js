/**
 * popup.js — MedPull eClinicalWorks Assistant
 *
 * Handles transcript input, communicates with the background service worker,
 * renders the review panel, and gates clinician confirmation before execution.
 */

/* ── DOM References ───────────────────────────────────────────── */
const transcriptInput = document.getElementById("transcriptInput");
const fileUpload = document.getElementById("fileUpload");
const fileNameDisplay = document.getElementById("fileName");
const processBtn = document.getElementById("processBtn");
const reviewSection = document.getElementById("reviewSection");
const reviewPanel = document.getElementById("reviewPanel");
const missingRequired = document.getElementById("missingRequired");
const missingList = document.getElementById("missingList");
const confirmBtn = document.getElementById("confirmBtn");
const executionResult = document.getElementById("executionResult");
const resultMessage = document.getElementById("resultMessage");
const screenshotPreview = document.getElementById("screenshotPreview");
const errorMessage = document.getElementById("errorMessage");
const serverStatus = document.getElementById("serverStatus");

/* ── State ────────────────────────────────────────────────────── */
let currentDecisions = null;

/* ── Server Health Check ──────────────────────────────────────── */
async function checkServerHealth() {
  try {
    const res = await fetch("http://localhost:8000/health", {
      method: "GET",
      signal: AbortSignal.timeout(3000),
    });
    if (res.ok) {
      serverStatus.className = "status-badge connected";
      serverStatus.innerHTML =
        '<span class="status-dot"></span> Server Online';
      return true;
    }
  } catch {
    /* fall through */
  }
  serverStatus.className = "status-badge error";
  serverStatus.innerHTML = '<span class="status-dot"></span> Server Offline';
  return false;
}

/* Run health check on load and enable the process button accordingly */
checkServerHealth().then((online) => {
  updateProcessBtnState(online);
});

/* ── Input Handling ───────────────────────────────────────────── */
transcriptInput.addEventListener("input", () => {
  updateProcessBtnState();
});

fileUpload.addEventListener("change", (e) => {
  const file = e.target.files[0];
  if (file) {
    fileNameDisplay.textContent = file.name;
    const reader = new FileReader();
    reader.onload = (ev) => {
      transcriptInput.value = ev.target.result;
      updateProcessBtnState();
    };
    reader.readAsText(file);
  }
});

function updateProcessBtnState(serverOnline) {
  const hasText = transcriptInput.value.trim().length > 0;
  const online =
    serverOnline !== undefined
      ? serverOnline
      : serverStatus.classList.contains("connected");
  processBtn.disabled = !(hasText && online);
}

/* ── Process & Fill ───────────────────────────────────────────── */
processBtn.addEventListener("click", async () => {
  hideError();
  hideResult();
  reviewSection.classList.add("hidden");
  confirmBtn.classList.add("hidden");
  confirmBtn.disabled = true;

  const transcript = transcriptInput.value.trim();
  if (!transcript) return;

  processBtn.disabled = true;
  processBtn.innerHTML = '<span class="spinner"></span> Processing…';

  try {
    /* Ask content script for the form schema from the active eCW tab */
    const formSchema = await getFormSchemaFromTab();

    /* Send to background → server */
    const response = await chrome.runtime.sendMessage({
      action: "processForm",
      payload: { transcript, form_schema: formSchema },
    });

    if (response.error) {
      showError(response.error);
      return;
    }

    currentDecisions = response.data;
    renderReviewPanel(currentDecisions);
  } catch (err) {
    showError(`Processing failed: ${err.message}`);
  } finally {
    processBtn.disabled = false;
    processBtn.textContent = "Process & Fill";
    updateProcessBtnState();
  }
});

/* ── Get Form Schema from Active Tab ──────────────────────────── */
async function getFormSchemaFromTab() {
  try {
    const [tab] = await chrome.tabs.query({
      active: true,
      currentWindow: true,
    });
    if (!tab) return [];

    const results = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => {
        if (typeof extractFormSchema === "function") {
          return extractFormSchema();
        }
        return [];
      },
    });

    return results?.[0]?.result || [];
  } catch {
    /* Not on an eCW page or content script not injected — return empty */
    return [];
  }
}

/* ── Render Review Panel ──────────────────────────────────────── */
function renderReviewPanel(data) {
  reviewPanel.innerHTML = "";

  if (!data || !data.decisions || data.decisions.length === 0) {
    reviewPanel.innerHTML =
      '<div class="message info">No field decisions returned. Verify the transcript and form schema.</div>';
    reviewSection.classList.remove("hidden");
    return;
  }

  for (const d of data.decisions) {
    const card = document.createElement("div");
    card.className = `decision-card ${d.action}`;

    const confPct = Math.round((d.confidence || 0) * 100);
    const confClass =
      confPct >= 75 ? "high" : confPct >= 50 ? "medium" : "low";

    let valueHTML = "";
    if (d.action === "fill" && d.value !== null) {
      valueHTML = `<div class="decision-value"><strong>Value:</strong> ${escapeHTML(d.value)}</div>`;
    }

    card.innerHTML = `
      <div class="decision-header">
        <span class="decision-label">${escapeHTML(d.label || d.field_id)}</span>
        <span class="decision-badge ${d.action}">${d.action}</span>
      </div>
      ${valueHTML}
      <div class="decision-reasoning">${escapeHTML(d.reasoning)}</div>
      <div class="confidence-bar">
        <div class="confidence-fill ${confClass}" style="width: ${confPct}%"></div>
      </div>
    `;

    reviewPanel.appendChild(card);
  }

  /* Missing required fields */
  if (data.missing_required && data.missing_required.length > 0) {
    missingList.innerHTML = "";
    for (const field of data.missing_required) {
      const li = document.createElement("li");
      li.textContent = field;
      missingList.appendChild(li);
    }
    missingRequired.classList.remove("hidden");
  } else {
    missingRequired.classList.add("hidden");
  }

  reviewSection.classList.remove("hidden");
  confirmBtn.classList.remove("hidden");
  confirmBtn.disabled = false;
}

/* ── Confirm & Submit ─────────────────────────────────────────── */
confirmBtn.addEventListener("click", async () => {
  if (!currentDecisions) return;

  confirmBtn.disabled = true;
  confirmBtn.innerHTML = '<span class="spinner"></span> Submitting…';
  hideError();

  try {
    const response = await chrome.runtime.sendMessage({
      action: "executeForm",
      payload: { decisions: currentDecisions.decisions },
    });

    if (response.error) {
      showError(response.error);
      return;
    }

    const result = response.data;
    showResult(result);
  } catch (err) {
    showError(`Execution failed: ${err.message}`);
  } finally {
    confirmBtn.disabled = false;
    confirmBtn.textContent = "Confirm & Submit to eClinicalWorks";
  }
});

/* ── Show Execution Result ────────────────────────────────────── */
function showResult(result) {
  executionResult.classList.remove("hidden");

  if (result.success) {
    resultMessage.className = "message success";
    resultMessage.textContent =
      "Fields filled successfully. Please verify in eClinicalWorks before saving.";
  } else {
    resultMessage.className = "message error";
    const errText = (result.errors || []).join("; ") || "Unknown error";
    resultMessage.textContent = `Some fields failed: ${errText}`;
  }

  if (result.screenshot_base64) {
    screenshotPreview.innerHTML = `<img src="data:image/png;base64,${result.screenshot_base64}" alt="Post-fill screenshot" />`;
    screenshotPreview.classList.remove("hidden");
  }
}

function hideResult() {
  executionResult.classList.add("hidden");
  screenshotPreview.classList.add("hidden");
}

/* ── Error Helpers ────────────────────────────────────────────── */
function showError(msg) {
  errorMessage.textContent = msg;
  errorMessage.classList.remove("hidden");
}

function hideError() {
  errorMessage.classList.add("hidden");
}

/* ── Utilities ────────────────────────────────────────────────── */
function escapeHTML(str) {
  const div = document.createElement("div");
  div.textContent = str || "";
  return div.innerHTML;
}
