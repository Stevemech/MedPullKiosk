const { createChromeMock } = require("./__mocks__/chrome");
const { readSource } = require("./helpers");

/* ── Constants ───────────────────────────────────────────────── */

const POPUP_EXPORTS = [
  "checkServerHealth",
  "getServerBase",
  "updateProcessBtnState",
  "loadFormQueue",
  "renderFormQueue",
  "renderReviewPanel",
  "getFormSchemaFromTab",
  "showResult",
  "hideResult",
  "showError",
  "hideError",
  "escapeHTML",
  "capitalize",
];

/**
 * Stripped popup.html body (no <script>/<link> to avoid jsdom trying
 * to fetch external resources).
 */
const POPUP_BODY = `
  <div class="header">
    <h1>MedPull Assistant</h1>
    <span id="serverStatus" class="status-badge">
      <span class="status-dot"></span> Checking…
    </span>
  </div>
  <div class="tab-bar">
    <button class="tab active" data-tab="autonomous">Autonomous</button>
    <button class="tab" data-tab="queue">Form Queue</button>
    <button class="tab" data-tab="manual">Manual Transcript</button>
  </div>
  <div id="tab-autonomous" class="tab-content">
    <div class="section">
      <div class="section-header">
        <div class="section-title">Autonomous Mode</div>
        <span id="autoStatus" class="auto-status-badge idle">Idle</span>
      </div>
      <p class="auto-desc">Autonomous processing description.</p>
      <div class="auto-progress" id="autoProgress">
        <div class="auto-progress-bar">
          <div class="auto-progress-fill" id="autoProgressFill" style="width:0%"></div>
        </div>
        <span class="auto-progress-text" id="autoProgressText"></span>
      </div>
      <div class="auto-controls">
        <button id="startAutoBtn" class="btn btn-primary">Start Processing</button>
        <button id="stopAutoBtn" class="btn btn-danger hidden">Stop</button>
      </div>
    </div>
    <div class="section">
      <div class="section-header">
        <div class="section-title">Activity Log</div>
        <button id="clearLogBtn" class="btn-icon" title="Clear log">&#x2715;</button>
      </div>
      <div id="autoLog" class="auto-log">
        <div class="log-empty">Press Start to begin processing forms.</div>
      </div>
    </div>
  </div>
  <div id="tab-queue" class="tab-content hidden">
    <div class="section">
      <div class="section-header">
        <div class="section-title">Pending Forms</div>
        <button id="refreshBtn" class="btn-icon" title="Refresh">&#x21bb;</button>
      </div>
      <div id="formQueue" class="form-queue">
        <div class="message info">Loading form queue…</div>
      </div>
    </div>
  </div>
  <div id="tab-manual" class="tab-content hidden">
    <div class="section">
      <div class="section-title">Clinical Transcript</div>
      <textarea id="transcriptInput" placeholder="Paste or type…"></textarea>
      <div class="file-upload-wrapper">
        <label class="file-upload-label">
          <span>Upload File</span>
          <input type="file" id="fileUpload" accept=".txt,.pdf,.hl7,.json" />
        </label>
        <div id="fileName" class="file-name"></div>
      </div>
    </div>
    <button id="processBtn" class="btn btn-primary" disabled>Process &amp; Fill</button>
  </div>
  <div id="reviewSection" class="section hidden">
    <div class="section-title">Field Decisions — Review Required</div>
    <div id="reviewPanel" class="review-panel"></div>
    <div id="missingRequired" class="missing-required hidden">
      <h4>Missing Required Fields</h4>
      <ul id="missingList"></ul>
    </div>
  </div>
  <button id="confirmBtn" class="btn btn-success hidden" disabled>
    Confirm &amp; Fill eClinicalWorks
  </button>
  <div id="executionResult" class="hidden">
    <div id="resultMessage" class="message"></div>
  </div>
  <div id="errorMessage" class="message error hidden"></div>
  <details class="settings-section">
    <summary class="settings-toggle">Settings</summary>
    <div class="settings-body">
      <label class="settings-label">
        Server URL
        <input type="text" id="settingsServerUrl" placeholder="https://your-alb-domain.com" />
      </label>
      <label class="settings-label">
        API Key
        <input type="password" id="settingsApiKey" placeholder="Your clinic API key" />
      </label>
      <label class="settings-checkbox">
        <input type="checkbox" id="settingsAutoSave" />
        <span>Auto-save after filling</span>
      </label>
      <button id="saveSettingsBtn" class="btn btn-primary btn-sm">Save Settings</button>
      <div id="settingsSaved" class="message success hidden" style="margin-top:8px;">Settings saved.</div>
    </div>
  </details>
`;

