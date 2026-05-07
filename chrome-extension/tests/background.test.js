const { createChromeMock } = require("./__mocks__/chrome");
const { loadScript } = require("./helpers");

/* ── Exported function names from background.js ─────────────── */
const EXPORTS = [
  "getServerBase",
  "getApiKey",
  "authHeaders",
  "handleSubmitForm",
  "handleGetFormQueue",
  "handleGetForm",
  "handleProcessForm",
  "handleClaimForm",
  "handleCompleteForm",
  "handleStartAutonomous",
  "handleStopAutonomous",
  "handleGetAutonomousState",
  "callNavigateAPI",
  "fetchWithTimeout",
  "formatError",
  "sleep",
];

/**
 * Create a fresh background-script context with its own mocks.
 * Every call evaluates background.js from scratch, giving full isolation.
 */
function createContext(options = {}) {
  const chromeMock = createChromeMock(
    options.storage || { serverUrl: "https://test-server.com", apiKey: "key-123" }
  );

  const fetchMock =
    options.fetch ||
    jest.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ result: "ok" }),
      text: () => Promise.resolve(""),
      status: 200,
      statusText: "OK",
    });

  const fns = loadScript("background.js", { chrome: chromeMock, fetch: fetchMock }, EXPORTS);

  return { ...fns, chromeMock, fetchMock };
}

/* ════════════════════════════════════════════════════════════════
   getServerBase / getApiKey / authHeaders
   ════════════════════════════════════════════════════════════════ */

describe("getServerBase", () => {
  test("returns URL stored in chrome.storage.sync", async () => {
    const { getServerBase } = createContext({
      storage: { serverUrl: "https://my-hospital.com" },
    });
    await expect(getServerBase()).resolves.toBe("https://my-hospital.com");
  });

  test("returns default when storage has no serverUrl", async () => {
    const { getServerBase } = createContext({ storage: {} });
    await expect(getServerBase()).resolves.toBe("https://YOUR_ALB_DOMAIN_HERE");
  });

  test("strips trailing slashes", async () => {
    const { getServerBase } = createContext({
      storage: { serverUrl: "https://example.com///" },
    });
    await expect(getServerBase()).resolves.toBe("https://example.com");
  });
});

describe("getApiKey", () => {
  test("returns stored API key", async () => {
    const { getApiKey } = createContext({ storage: { apiKey: "secret" } });
    await expect(getApiKey()).resolves.toBe("secret");
  });

  test("returns empty string when no key stored", async () => {
    const { getApiKey } = createContext({ storage: {} });
    await expect(getApiKey()).resolves.toBe("");
  });
});

describe("authHeaders", () => {
  test("returns Content-Type and X-API-Key headers", async () => {
    const { authHeaders } = createContext({ storage: { apiKey: "abc" } });
    const headers = await authHeaders();
    expect(headers).toEqual({
      "Content-Type": "application/json",
      "X-API-Key": "abc",
    });
  });
});

/* ════════════════════════════════════════════════════════════════
   fetchWithTimeout
   ════════════════════════════════════════════════════════════════ */

describe("fetchWithTimeout", () => {
  test("returns the fetch Response on success", async () => {
    const mockResponse = { ok: true, json: jest.fn() };
    const { fetchWithTimeout } = createContext({
      fetch: jest.fn().mockResolvedValue(mockResponse),
    });
    const res = await fetchWithTimeout("https://x.com/api", {});
    expect(res).toBe(mockResponse);
  });

  test("passes an AbortSignal to fetch", async () => {
    const mockFetch = jest.fn().mockResolvedValue({ ok: true });
    const { fetchWithTimeout } = createContext({ fetch: mockFetch });
    await fetchWithTimeout("https://x.com/api", { method: "GET" });

    const [, opts] = mockFetch.mock.calls[0];
    expect(opts.signal).toBeDefined();
    expect(opts.method).toBe("GET");
  });

  test("converts AbortError into a user-friendly timeout message", async () => {
    const err = new Error("The operation was aborted");
    err.name = "AbortError";
    const { fetchWithTimeout } = createContext({
      fetch: jest.fn().mockRejectedValue(err),
    });
    await expect(fetchWithTimeout("https://x.com", {})).rejects.toThrow(
      "Request timed out"
    );
  });

  test("rethrows non-abort errors unchanged", async () => {
    const err = new TypeError("Failed to fetch");
    const { fetchWithTimeout } = createContext({
      fetch: jest.fn().mockRejectedValue(err),
    });
    await expect(fetchWithTimeout("https://x.com", {})).rejects.toThrow(
      "Failed to fetch"
    );
  });
});

