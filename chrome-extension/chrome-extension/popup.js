/**
 * popup.js — MedPull eClinicalWorks Assistant
 *
 * Three tabs:
 *   1. Autonomous — start/stop the auto-processing loop, live log
 *   2. Form Queue — poll server for pending/ready forms (manual review)
 *   3. Manual Transcript — paste transcript directly
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
const settingsAutoSave = document.getElementById("settingsAutoSave");
const saveSettingsBtn = document.getElementById("saveSettingsBtn");
const settingsSaved = document.getElementById("settingsSaved");

const startAutoBtn = document.getElementById("startAutoBtn");
const stopAutoBtn = document.getElementById("stopAutoBtn");
const autoStatus = document.getElementById("autoStatus");
const autoLog = document.getElementById("autoLog");
const autoProgressFill = document.getElementById("autoProgressFill");
const autoProgressText = document.getElementById("autoProgressText");
const clearLogBtn = document.getElementById("clearLogBtn");

/* ── State ────────────────────────────────────────────────────── */
let currentDecisions = null;
let currentFormId = null;
let pollInterval = null;
let logPollInterval = null;
let lastLogLength = 0;

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
chrome.storage.sync.get(["serverUrl", "apiKey", "autoSave"], (result) => {
  if (result.serverUrl) settingsServerUrl.value = result.serverUrl;
  if (result.apiKey) settingsApiKey.value = result.apiKey;
  settingsAutoSave.checked = !!result.autoSave;
});

saveSettingsBtn.addEventListener("click", () => {
  chrome.storage.sync.set(
    {
      serverUrl: settingsServerUrl.value.trim(),
      apiKey: settingsApiKey.value.trim(),
      autoSave: settingsAutoSave.checked,
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
  } catch { /* fall through */ }
  serverStatus.className = "status-badge error";
  serverStatus.innerHTML = '<span class="status-dot"></span> Server Offline';
  return false;
}

checkServerHealth().then((online) => {
  updateProcessBtnState(online);
  if (online) loadFormQueue();
});

/* ══════════════════════════════════════════════════════════════════
   Autonomous Mode
   ══════════════════════════════════════════════════════════════════ */

startAutoBtn.addEventListener("click", async () => {
  startAutoBtn.disabled = true;
  startAutoBtn.innerHTML = '<span class="spinner"></span> Starting…';

  const resp = await chrome.runtime.sendMessage({
    action: "startAutonomous",
    payload: {},
  });

  if (resp?.data?.status === "already_running") {
    startAutoBtn.disabled = false;
    startAutoBtn.textContent = "Start Processing";
  }

  startLogPolling();
});

stopAutoBtn.addEventListener("click", async () => {
  stopAutoBtn.disabled = true;
  await chrome.runtime.sendMessage({ action: "stopAutonomous", payload: {} });
});

clearLogBtn.addEventListener("click", () => {
  autoLog.innerHTML = '<div class="log-empty">Log cleared.</div>';
  lastLogLength = 0;
});

function startLogPolling() {
  if (logPollInterval) clearInterval(logPollInterval);
  lastLogLength = 0;
  logPollInterval = setInterval(refreshAutoState, 800);
  refreshAutoState();
}

async function refreshAutoState() {
  const resp = await chrome.runtime.sendMessage({
    action: "getAutonomousState",
    payload: {},
  });
  const state = resp?.data;
  if (!state) return;

  if (state.running) {
    startAutoBtn.classList.add("hidden");
    stopAutoBtn.classList.remove("hidden");
    stopAutoBtn.disabled = false;
    autoStatus.textContent = capitalize(state.step || "running");
    autoStatus.className = "auto-status-badge running";
  } else {
    startAutoBtn.classList.remove("hidden");
    startAutoBtn.disabled = false;
    startAutoBtn.textContent = "Start Processing";
    stopAutoBtn.classList.add("hidden");
    autoStatus.textContent = "Idle";
    autoStatus.className = "auto-status-badge idle";

    if (logPollInterval) {
      clearInterval(logPollInterval);
      logPollInterval = null;
    }
  }

  if (state.totalForms > 0) {
    const pct = Math.round((state.formIndex / state.totalForms) * 100);
    autoProgressFill.style.width = `${pct}%`;
    autoProgressText.textContent = `${state.formIndex} / ${state.totalForms} forms`;
  }

  if (state.log && state.log.length > lastLogLength) {
    const newEntries = state.log.slice(lastLogLength);
    lastLogLength = state.log.length;

    if (autoLog.querySelector(".log-empty")) autoLog.innerHTML = "";

    for (const entry of newEntries) {
      const div = document.createElement("div");
      div.className = `log-entry log-${entry.level || "info"}`;
      const time = new Date(entry.ts).toLocaleTimeString();
      div.innerHTML = `<span class="log-time">${time}</span> ${escapeHTML(entry.text)}`;
      autoLog.appendChild(div);
    }
    autoLog.scrollTop = autoLog.scrollHeight;
  }
}

chrome.runtime.sendMessage({ action: "getAutonomousState", payload: {} }).then((resp) => {
  if (resp?.data?.running) startLogPolling();
});

/* ══════════════════════════════════════════════════════════════════
   Manual Input Handling
   ══════════════════════════════════════════════════════════════════ */

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
    serverOnline !== undefined ? serverOnline : serverStatus.classList.contains("connected");
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
    renderFormQueue(response.data.forms || []);
  } catch (err) {
    formQueue.innerHTML = `<div class="message error">Failed to load queue: ${escapeHTML(err.message)}</div>`;
  }
}