/* ── Helpers ──────────────────────────────────────────────────── */

/** Flush one round of microtasks (resolved promises). */
function flushMicrotasks() {
  return new Promise((resolve) => {
    jest.requireActual("timers").setImmediate(resolve);
  });
}

/** Flush several rounds to let chained promise callbacks settle. */
async function settle(rounds = 6) {
  for (let i = 0; i < rounds; i++) {
    await flushMicrotasks();
  }
}

/**
 * Boot the popup: inject DOM, wire mocks, evaluate popup.js,
 * then flush async init (health-check, storage load, queue poll).
 */
async function bootPopup(overrides = {}) {
  document.body.innerHTML = POPUP_BODY;

  const chromeMock = createChromeMock(
    overrides.storage || { serverUrl: "https://test.com", apiKey: "test-key" }
  );
  chromeMock.runtime.sendMessage.mockResolvedValue({
    data: { forms: overrides.forms || [] },
  });

  const fetchMock = overrides.fetch || jest.fn().mockResolvedValue({ ok: true });

  const code = readSource("popup.js");
  const factory = new Function(
    "chrome",
    "fetch",
    `${code}\nreturn { ${POPUP_EXPORTS.join(", ")} };`
  );
  const fns = factory(chromeMock, fetchMock);

  await settle();

  return { fns, chromeMock, fetchMock };
}

/* ── Test setup ──────────────────────────────────────────────── */

beforeEach(() => {
  jest.useFakeTimers();
});

afterEach(() => {
  jest.useRealTimers();
  document.body.innerHTML = "";
});

/* ════════════════════════════════════════════════════════════════
   Pure helpers
   ════════════════════════════════════════════════════════════════ */

describe("escapeHTML", () => {
  test("escapes < > & \" characters", async () => {
    const { fns } = await bootPopup();
    expect(fns.escapeHTML('<b>"hi"&</b>')).toBe(
      "&lt;b&gt;\"hi\"&amp;&lt;/b&gt;"
    );
  });

  test("returns empty string for null/undefined", async () => {
    const { fns } = await bootPopup();
    expect(fns.escapeHTML(null)).toBe("");
    expect(fns.escapeHTML(undefined)).toBe("");
  });
});

/* ════════════════════════════════════════════════════════════════
   Initialization
   ════════════════════════════════════════════════════════════════ */

describe("initialization", () => {
  test("loads settings from chrome.storage.sync on startup", async () => {
    await bootPopup({ storage: { serverUrl: "https://my.com", apiKey: "k1" } });

    expect(document.getElementById("settingsServerUrl").value).toBe(
      "https://my.com"
    );
    expect(document.getElementById("settingsApiKey").value).toBe("k1");
  });

  test("marks server as online when health-check succeeds", async () => {
    await bootPopup({ fetch: jest.fn().mockResolvedValue({ ok: true }) });

    const badge = document.getElementById("serverStatus");
    expect(badge.className).toContain("connected");
    expect(badge.textContent).toContain("Server Online");
  });

  test("marks server as offline when health-check fails", async () => {
    await bootPopup({ fetch: jest.fn().mockRejectedValue(new Error("nope")) });

    const badge = document.getElementById("serverStatus");
    expect(badge.className).toContain("error");
    expect(badge.textContent).toContain("Server Offline");
  });

  test("processBtn is disabled when textarea is empty", async () => {
    await bootPopup();
    expect(document.getElementById("processBtn").disabled).toBe(true);
  });
});