/* ════════════════════════════════════════════════════════════════
   formatError
   ════════════════════════════════════════════════════════════════ */

describe("formatError", () => {
  let formatError;
  beforeAll(() => {
    ({ formatError } = createContext());
  });

  test("maps 'Failed to fetch' to connectivity message", () => {
    const msg = formatError(new Error("Failed to fetch"));
    expect(msg).toContain("Cannot reach the server");
  });

  test("maps 'NetworkError' to connectivity message", () => {
    const msg = formatError(new Error("NetworkError when attempting"));
    expect(msg).toContain("Cannot reach the server");
  });

  test("passes through timeout messages", () => {
    const msg = formatError(
      new Error("Request timed out. Check your server URL and network connection.")
    );
    expect(msg).toContain("timed out");
  });

  test("maps status 401 to authentication failure message", () => {
    const msg = formatError(new Error("Server returned 401: Unauthorized"));
    expect(msg).toContain("Authentication failed");
  });

  test("wraps unknown errors with 'Server error:' prefix", () => {
    const msg = formatError(new Error("Something unexpected"));
    expect(msg).toBe("Server error: Something unexpected");
  });

  test("handles non-Error values gracefully", () => {
    const msg = formatError("raw string error");
    expect(msg).toBe("Server error: raw string error");
  });
});

/* ════════════════════════════════════════════════════════════════
   Message listener routing
   ════════════════════════════════════════════════════════════════ */

describe("message listener", () => {
  test("registers a listener on chrome.runtime.onMessage", () => {
    const { chromeMock } = createContext();
    expect(chromeMock.runtime.onMessage.addListener).toHaveBeenCalledTimes(1);
    expect(typeof chromeMock.runtime.onMessage.addListener.mock.calls[0][0]).toBe(
      "function"
    );
  });

  test("returns true for known actions (keeps sendResponse channel open)", () => {
    const { chromeMock } = createContext();
    const listener = chromeMock.runtime.onMessage.addListener.mock.calls[0][0];
    const sendResponse = jest.fn();
    const result = listener({ action: "getFormQueue", payload: {} }, {}, sendResponse);
    expect(result).toBe(true);
  });

  test("returns false for unknown actions", () => {
    const { chromeMock } = createContext();
    const listener = chromeMock.runtime.onMessage.addListener.mock.calls[0][0];
    const sendResponse = jest.fn();
    const result = listener({ action: "nonExistentAction" }, {}, sendResponse);
    expect(result).toBe(false);
  });

  test("calls sendResponse with { data } on handler success", async () => {
    const mockFetch = jest.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ forms: [] }),
      text: () => Promise.resolve(""),
    });
    const { chromeMock } = createContext({ fetch: mockFetch });
    const listener = chromeMock.runtime.onMessage.addListener.mock.calls[0][0];

    const sendResponse = jest.fn();
    listener({ action: "getFormQueue", payload: {} }, {}, sendResponse);

    await new Promise((r) => setTimeout(r, 50));
    expect(sendResponse).toHaveBeenCalledWith({ data: { forms: [] } });
  });

  test("calls sendResponse with { error } on handler failure", async () => {
    const mockFetch = jest.fn().mockRejectedValue(new Error("Failed to fetch"));
    const { chromeMock } = createContext({ fetch: mockFetch });
    const listener = chromeMock.runtime.onMessage.addListener.mock.calls[0][0];

    const sendResponse = jest.fn();
    listener({ action: "getFormQueue", payload: {} }, {}, sendResponse);

    await new Promise((r) => setTimeout(r, 50));
    expect(sendResponse).toHaveBeenCalledWith({
      error: expect.stringContaining("Cannot reach the server"),
    });
  });
});

/* ════════════════════════════════════════════════════════════════
   API handler — happy & error paths (shared pattern)
   ════════════════════════════════════════════════════════════════ */

