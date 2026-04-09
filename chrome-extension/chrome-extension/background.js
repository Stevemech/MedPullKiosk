/**
 * background.js — MedPull eClinicalWorks Assistant (Service Worker)
 *
 * Proxies requests from the popup to the MedPull API server.
 * Server URL and API key are configurable via chrome.storage.sync.
 */

const DEFAULT_SERVER = "https://YOUR_ALB_DOMAIN_HERE";

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
  return {
    "Content-Type": "application/json",
    "X-API-Key": apiKey,
  };
}

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
};

/**
 * POST /forms — submit a new form (transcript + optional schema).
 */
async function handleSubmitForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();

  const res = await fetchWithTimeout(`${base}/forms`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      patient_id: payload.patient_id,
      transcript: payload.transcript,
      source: payload.source || "extension",
      form_schema: payload.form_schema || null,
    }),
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Server returned ${res.status}: ${body || res.statusText}`);
  }
  return res.json();
}

/**
 * GET /forms — poll for pending/ready forms.
 */
async function handleGetFormQueue(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();
  const status = (payload && payload.status) || "pending,ready";

  const res = await fetchWithTimeout(`${base}/forms?status=${encodeURIComponent(status)}`, {
    method: "GET",
    headers,
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Server returned ${res.status}: ${body || res.statusText}`);
  }
  return res.json();
}

/**
 * GET /forms/{form_id} — get a single form.
 */
async function handleGetForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();

  const res = await fetchWithTimeout(`${base}/forms/${payload.form_id}`, {
    method: "GET",
    headers,
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Server returned ${res.status}: ${body || res.statusText}`);
  }
  return res.json();
}

/**
 * POST /forms/{form_id}/process — trigger LLM processing on a form.
 */
async function handleProcessForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();

  const res = await fetchWithTimeout(`${base}/forms/${payload.form_id}/process`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      form_schema: payload.form_schema || [],
    }),
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Server returned ${res.status}: ${body || res.statusText}`);
  }
  return res.json();
}

/**
 * POST /forms/{form_id}/claim — claim a form before filling eCW.
 */
async function handleClaimForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();

  const res = await fetchWithTimeout(`${base}/forms/${payload.form_id}/claim`, {
    method: "POST",
    headers,
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Server returned ${res.status}: ${body || res.statusText}`);
  }
  return res.json();
}

/**
 * POST /forms/{form_id}/complete — mark a form as completed.
 */
async function handleCompleteForm(payload) {
  const base = await getServerBase();
  const headers = await authHeaders();

  const res = await fetchWithTimeout(`${base}/forms/${payload.form_id}/complete`, {
    method: "POST",
    headers,
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Server returned ${res.status}: ${body || res.statusText}`);
  }
  return res.json();
}

/**
 * Fetch wrapper with a 60-second timeout.
 */
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

/**
 * Convert raw errors into user-friendly messages.
 */
function formatError(err) {
  const msg = err.message || String(err);

  if (msg.includes("Failed to fetch") || msg.includes("NetworkError")) {
    return "Cannot reach the server. Check your Server URL and API Key in extension settings.";
  }

  if (msg.includes("timed out")) return msg;
  if (msg.includes("401")) return "Authentication failed. Check your API key in extension settings.";

  return `Server error: ${msg}`;
}