/* ════════════════════════════════════════════════════════════════
   Tab switching
   ════════════════════════════════════════════════════════════════ */

describe("tab switching", () => {
  test("clicking Manual tab shows manual content and hides queue", async () => {
    await bootPopup();

    const manualTab = document.querySelector('[data-tab="manual"]');
    manualTab.click();

    expect(
      document.getElementById("tab-manual").classList.contains("hidden")
    ).toBe(false);
    expect(
      document.getElementById("tab-queue").classList.contains("hidden")
    ).toBe(true);
    expect(manualTab.classList.contains("active")).toBe(true);
  });

  test("clicking Queue tab switches back", async () => {
    await bootPopup();

    document.querySelector('[data-tab="manual"]').click();
    document.querySelector('[data-tab="queue"]').click();

    expect(
      document.getElementById("tab-queue").classList.contains("hidden")
    ).toBe(false);
  });
});

/* ════════════════════════════════════════════════════════════════
   Settings
   ════════════════════════════════════════════════════════════════ */

describe("settings", () => {
  test("saving settings writes to chrome.storage.sync", async () => {
    const { chromeMock } = await bootPopup();

    document.getElementById("settingsServerUrl").value = "https://new.com";
    document.getElementById("settingsApiKey").value = "new-key";
    document.getElementById("saveSettingsBtn").click();

    expect(chromeMock.storage.sync.set).toHaveBeenCalledWith(
      { serverUrl: "https://new.com", apiKey: "new-key", autoSave: false },
      expect.any(Function)
    );
  });

  test("shows 'Settings saved' confirmation after save", async () => {
    await bootPopup();

    document.getElementById("saveSettingsBtn").click();
    const badge = document.getElementById("settingsSaved");
    expect(badge.classList.contains("hidden")).toBe(false);
  });
});

/* ════════════════════════════════════════════════════════════════
   showError / hideError / showResult / hideResult
   ════════════════════════════════════════════════════════════════ */

describe("error and result helpers", () => {
  test("showError makes the error div visible with the message", async () => {
    const { fns } = await bootPopup();
    fns.showError("Something broke");

    const el = document.getElementById("errorMessage");
    expect(el.classList.contains("hidden")).toBe(false);
    expect(el.textContent).toBe("Something broke");
  });

  test("hideError adds .hidden class", async () => {
    const { fns } = await bootPopup();
    fns.showError("err");
    fns.hideError();
    expect(
      document.getElementById("errorMessage").classList.contains("hidden")
    ).toBe(true);
  });

  test("showResult (success) shows a success message with filled count", async () => {
    const { fns } = await bootPopup();
    fns.showResult({ success: true, filled: 5 });

    const wrapper = document.getElementById("executionResult");
    const msg = document.getElementById("resultMessage");
    expect(wrapper.classList.contains("hidden")).toBe(false);
    expect(msg.className).toContain("success");
    expect(msg.textContent).toContain("5 fields");
  });

  test("showResult (failure) shows errors joined by semicolons", async () => {
    const { fns } = await bootPopup();
    fns.showResult({ success: false, errors: ["Field A missing", "Field B failed"] });

    const msg = document.getElementById("resultMessage");
    expect(msg.className).toContain("error");
    expect(msg.textContent).toContain("Field A missing; Field B failed");
  });

  test("hideResult hides the execution result section", async () => {
    const { fns } = await bootPopup();
    fns.showResult({ success: true, filled: 1 });
    fns.hideResult();
    expect(
      document.getElementById("executionResult").classList.contains("hidden")
    ).toBe(true);
  });
});

/* ════════════════════════════════════════════════════════════════
   renderFormQueue
   ════════════════════════════════════════════════════════════════ */

