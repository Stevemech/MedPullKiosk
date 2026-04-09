/**
 * popup.js — MedPull eClinicalWorks Assistant
 *
 * Two modes:
 *   1. Form Queue — polls server for pending/ready forms submitted by kiosk
 *   2. Manual Transcript — clinician pastes transcript directly
 *
 * Review panel and confirm flow are shared between both modes.
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
const errorMessage = document.getElementById("errorMessage");
const serverStatus = document.getElementById("serverStatus");
const refreshBtn = document.getElementById("refreshBtn");
const formQueue = document.getElementById("formQueue");

const settingsServerUrl = document.getElementById("settingsServerUrl");
const settingsApiKey = document.getElementById("settingsApiKey");
const saveSettingsBtn = document.getElementById("saveSettingsBtn");
const settingsSaved = document.getElementById("settingsSaved");

/* ── State ────────────────────────────────────────────────────── */
let currentDecisions = null;
let currentFormId = null;
let pollInterval = null;

/* ── Tab Switching ────────────────────────────────────────────── */
document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
    document.querySelectorAll(".tab-content").forEach((c) => c.classList.add("hidden"));
    tab.classList.add("active");
    document.getElementById(`tab-${tab.dataset.tab}`).classList.remove("hidden");
  });
});

/* ── Settings ─────────────────────────────────────────────────── */
chrome.storage.sync.get(["serverUrl", "apiKey"], (result) => {
  if (result.serverUrl) settingsServerUrl.value = result.serverUrl;
  if (result.apiKey) settingsApiKey.value = result.apiKey;
});

saveSettingsBtn.addEventListener("click", () => {
  chrome.storage.sync.set(
    {
      serverUrl: settingsServerUrl.value.trim(),
      apiKey: settingsApiKey.value.trim(),
    },
    () => {
      settingsSaved.classList.remove("hidden");
      setTimeout(() => settingsSaved.classList.add("hidden"), 2000);
      checkServerHealth();
    }
  );
});

/* ── Server Health Check ──────────────────────────────────────── */
async function getServerBase() {
  const result = await chrome.storage.sync.get("serverUrl");
  return (result.serverUrl || "https://YOUR_ALB_DOMAIN_HERE").replace(/\/+$/, "");
}

async function checkServerHealth() {
  try {
    const base = await getServerBase();
    const res = await fetch(`${base}/health`, {
      method: "GET",
      signal: AbortSignal.timeout(3000),
    });
    if (res.ok) {
      serverStatus.className = "status-badge connected";
      serverStatus.innerHTML = '<span class="status-dot"></span> Server Online';
      return true;
    }
  } catch {
    /* fall through */
  }
  serverStatus.className = "status-badge error";
  serverStatus.innerHTML = '<span class="status-dot"></span> Server Offline';
  return false;
}

checkServerHealth().then((online) => {
  updateProcessBtnState(online);
  if (online) loadFormQueue();
});

/* ── Input Handling (Manual tab) ──────────────────────────────── */
transcriptInput.addEventListener("input", () => updateProcessBtnState());

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

/* ── Form Queue ───────────────────────────────────────────────── */
async function loadFormQueue() {
  try {
    const response = await chrome.runtime.sendMessage({
      action: "getFormQueue",
      payload: { status: "pending,ready" },
    });

    if (response.error) {
      formQueue.innerHTML = `<div class="message error">${escapeHTML(response.error)}</div>`;
      return;
    }

    const forms = response.data.forms || [];
    renderFormQueue(forms);
  } catch (err) {
    formQueue.innerHTML = `<div class="message error">Failed to load queue: ${escapeHTML(err.message)}</div>`;
  }
}

function renderFormQueue(forms) {
  if (!forms || forms.length === 0) {
    formQueue.innerHTML = '<div class="message info">No pending forms. Forms submitted from the kiosk will appear here.</div>';
    return;
  }

  formQueue.innerHTML = "";
  for (const f of forms) {
    const card = document.createElement("div");
    card.className = "queue-card";

    const statusClass = f.status === "ready" ? "ready" : "pending";
    const createdDate = new Date(f.created_at).toLocaleString();

    card.innerHTML = `
      <div class="queue-card-header">
        <span class="queue-patient">Patient: ${escapeHTML(f.patient_id)}</span>
        <span class="decision-badge ${statusClass}">${f.status}</span>
      </div>
      <div class="queue-card-meta">
        <span>Source: ${escapeHTML(f.source)}</span>
        <span>${createdDate}</span>
      </div>
    `;

    if (f.status === "ready") {
      const btn = document.createElement("button");
      btn.className = "btn btn-primary btn-sm";
      btn.textContent = "Review Decisions";
      btn.addEventListener("click", () => loadFormForReview(f.form_id));
      card.appendChild(btn);
    } else {
      const btn = document.createElement("button");
      btn.className = "btn btn-primary btn-sm";
      btn.textContent = "Process Now";
      btn.addEventListener("click", () => processQueuedForm(f.form_id));
      card.appendChild(btn);
    }

    formQueue.appendChild(card);
  }
}

