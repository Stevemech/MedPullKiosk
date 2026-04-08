/**
 * background.js — MedPull eClinicalWorks Assistant (Service Worker)
 *
 * Listens for messages from the popup and proxies requests to the local
 * FastAPI server at http://localhost:8000. Handles errors gracefully and
 * returns user-facing error messages.
 */

const SERVER_BASE = "http://localhost:8000";

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.action === "processForm") {
    handleProcessForm(message.payload)
      .then((data) => sendResponse({ data }))
      .catch((err) => sendResponse({ error: formatError(err) }));
    return true; // keep the message channel open for async response
  }

  if (message.action === "executeForm") {
    handleExecuteForm(message.payload)
      .then((data) => sendResponse({ data }))
      .catch((err) => sendResponse({ error: formatError(err) }));
    return true;
  }

  return false;
});

/**
 * POST /process — send transcript + form_schema to the server for LLM processing.
 */
async function handleProcessForm(payload) {
  const res = await fetchWithTimeout(`${SERVER_BASE}/process`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      transcript: payload.transcript,
      form_schema: payload.form_schema,
    }),
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(
      `Server returned ${res.status}: ${body || res.statusText}`
    );
  }

  return res.json();
}

/**
 * POST /execute — send confirmed decisions to the server for Playwright execution.
 */
async function handleExecuteForm(payload) {
  const res = await fetchWithTimeout(`${SERVER_BASE}/execute`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ decisions: payload.decisions }),
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(
      `Server returned ${res.status}: ${body || res.statusText}`
    );
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
    const res = await fetch(url, {
      ...options,
      signal: controller.signal,
    });
    return res;
  } catch (err) {
    if (err.name === "AbortError") {
      throw new Error(
        "Request timed out. Ensure the local server is running on port 8000."
      );
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
    return "Cannot reach the local server. Please ensure it is running: uvicorn main:app --reload --port 8000";
  }

  if (msg.includes("timed out")) {
    return msg;
  }

  return `Server error: ${msg}`;
}