describe("renderFormQueue", () => {
  test("renders queue cards for forms", async () => {
    const { fns } = await bootPopup();
    fns.renderFormQueue([
      {
        form_id: "f1",
        patient_id: "P-001",
        status: "ready",
        source: "kiosk",
        created_at: "2025-01-01T00:00:00Z",
      },
      {
        form_id: "f2",
        patient_id: "P-002",
        status: "pending",
        source: "extension",
        created_at: "2025-01-02T00:00:00Z",
      },
    ]);

    const cards = document.querySelectorAll(".queue-card");
    expect(cards).toHaveLength(2);

    expect(cards[0].textContent).toContain("P-001");
    expect(cards[0].querySelector(".decision-badge").textContent).toBe("ready");

    expect(cards[1].textContent).toContain("P-002");
  });

  test("shows info message for empty queue", async () => {
    const { fns } = await bootPopup();
    fns.renderFormQueue([]);

    const queue = document.getElementById("formQueue");
    expect(queue.textContent).toContain("No pending forms");
  });

  test("handles null/undefined forms gracefully", async () => {
    const { fns } = await bootPopup();
    fns.renderFormQueue(null);

    const queue = document.getElementById("formQueue");
    expect(queue.textContent).toContain("No pending forms");
  });
});

/* ════════════════════════════════════════════════════════════════
   renderReviewPanel
   ════════════════════════════════════════════════════════════════ */

describe("renderReviewPanel", () => {
  test("renders decision cards with action badges and confidence bars", async () => {
    const { fns } = await bootPopup();
    fns.renderReviewPanel({
      decisions: [
        {
          field_id: "f1",
          label: "Patient Name",
          action: "fill",
          value: "John",
          confidence: 0.92,
          reasoning: "From transcript",
        },
        {
          field_id: "f2",
          label: "SSN",
          action: "skip",
          value: null,
          confidence: 0.5,
          reasoning: "Not in transcript",
        },
        {
          field_id: "f3",
          label: "Allergies",
          action: "flag",
          value: null,
          confidence: 0.3,
          reasoning: "Required but not found",
        },
      ],
      missing_required: ["Allergies"],
    });

    const cards = document.querySelectorAll(".decision-card");
    expect(cards).toHaveLength(3);

    expect(cards[0].querySelector(".decision-badge").textContent).toBe("fill");
    expect(cards[0].querySelector(".decision-value").textContent).toContain("John");

    expect(cards[1].classList.contains("skip")).toBe(true);

    const reviewSection = document.getElementById("reviewSection");
    expect(reviewSection.classList.contains("hidden")).toBe(false);

    const confirmBtn = document.getElementById("confirmBtn");
    expect(confirmBtn.classList.contains("hidden")).toBe(false);
    expect(confirmBtn.disabled).toBe(false);
  });

  test("shows missing-required list when present", async () => {
    const { fns } = await bootPopup();
    fns.renderReviewPanel({
      decisions: [{ field_id: "a", label: "A", action: "fill", value: "V", confidence: 1, reasoning: "" }],
      missing_required: ["Field X", "Field Y"],
    });

    const missing = document.getElementById("missingRequired");
    expect(missing.classList.contains("hidden")).toBe(false);
    const items = document.querySelectorAll("#missingList li");
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toBe("Field X");
  });

  test("hides missing-required section when none are missing", async () => {
    const { fns } = await bootPopup();
    fns.renderReviewPanel({
      decisions: [{ field_id: "a", label: "A", action: "fill", value: "V", confidence: 1, reasoning: "" }],
      missing_required: [],
    });

    expect(
      document.getElementById("missingRequired").classList.contains("hidden")
    ).toBe(true);
  });

  test("shows info message when no decisions exist", async () => {
    const { fns } = await bootPopup();
    fns.renderReviewPanel({ decisions: [], missing_required: [] });

    const panel = document.getElementById("reviewPanel");
    expect(panel.textContent).toContain("No field decisions");
  });
});

/* ════════════════════════════════════════════════════════════════
   loadFormQueue (integration with chrome.runtime.sendMessage)
   ════════════════════════════════════════════════════════════════ */