async function loadFormForReview(formId) {
  hideError();
  hideResult();
  try {
    const response = await chrome.runtime.sendMessage({
      action: "getForm",
      payload: { form_id: formId },
    });

    if (response.error) {
      showError(response.error);
      return;
    }

    currentFormId = formId;
    currentDecisions = {
      decisions: response.data.decisions || [],
      missing_required: response.data.missing_required || [],
    };
    renderReviewPanel(currentDecisions);
  } catch (err) {
    showError(`Failed to load form: ${err.message}`);
  }
}

async function processQueuedForm(formId) {
  hideError();
  hideResult();

  const btn = event.target;
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Processing…';

  try {
    const formSchema = await getFormSchemaFromTab();

    const response = await chrome.runtime.sendMessage({
      action: "processForm",
      payload: { form_id: formId, form_schema: formSchema },
    });

    if (response.error) {
      showError(response.error);
      return;
    }

    currentFormId = formId;
    currentDecisions = {
      decisions: response.data.decisions || [],
      missing_required: response.data.missing_required || [],
    };
    renderReviewPanel(currentDecisions);
  } catch (err) {
    showError(`Processing failed: ${err.message}`);
  } finally {
    btn.disabled = false;
    btn.textContent = "Process Now";
  }
}

refreshBtn.addEventListener("click", () => {
  formQueue.innerHTML = '<div class="message info">Refreshing…</div>';
  loadFormQueue();
});

/* Auto-poll every 30 seconds */
pollInterval = setInterval(() => {
  if (serverStatus.classList.contains("connected")) {
    loadFormQueue();
  }
}, 30000);

/* ── Process & Fill (Manual tab) ──────────────────────────────── */
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
    const formSchema = await getFormSchemaFromTab();

    /* Step 1: Submit the form */
    const submitResp = await chrome.runtime.sendMessage({
      action: "submitForm",
      payload: {
        patient_id: "manual-entry",
        transcript,
        source: "extension",
        form_schema: formSchema,
      },
    });

    if (submitResp.error) {
      showError(submitResp.error);
      return;
    }

    const formId = submitResp.data.form_id;
    currentFormId = formId;

    /* Step 2: Process via LLM */
    const processResp = await chrome.runtime.sendMessage({
      action: "processForm",
      payload: { form_id: formId, form_schema: formSchema },
    });

    if (processResp.error) {
      showError(processResp.error);
      return;
    }

    currentDecisions = {
      decisions: processResp.data.decisions || [],
      missing_required: processResp.data.missing_required || [],
    };
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
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) return [];

    const results = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => {
        if (typeof extractFormSchema === "function") return extractFormSchema();
        return [];
      },
    });
    return results?.[0]?.result || [];
  } catch {
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
    const confClass = confPct >= 75 ? "high" : confPct >= 50 ? "medium" : "low";

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

/* ── Confirm & Fill eClinicalWorks ────────────────────────────── */
confirmBtn.addEventListener("click", async () => {
  if (!currentDecisions || !currentFormId) return;

  confirmBtn.disabled = true;
  confirmBtn.innerHTML = '<span class="spinner"></span> Filling…';
  hideError();

  try {
    /* Step 1: Claim the form */
    const claimResp = await chrome.runtime.sendMessage({
      action: "claimForm",
      payload: { form_id: currentFormId },
    });

    if (claimResp.error) {
      showError(claimResp.error);
      return;
    }

    /* Step 2: Send decisions to content script for DOM filling */
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) {
      showError("No active tab found. Navigate to eClinicalWorks first.");
      return;
    }

    const fillResult = await chrome.tabs.sendMessage(tab.id, {
      action: "fillAndHighlight",
      decisions: currentDecisions.decisions,
    });

    if (fillResult && fillResult.success) {
      /* Step 3: Mark form as completed */
      await chrome.runtime.sendMessage({
        action: "completeForm",
        payload: { form_id: currentFormId },
      });

      showResult({ success: true, filled: fillResult.filled || 0 });
    } else {
      const errors = (fillResult && fillResult.errors) || ["Unknown fill error"];
      showResult({ success: false, errors });
    }
  } catch (err) {
    showError(`Fill failed: ${err.message}`);
  } finally {
    confirmBtn.disabled = false;
    confirmBtn.textContent = "Confirm & Fill eClinicalWorks";
  }
});

/* ── Show Execution Result ────────────────────────────────────── */
function showResult(result) {
  executionResult.classList.remove("hidden");

  if (result.success) {
    resultMessage.className = "message success";
    resultMessage.textContent = `Fields filled successfully (${result.filled || 0} fields). Please verify in eClinicalWorks before saving.`;
  } else {
    resultMessage.className = "message error";
    const errText = (result.errors || []).join("; ") || "Unknown error";
    resultMessage.textContent = `Some fields failed: ${errText}`;
  }
}

function hideResult() {
  executionResult.classList.add("hidden");
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
