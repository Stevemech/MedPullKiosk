/**
 * background.js — MedPull eClinicalWorks Assistant (Service Worker)
 *
 * Two responsibilities:
 *   1. API proxy — relays requests from popup/content to the MedPull server
 *   2. Autonomous orchestrator — processes form queue without supervision
 */

const DEFAULT_SERVER = "https://YOUR_ALB_DOMAIN_HERE";

/* ══════════════════════════════════════════════════════════════════
   Configuration helpers
   ══════════════════════════════════════════════════════════════════ */

async function getServerBase() {
  const result = await chrome.storage.sync.get("serverUrl");
  return (result.serverUrl || DEFAULT_SERVER).replace(/\/+$/, "");
}

async function getApiKey() {
  const result = await chrome.storage.sync.get("apiKey");
  return result.apiKey || "";
}

async function authHeaders() {
  const apiKey = await getApiKey();
  return { "Content-Type": "application/json", "X-API-Key": apiKey };
}

/* ══════════════════════════════════════════════════════════════════
   Fetch helpers
   ══════════════════════════════════════════════════════════════════ */

async function fetchWithTimeout(url, options, timeoutMs = 60000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (err) {
    if (err.name === "AbortError") {
      throw new Error("Request timed out. Check your server URL and network connection.");
    }
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

function formatError(err) {
  const msg = err.message || String(err);
  if (msg.includes("Failed to fetch") || msg.includes("NetworkError")) {
    return "Cannot reach the server. Check your Server URL and API Key in extension settings.";
  }
  if (msg.includes("timed out")) return msg;
  if (msg.includes("401")) return "Authentication failed. Check your API key in extension settings.";
  return `Server error: ${msg}`;
}

/* ══════════════════════════════════════════════════════════════════
   Message router — handles requests from popup and content scripts
   ══════════════════════════════════════════════════════════════════ */

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  const handler = MESSAGE_HANDLERS[message.action];
  if (!handler) return false;

  handler(message.payload)
    .then((data) => sendResponse({ data }))
    .catch((err) => sendResponse({ error: formatError(err) }));
  return true;
});

const MESSAGE_HANDLERS = {
  submitForm: handleSubmitForm,
  getFormQueue: handleGetFormQueue,
  getForm: handleGetForm,
  processForm: handleProcessForm,
  claimForm: handleClaimForm,
  completeForm: handleCompleteForm,
  startAutonomous: handleStartAutonomous,
  stopAutonomous: handleStopAutonomous,
  getAutonomousState: handleGetAutonomousState,
};

/* ══════════════════════════════════════════════════════════════════
   API handlers (original, preserved)
   ══════════════════════════════════════════════════════════════════ */

async function handleSubmitForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();
  const res = await fetchWithTimeout(`${base}/forms`, {
    method: "POST", headers,
    body: JSON.stringify({
      patient_id: payload.patient_id,
      transcript: payload.transcript,
      source: payload.source || "extension",
      form_schema: payload.form_schema || null,
    }),
  });
  if (!res.ok) { const body = await res.text().catch(() => ""); throw new Error(`Server returned ${res.status}: ${body || res.statusText}`); }
  return res.json();
}

async function handleGetFormQueue(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();
  const status = (payload && payload.status) || "pending,ready";
  const res = await fetchWithTimeout(`${base}/forms?status=${encodeURIComponent(status)}`, { method: "GET", headers });
  if (!res.ok) { const body = await res.text().catch(() => ""); throw new Error(`Server returned ${res.status}: ${body || res.statusText}`); }
  return res.json();
}

async function handleGetForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();
  const res = await fetchWithTimeout(`${base}/forms/${payload.form_id}`, { method: "GET", headers });
  if (!res.ok) { const body = await res.text().catch(() => ""); throw new Error(`Server returned ${res.status}: ${body || res.statusText}`); }
  return res.json();
}

async function handleProcessForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();
  const res = await fetchWithTimeout(`${base}/forms/${payload.form_id}/process`, {
    method: "POST", headers,
    body: JSON.stringify({ form_schema: payload.form_schema || [] }),
  });
  if (!res.ok) { const body = await res.text().catch(() => ""); throw new Error(`Server returned ${res.status}: ${body || res.statusText}`); }
  return res.json();
}

async function handleClaimForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();
  const res = await fetchWithTimeout(`${base}/forms/${payload.form_id}/claim`, { method: "POST", headers });
  if (!res.ok) { const body = await res.text().catch(() => ""); throw new Error(`Server returned ${res.status}: ${body || res.statusText}`); }
  return res.json();
}