describe("API handlers — common behaviour", () => {
  const HANDLER_CASES = [
    { name: "handleSubmitForm", payload: { patient_id: "p1", transcript: "hi" } },
    { name: "handleGetFormQueue", payload: { status: "pending" } },
    { name: "handleGetForm", payload: { form_id: "f-1" } },
    { name: "handleProcessForm", payload: { form_id: "f-1", form_schema: [] } },
    { name: "handleClaimForm", payload: { form_id: "f-1" } },
    { name: "handleCompleteForm", payload: { form_id: "f-1" } },
  ];

  describe.each(HANDLER_CASES)("$name", ({ name, payload }) => {
    test("resolves with parsed JSON on HTTP 200", async () => {
      const body = { id: "result-1" };
      const mockFetch = jest.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(body),
        text: () => Promise.resolve(JSON.stringify(body)),
      });
      const ctx = createContext({ fetch: mockFetch });
      await expect(ctx[name](payload)).resolves.toEqual(body);
    });

    test("rejects with status + body on non-ok response", async () => {
      const mockFetch = jest.fn().mockResolvedValue({
        ok: false,
        status: 500,
        statusText: "Internal Server Error",
        text: () => Promise.resolve("db error"),
      });
      const ctx = createContext({ fetch: mockFetch });
      await expect(ctx[name](payload)).rejects.toThrow("Server returned 500: db error");
    });

    test("rejects with status + statusText when body is unreadable", async () => {
      const mockFetch = jest.fn().mockResolvedValue({
        ok: false,
        status: 503,
        statusText: "Service Unavailable",
        text: () => Promise.reject(new Error("stream error")),
      });
      const ctx = createContext({ fetch: mockFetch });
      await expect(ctx[name](payload)).rejects.toThrow(
        "Server returned 503: Service Unavailable"
      );
    });
  });
});

/* ════════════════════════════════════════════════════════════════
   API handler — request-specific assertions
   ════════════════════════════════════════════════════════════════ */

describe("API handlers — request construction", () => {
  function successFetch() {
    return jest.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({}),
      text: () => Promise.resolve(""),
    });
  }

  test("handleSubmitForm POSTs /forms with correct body", async () => {
    const mockFetch = successFetch();
    const { handleSubmitForm } = createContext({ fetch: mockFetch });

    await handleSubmitForm({
      patient_id: "p1",
      transcript: "cough for 3 days",
      source: "kiosk",
      form_schema: [{ id: "f1" }],
    });

    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("https://test-server.com/forms");
    expect(opts.method).toBe("POST");
    const body = JSON.parse(opts.body);
    expect(body.patient_id).toBe("p1");
    expect(body.transcript).toBe("cough for 3 days");
    expect(body.source).toBe("kiosk");
    expect(body.form_schema).toEqual([{ id: "f1" }]);
  });

  test("handleSubmitForm defaults source to 'extension' and form_schema to null", async () => {
    const mockFetch = successFetch();
    const { handleSubmitForm } = createContext({ fetch: mockFetch });

    await handleSubmitForm({ patient_id: "p1", transcript: "test" });

    const body = JSON.parse(mockFetch.mock.calls[0][1].body);
    expect(body.source).toBe("extension");
    expect(body.form_schema).toBeNull();
  });

  test("handleGetFormQueue GETs /forms with encoded status query", async () => {
    const mockFetch = successFetch();
    const { handleGetFormQueue } = createContext({ fetch: mockFetch });

    await handleGetFormQueue({ status: "pending,ready" });

    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("https://test-server.com/forms?status=pending%2Cready");
    expect(opts.method).toBe("GET");
  });

  test("handleGetFormQueue defaults status to 'pending,ready'", async () => {
    const mockFetch = successFetch();
    const { handleGetFormQueue } = createContext({ fetch: mockFetch });

    await handleGetFormQueue(null);

    const [url] = mockFetch.mock.calls[0];
    expect(url).toContain("status=pending%2Cready");
  });

  test("handleGetForm GETs /forms/{form_id}", async () => {
    const mockFetch = successFetch();
    const { handleGetForm } = createContext({ fetch: mockFetch });

    await handleGetForm({ form_id: "abc-123" });

    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("https://test-server.com/forms/abc-123");
    expect(opts.method).toBe("GET");
  });

  test("handleProcessForm POSTs /forms/{form_id}/process with schema body", async () => {
    const mockFetch = successFetch();
    const { handleProcessForm } = createContext({ fetch: mockFetch });

    await handleProcessForm({ form_id: "f1", form_schema: [{ id: "x" }] });

    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("https://test-server.com/forms/f1/process");
    expect(opts.method).toBe("POST");
    expect(JSON.parse(opts.body)).toEqual({ form_schema: [{ id: "x" }] });
  });

  test("handleProcessForm defaults form_schema to []", async () => {
    const mockFetch = successFetch();
    const { handleProcessForm } = createContext({ fetch: mockFetch });

    await handleProcessForm({ form_id: "f1" });

    const body = JSON.parse(mockFetch.mock.calls[0][1].body);
    expect(body.form_schema).toEqual([]);
  });

  test("handleClaimForm POSTs /forms/{form_id}/claim with no body", async () => {
    const mockFetch = successFetch();
    const { handleClaimForm } = createContext({ fetch: mockFetch });

    await handleClaimForm({ form_id: "f1" });

    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("https://test-server.com/forms/f1/claim");
    expect(opts.method).toBe("POST");
    expect(opts.body).toBeUndefined();
  });

  test("handleCompleteForm POSTs /forms/{form_id}/complete", async () => {
    const mockFetch = successFetch();
    const { handleCompleteForm } = createContext({ fetch: mockFetch });

    await handleCompleteForm({ form_id: "f1" });

    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("https://test-server.com/forms/f1/complete");
    expect(opts.method).toBe("POST");
  });

  test("all handlers send X-API-Key header from storage", async () => {
    const mockFetch = successFetch();
    const ctx = createContext({
      fetch: mockFetch,
      storage: { serverUrl: "https://s.com", apiKey: "secret-key-42" },
    });

    await ctx.handleGetFormQueue({});

    const headers = mockFetch.mock.calls[0][1].headers;
    expect(headers["X-API-Key"]).toBe("secret-key-42");
    expect(headers["Content-Type"]).toBe("application/json");
  });
});