function renderFormQueue(forms) {
  if (!forms || forms.length === 0) {
    formQueue.innerHTML = '<div class="message info">No pending forms.</div>';
    return;
  }
  formQueue.innerHTML = "";
  for (const f of forms) {
    const card = document.createElement("div");
    card.className = "queue-card";
    const statusClass = f.status === "ready" ? "ready" : "pending";
    card.innerHTML = `
      <div class="queue-card-header">
        <span class="queue-patient">Patient: ${escapeHTML(f.patient_id)}</span>
        <span class="decision-badge ${statusClass}">${f.status}</span>
      </div>
      <div class="queue-card-meta">
        <span>Source: ${escapeHTML(f.source)}</span>
        <span>${new Date(f.created_at).toLocaleString()}</span>
      </div>`;
    formQueue.appendChild(card);
  }
}

refreshBtn.addEventListener("click", () => {
  formQueue.innerHTML = '<div class="message info">Refreshing…</div>';
  loadFormQueue();
});

pollInterval = setInterval(() => {
  if (serverStatus.classList.contains("connected")) loadFormQueue();
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
    const submitResp = await chrome.runtime.sendMessage({
      action: "submitForm",
      payload: { patient_id: "manual-entry", transcript, source: "extension", form_schema: formSchema },
    });
    if (submitResp.error) { showError(submitResp.error); return; }

    const formId = submitResp.data.form_id;
    currentFormId = formId;

    const processResp = await chrome.runtime.sendMessage({
      action: "processForm",
      payload: { form_id: formId, form_schema: formSchema },
    });
    if (processResp.error) { showError(processResp.error); return; }

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

async function getFormSchemaFromTab() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) return [];
    const results = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => typeof extractFormSchema === "function" ? extractFormSchema() : [],
    });
    return results?.[0]?.result || [];
  } catch { return []; }
}

/* ── Render Review Panel ──────────────────────────────────────── */
function renderReviewPanel(data) {
  reviewPanel.innerHTML = "";
  if (!data || !data.decisions || data.decisions.length === 0) {
    reviewPanel.innerHTML = '<div class="message info">No field decisions returned.</div>';
    reviewSection.classList.remove("hidden");
    return;
  }
  for (const d of data.decisions) {
    const card = document.createElement("div");
    card.className = `decision-card ${d.action}`;
    const confPct = Math.round((d.confidence || 0) * 100);
    const confClass = confPct >= 75 ? "high" : confPct >= 50 ? "medium" : "low";
    let valueHTML = "";
    if (d.action === "fill" && d.value !== null)
      valueHTML = `<div class="decision-value"><strong>Value:</strong> ${escapeHTML(d.value)}</div>`;
    card.innerHTML = `
      <div class="decision-header">
        <span class="decision-label">${escapeHTML(d.label || d.field_id)}</span>
        <span class="decision-badge ${d.action}">${d.action}</span>
      </div>
      ${valueHTML}
      <div class="decision-reasoning">${escapeHTML(d.reasoning)}</div>
      <div class="confidence-bar"><div class="confidence-fill ${confClass}" style="width:${confPct}%"></div></div>`;
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
    await chrome.runtime.sendMessage({ action: "claimForm", payload: { form_id: currentFormId } });
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) { showError("No active tab found."); return; }
    const fillResult = await chrome.tabs.sendMessage(tab.id, {
      action: "fillAndHighlight",
      decisions: currentDecisions.decisions,
    });
    if (fillResult && fillResult.success) {
      await chrome.runtime.sendMessage({ action: "completeForm", payload: { form_id: currentFormId } });
      showResult({ success: true, filled: fillResult.filled || 0 });
    } else {
      showResult({ success: false, errors: fillResult?.errors || ["Unknown error"] });
    }
  } catch (err) {
    showError(`Fill failed: ${err.message}`);
  } finally {
    confirmBtn.disabled = false;
    confirmBtn.textContent = "Confirm & Fill eClinicalWorks";
  }
});

/* ── UI Helpers ───────────────────────────────────────────────── */
function showResult(result) {
  executionResult.classList.remove("hidden");
  if (result.success) {
    resultMessage.className = "message success";
    resultMessage.textContent = `Fields filled successfully (${result.filled || 0} fields). Verify in eCW before saving.`;
  } else {
    resultMessage.className = "message error";
    resultMessage.textContent = `Some fields failed: ${(result.errors || []).join("; ")}`;
  }
}
function hideResult() { executionResult.classList.add("hidden"); }
function showError(msg) { errorMessage.textContent = msg; errorMessage.classList.remove("hidden"); }
function hideError() { errorMessage.classList.add("hidden"); }
function escapeHTML(str) { const d = document.createElement("div"); d.textContent = str || ""; return d.innerHTML; }
function capitalize(s) { return s ? s.charAt(0).toUpperCase() + s.slice(1).replace(/_/g, " ") : ""; }