async function handleCompleteForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();
  const res = await fetchWithTimeout(`${base}/forms/${payload.form_id}/complete`, { method: "POST", headers });
  if (!res.ok) { const body = await res.text().catch(() => ""); throw new Error(`Server returned ${res.status}: ${body || res.statusText}`); }
  return res.json();
}

/* ══════════════════════════════════════════════════════════════════
   Navigate API — asks Grok what to do on the current page
   ══════════════════════════════════════════════════════════════════ */

async function callNavigateAPI(goal, snapshot, history, patientContext) {
  const base = await getServerBase();
  const headers = await authHeaders();

  const res = await fetchWithTimeout(`${base}/navigate`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      goal,
      page_snapshot: snapshot,
      history: history || [],
      patient_context: patientContext || null,
    }),
  }, 90000);

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Navigate API returned ${res.status}: ${body || res.statusText}`);
  }
  return res.json();
}

/* ══════════════════════════════════════════════════════════════════
   Autonomous Orchestrator
   ══════════════════════════════════════════════════════════════════ */

const MAX_ACTIONS_PER_GOAL = 15;
const MAX_RETRIES_PER_FORM = 2;
const WAIT_AFTER_ACTION_MS = 1500;
const WAIT_AFTER_NAV_MS = 3000;

let autonomousRunning = false;
let autonomousAbort = false;

function defaultState() {
  return {
    running: false,
    currentForm: null,
    formIndex: 0,
    totalForms: 0,
    step: "idle",
    log: [],
  };
}

async function saveState(updates) {
  const prev = (await chrome.storage.session.get("autoState")).autoState || defaultState();
  const next = { ...prev, ...updates };
  await chrome.storage.session.set({ autoState: next });
  return next;
}

async function appendLog(text, level = "info") {
  const state = (await chrome.storage.session.get("autoState")).autoState || defaultState();
  const entry = { ts: Date.now(), text, level };
  state.log = [...state.log.slice(-200), entry];
  await chrome.storage.session.set({ autoState: state });
}

async function handleStartAutonomous() {
  if (autonomousRunning) return { status: "already_running" };
  autonomousAbort = false;
  autonomousRunning = true;

  chrome.alarms.create("keepAlive", { periodInMinutes: 0.4 });
  await saveState({ running: true, log: [], step: "starting" });
  await appendLog("Autonomous mode started");

  runAutonomousLoop().finally(() => {
    autonomousRunning = false;
    chrome.alarms.clear("keepAlive");
    saveState({ running: false, step: "idle", currentForm: null });
    appendLog("Autonomous mode stopped");
  });

  return { status: "started" };
}

async function handleStopAutonomous() {
  autonomousAbort = true;
  await appendLog("Stop requested — finishing current action…", "warn");
  return { status: "stopping" };
}

async function handleGetAutonomousState() {
  return (await chrome.storage.session.get("autoState")).autoState || defaultState();
}

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "keepAlive" && autonomousRunning) { /* noop — keeps SW alive */ }
});

/* ── Main loop ─────────────────────────────────────────────────── */

async function runAutonomousLoop() {
  try {
    await appendLog("Fetching form queue from server…");
    const queueResp = await handleGetFormQueue({ status: "pending,ready" });
    const forms = queueResp.forms || [];

    if (forms.length === 0) {
      await appendLog("No pending forms in queue. Done.");
      return;
    }

    await appendLog(`Found ${forms.length} form(s) to process`);
    await saveState({ totalForms: forms.length });

    for (let i = 0; i < forms.length; i++) {
      if (autonomousAbort) { await appendLog("Aborted by user", "warn"); return; }

      const form = forms[i];
      await saveState({ formIndex: i + 1, currentForm: form.patient_id, step: "claiming" });
      await appendLog(`\n── Form ${i + 1}/${forms.length}: patient "${form.patient_id}" ──`);

      let success = false;
      for (let attempt = 0; attempt <= MAX_RETRIES_PER_FORM; attempt++) {
        if (autonomousAbort) return;
        try {
          if (attempt > 0) await appendLog(`Retry attempt ${attempt}…`, "warn");
          await processOneForm(form);
          success = true;
          break;
        } catch (err) {
          await appendLog(`Error: ${err.message}`, "error");
          if (attempt === MAX_RETRIES_PER_FORM) {
            await appendLog(`Giving up on form ${form.form_id} after ${MAX_RETRIES_PER_FORM + 1} attempts`, "error");
          }
        }
      }

      if (!success) {
        try {
          const base = await getServerBase();
          const headers = await authHeaders();
          await fetchWithTimeout(`${base}/forms/${form.form_id}/complete`, { method: "POST", headers });
        } catch { /* best-effort mark */ }
      }
    }

    await appendLog("\nAll forms processed.");
  } catch (err) {
    await appendLog(`Fatal error: ${err.message}`, "error");
  }
}

/* ── Process a single form ─────────────────────────────────────── */

async function processOneForm(form) {
  const tabId = await getEcwTabId();
  if (!tabId) throw new Error("No eClinicalWorks tab found. Open eCW and try again.");

  const patientCtx = {
    patient_id: form.patient_id,
    transcript_excerpt: (form.transcript || "").slice(0, 300),
  };

  /* Step 1: Claim the form */
  await saveState({ step: "claiming" });
  try {
    await handleClaimForm({ form_id: form.form_id });
    await appendLog("Form claimed");
  } catch (err) {
    if (err.message.includes("409")) {
      await appendLog("Form already claimed by another session, skipping", "warn");
      return;
    }
    throw err;
  }

  /* Step 2: Navigate — search for patient */
  await saveState({ step: "navigating" });
  await appendLog(`Navigating to search for patient "${form.patient_id}"…`);

  const searchResult = await navigateWithGoal(
    tabId,
    `Search for patient named "${form.patient_id}" using the patient search. ` +
    `Type the name in the search field and submit the search.`,
    patientCtx,
  );

  if (!searchResult.success) {
    throw new Error(`Navigation failed during patient search: ${searchResult.error}`);
  }

  /* Step 3: Open patient chart (or create patient) */
  await appendLog(`Opening chart for "${form.patient_id}"…`);

  const openResult = await navigateWithGoal(
    tabId,
    `Find patient "${form.patient_id}" in the search results and open their chart. ` +
    `Click on the patient's name or row to open their record. ` +
    `If the patient is not found, look for an "Add Patient" or "New Patient" button.`,
    patientCtx,
  );

  if (!openResult.success) {
    await appendLog("Patient not found in results. Attempting to create…", "warn");
    const createResult = await navigateWithGoal(
      tabId,
      `Create a new patient named "${form.patient_id}". ` +
      `Click "Add Patient" or "New Patient", fill in the name, and save.`,
      patientCtx,
    );
    if (!createResult.success) {
      throw new Error(`Could not find or create patient "${form.patient_id}"`);
    }
    await appendLog("New patient created");
  }

  await appendLog("Patient chart opened");
  await sleep(WAIT_AFTER_NAV_MS);

  /* Step 4: Extract form schema from current page */
  await saveState({ step: "extracting" });
  await appendLog("Extracting form fields from page…");

  const schemaResp = await sendToTab(tabId, { action: "extractSchema" });
  const formSchema = schemaResp?.schema || [];

  if (formSchema.length === 0) {
    await appendLog("No form fields found on this page — trying to find the form page…", "warn");

    const formNavResult = await navigateWithGoal(
      tabId,
      `Navigate to the clinical encounter form or patient intake form where data entry fields are located. ` +
      `Look for tabs or links like "Encounter", "Progress Note", "Demographics", "Intake", or "New Visit".`,
      patientCtx,
    );

    if (formNavResult.success) {
      await sleep(WAIT_AFTER_NAV_MS);
      const retry = await sendToTab(tabId, { action: "extractSchema" });
      formSchema.push(...(retry?.schema || []));
    }
  }

  await appendLog(`Found ${formSchema.length} form fields`);

  if (formSchema.length === 0) {
    throw new Error("No fillable form fields found on the patient's page");
  }

  /* Step 5: Process transcript with LLM */
  await saveState({ step: "processing" });
  await appendLog("Sending transcript to Grok for field mapping…");

  const processResp = await handleProcessForm({
    form_id: form.form_id,
    form_schema: formSchema,
  });

  const decisions = processResp.decisions || [];
  const fillDecisions = decisions.filter((d) => d.action === "fill");
  await appendLog(
    `Grok returned ${decisions.length} decisions (${fillDecisions.length} fills, ` +
    `${decisions.filter((d) => d.action === "skip").length} skips, ` +
    `${decisions.filter((d) => d.action === "flag").length} flags)`
  );

  /* Step 6: Fill fields */
  await saveState({ step: "filling" });
  await appendLog("Filling form fields…");

  const fillResult = await sendToTab(tabId, {
    action: "fillAndHighlight",
    decisions,
  });

  if (fillResult && fillResult.success) {
    await appendLog(`Filled ${fillResult.filled} fields successfully`);
  } else {
    const errs = fillResult?.errors || [];
    if (errs.length > 0) {
      await appendLog(`Fill warnings: ${errs.join("; ")}`, "warn");
    }
  }

  /* Step 7: Auto-save (if enabled) */
  const settings = await chrome.storage.sync.get("autoSave");
  if (settings.autoSave) {
    await saveState({ step: "saving" });
    await appendLog("Auto-save enabled — looking for Save button…");

    const saveResult = await navigateWithGoal(
      tabId,
      "Click the Save, Submit, or Sign button to save the current form/encounter.",
      patientCtx,
    );

    if (saveResult.success) {
      await appendLog("Form saved in eCW");
    } else {
      await appendLog("Could not find Save button — form is filled but unsaved", "warn");
    }
  } else {
    await appendLog("Auto-save disabled — fields filled but not saved");
  }

  /* Step 8: Mark complete */
  await saveState({ step: "completing" });
  await handleCompleteForm({ form_id: form.form_id });
  await appendLog("Form marked complete in queue");
}