describe("loadFormQueue", () => {
  test("populates form queue on success", async () => {
    const { fns, chromeMock } = await bootPopup();

    chromeMock.runtime.sendMessage.mockResolvedValue({
      data: {
        forms: [
          { form_id: "f1", patient_id: "P1", status: "ready", source: "kiosk", created_at: new Date().toISOString() },
        ],
      },
    });

    await fns.loadFormQueue();
    await settle();

    const cards = document.querySelectorAll(".queue-card");
    expect(cards.length).toBeGreaterThanOrEqual(1);
  });

  test("shows error message when response contains error", async () => {
    const { fns, chromeMock } = await bootPopup();
    chromeMock.runtime.sendMessage.mockResolvedValue({
      error: "Auth failed",
    });

    await fns.loadFormQueue();
    await settle();

    const queue = document.getElementById("formQueue");
    expect(queue.textContent).toContain("Auth failed");
  });
});

/* ════════════════════════════════════════════════════════════════
   Refresh button
   ════════════════════════════════════════════════════════════════ */

describe("refresh button", () => {
  test("clicking refresh reloads the form queue", async () => {
    const { chromeMock } = await bootPopup();

    chromeMock.runtime.sendMessage.mockResolvedValue({
      data: { forms: [] },
    });

    document.getElementById("refreshBtn").click();
    await settle();

    expect(chromeMock.runtime.sendMessage).toHaveBeenCalledWith(
      expect.objectContaining({ action: "getFormQueue" })
    );
  });
});

/* ════════════════════════════════════════════════════════════════
   Manual transcript — input handling
   ════════════════════════════════════════════════════════════════ */

describe("manual transcript input", () => {
  test("typing text enables process button when server is online", async () => {
    await bootPopup({ fetch: jest.fn().mockResolvedValue({ ok: true }) });

    const ta = document.getElementById("transcriptInput");
    const btn = document.getElementById("processBtn");

    ta.value = "Patient complains of cough.";
    ta.dispatchEvent(new Event("input", { bubbles: true }));

    expect(btn.disabled).toBe(false);
  });

  test("clearing text disables process button", async () => {
    await bootPopup({ fetch: jest.fn().mockResolvedValue({ ok: true }) });

    const ta = document.getElementById("transcriptInput");
    const btn = document.getElementById("processBtn");

    ta.value = "Some text";
    ta.dispatchEvent(new Event("input", { bubbles: true }));
    expect(btn.disabled).toBe(false);

    ta.value = "";
    ta.dispatchEvent(new Event("input", { bubbles: true }));
    expect(btn.disabled).toBe(true);
  });

  test("process button stays disabled when server is offline", async () => {
    await bootPopup({ fetch: jest.fn().mockRejectedValue(new Error("down")) });

    const ta = document.getElementById("transcriptInput");
    const btn = document.getElementById("processBtn");

    ta.value = "Text here";
    ta.dispatchEvent(new Event("input", { bubbles: true }));

    expect(btn.disabled).toBe(true);
  });
});

/* ════════════════════════════════════════════════════════════════
   getFormSchemaFromTab
   ════════════════════════════════════════════════════════════════ */

describe("getFormSchemaFromTab", () => {
  test("returns schema from executeScript result", async () => {
    const { fns, chromeMock } = await bootPopup();
    const mockSchema = [{ id: "f1", name: "field", type: "text" }];
    chromeMock.scripting.executeScript.mockResolvedValue([
      { result: mockSchema },
    ]);
    chromeMock.tabs.query.mockResolvedValue([{ id: 42 }]);

    const schema = await fns.getFormSchemaFromTab();
    expect(schema).toEqual(mockSchema);
  });

  test("returns empty array when no active tab", async () => {
    const { fns, chromeMock } = await bootPopup();
    chromeMock.tabs.query.mockResolvedValue([]);

    const schema = await fns.getFormSchemaFromTab();
    expect(schema).toEqual([]);
  });

  test("returns empty array when executeScript throws", async () => {
    const { fns, chromeMock } = await bootPopup();
    chromeMock.tabs.query.mockResolvedValue([{ id: 1 }]);
    chromeMock.scripting.executeScript.mockRejectedValue(
      new Error("Cannot access page")
    );

    const schema = await fns.getFormSchemaFromTab();
    expect(schema).toEqual([]);
  });
});