/* ════════════════════════════════════════════════════════════════
   Autonomous mode handlers
   ════════════════════════════════════════════════════════════════ */

describe("autonomous state", () => {
  test("getAutonomousState returns default state when nothing stored", async () => {
    const { handleGetAutonomousState } = createContext();
    const state = await handleGetAutonomousState();
    expect(state).toEqual({
      running: false,
      currentForm: null,
      formIndex: 0,
      totalForms: 0,
      step: "idle",
      log: [],
    });
  });
});

describe("callNavigateAPI", () => {
  test("POSTs to /navigate with snapshot and goal", async () => {
    const mockResp = {
      action: { action: "click", element_index: 0, value: null, reasoning: "test", done: false, confidence: 0.9 },
      model_used: "xai/grok-3-mini",
    };
    const mockFetch = jest.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockResp),
      text: () => Promise.resolve(JSON.stringify(mockResp)),
    });
    const { callNavigateAPI } = createContext({ fetch: mockFetch });

    const snapshot = { url: "https://ecw.com", title: "eCW", visible_text: "", elements: [], iframe_count: 0, has_modal: false };
    const result = await callNavigateAPI("find patient", snapshot, [], null);

    expect(result).toEqual(mockResp);
    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("https://test-server.com/navigate");
    expect(opts.method).toBe("POST");
    const body = JSON.parse(opts.body);
    expect(body.goal).toBe("find patient");
    expect(body.page_snapshot).toEqual(snapshot);
  });

  test("throws on non-ok response", async () => {
    const mockFetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: "Internal Server Error",
      text: () => Promise.resolve("error"),
    });
    const { callNavigateAPI } = createContext({ fetch: mockFetch });

    const snapshot = { url: "", title: "", visible_text: "", elements: [], iframe_count: 0, has_modal: false };
    await expect(callNavigateAPI("goal", snapshot, [], null)).rejects.toThrow("Navigate API returned 500");
  });
});

describe("message listener — autonomous actions", () => {
  test("startAutonomous action is routed", () => {
    const { chromeMock } = createContext();
    const listener = chromeMock.runtime.onMessage.addListener.mock.calls[0][0];
    const sendResponse = jest.fn();
    const result = listener({ action: "startAutonomous", payload: {} }, {}, sendResponse);
    expect(result).toBe(true);
  });

  test("stopAutonomous action is routed", () => {
    const { chromeMock } = createContext();
    const listener = chromeMock.runtime.onMessage.addListener.mock.calls[0][0];
    const sendResponse = jest.fn();
    const result = listener({ action: "stopAutonomous", payload: {} }, {}, sendResponse);
    expect(result).toBe(true);
  });

  test("getAutonomousState action is routed", () => {
    const { chromeMock } = createContext();
    const listener = chromeMock.runtime.onMessage.addListener.mock.calls[0][0];
    const sendResponse = jest.fn();
    const result = listener({ action: "getAutonomousState", payload: {} }, {}, sendResponse);
    expect(result).toBe(true);
  });
});