/* ── Navigation loop — Grok-driven step execution ──────────────── */

async function navigateWithGoal(tabId, goal, patientContext) {
  const history = [];

  for (let i = 0; i < MAX_ACTIONS_PER_GOAL; i++) {
    if (autonomousAbort) return { success: false, error: "Aborted" };

    const snapResp = await sendToTab(tabId, { action: "getPageSnapshot" });
    if (!snapResp || !snapResp.snapshot) {
      await sleep(2000);
      continue;
    }

    let navResp;
    try {
      navResp = await callNavigateAPI(goal, snapResp.snapshot, history, patientContext);
    } catch (err) {
      await appendLog(`Navigate API error: ${err.message}`, "error");
      return { success: false, error: err.message };
    }

    const action = navResp.action;

    if (action.confidence < 0.3) {
      await appendLog(`Low confidence action (${action.confidence}) — skipping: ${action.reasoning}`, "warn");
      return { success: false, error: "Grok confidence too low" };
    }

    if (action.done) {
      await appendLog(`Goal achieved: ${action.reasoning}`);
      return { success: true };
    }

    if (action.action === "error") {
      await appendLog(`Navigation error: ${action.reasoning}`, "warn");
      return { success: false, error: action.reasoning };
    }

    await appendLog(`  → ${action.action} ${action.element_index != null ? "element " + action.element_index : ""} ${action.value ? '"' + action.value + '"' : ""}`);

    if (action.action === "wait") {
      await sleep(WAIT_AFTER_NAV_MS);
      history.push({ action: "wait", outcome: "waited" });
      continue;
    }

    const execResult = await sendToTab(tabId, {
      action: "executeAction",
      navAction: {
        action: action.action,
        element_index: action.element_index,
        value: action.value,
      },
    });

    const outcome = execResult?.success ? "ok" : (execResult?.error || "failed");
    history.push({
      action: action.action,
      element_index: action.element_index,
      value: action.value || null,
      outcome,
    });

    if (!execResult?.success) {
      await appendLog(`Action failed: ${execResult?.error}`, "warn");
    }

    await sendToTab(tabId, { action: "waitForStable", timeout: 4000 });
    await sleep(WAIT_AFTER_ACTION_MS);
  }

  return { success: false, error: `Exceeded ${MAX_ACTIONS_PER_GOAL} actions without reaching goal` };
}

/* ── Tab helpers ───────────────────────────────────────────────── */

async function getEcwTabId() {
  const tabs = await chrome.tabs.query({});
  const ecwTab = tabs.find(
    (t) =>
      t.url &&
      (t.url.includes("eclinicalworks.com") || t.url.includes("ecw.com"))
  );
  return ecwTab ? ecwTab.id : null;
}

async function sendToTab(tabId, message) {
  try {
    return await chrome.tabs.sendMessage(tabId, message);
  } catch (err) {
    if (err.message.includes("Could not establish connection") ||
        err.message.includes("Receiving end does not exist")) {
      await sleep(2000);
      try {
        await chrome.scripting.executeScript({
          target: { tabId },
          files: ["content.js"],
        });
        await sleep(500);
        return await chrome.tabs.sendMessage(tabId, message);
      } catch { /* give up */ }
    }
    return null;
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
