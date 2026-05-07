/**
 * content.js — MedPull eClinicalWorks Assistant
 *
 * Injected into eClinicalWorks pages. Provides:
 *   - extractFormSchema()    — scans visible form elements and returns a schema array
 *   - highlightDecisions()   — visually marks fields after LLM processing
 *   - getPageSnapshot()      — captures interactive elements for Grok navigation
 *   - executeAction(action)  — performs click/type/select as directed by Grok
 *   - waitForStable()        — waits for DOM to settle after navigation
 */

/* ══════════════════════════════════════════════════════════════════
   Page Snapshot — captures the page state for Grok navigation
   ══════════════════════════════════════════════════════════════════ */

const INTERACTIVE_SELECTORS =
  "a[href], button, input, select, textarea, [role='button'], " +
  "[role='tab'], [role='link'], [role='menuitem'], [onclick], " +
  "[tabindex]:not([tabindex='-1'])";

const MAX_ELEMENTS = 200;
const MAX_TEXT_LENGTH = 2000;

function getPageSnapshot() {
  const elements = [];
  const seen = new WeakSet();

  function collectFrom(doc, frameLabel) {
    let candidates;
    try {
      candidates = doc.querySelectorAll(INTERACTIVE_SELECTORS);
    } catch { return; }

    for (const el of candidates) {
      if (seen.has(el) || elements.length >= MAX_ELEMENTS) break;
      seen.add(el);

      if (el.type === "hidden") continue;
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) continue;

      const style = el.ownerDocument.defaultView.getComputedStyle(el);
      if (style.display === "none" || style.visibility === "hidden") continue;

      const text = (el.textContent || "").trim().slice(0, 120);
      const entry = {
        index: elements.length,
        tag: el.tagName,
      };

      if (el.type) entry.type = el.type;
      if (el.id) entry.id = el.id;
      if (el.name) entry.name = el.name;
      if (text && text !== el.value) entry.text = text;
      if (el.placeholder) entry.placeholder = el.placeholder;
      if (el.value && el.tagName !== "BUTTON") entry.value = el.value.slice(0, 100);
      if (el.href) entry.href = el.href;
      if (el.className && typeof el.className === "string") entry.classes = el.className.slice(0, 100);
      if (el.getAttribute("aria-label")) entry.aria_label = el.getAttribute("aria-label");
      if (el.getAttribute("role")) entry.role = el.getAttribute("role");
      if (el.disabled) entry.disabled = true;
      if (frameLabel) entry.frame = frameLabel;

      entry._el = el;
      elements.push(entry);
    }
  }

  collectFrom(document, null);

  try {
    const iframes = document.querySelectorAll("iframe");
    for (let i = 0; i < iframes.length && elements.length < MAX_ELEMENTS; i++) {
      try {
        const iframeDoc = iframes[i].contentDocument;
        if (iframeDoc) collectFrom(iframeDoc, `iframe-${i}`);
      } catch { /* cross-origin, skip */ }
    }
  } catch { /* no iframes */ }

  const hasModal = !!document.querySelector(
    "[role='dialog'], [role='alertdialog'], .modal.show, .modal[style*='display: block'], " +
    ".overlay:not(.hidden), [class*='modal'][class*='open']"
  );

  const visibleText = document.body ? document.body.innerText.slice(0, MAX_TEXT_LENGTH) : "";

  const cleanElements = elements.map(({ _el, ...rest }) => rest);

  return {
    url: window.location.href,
    title: document.title,
    visible_text: visibleText,
    elements: cleanElements,
    iframe_count: document.querySelectorAll("iframe").length,
    has_modal: hasModal,
    _rawElements: elements,
  };
}

/* ══════════════════════════════════════════════════════════════════
   Execute Action — performs browser actions as directed by Grok
   ══════════════════════════════════════════════════════════════════ */

function executeAction(action, snapshotElements) {
  const result = { success: false, error: null };

  if (action.action === "wait") {
    result.success = true;
    return result;
  }

  if (action.action === "done" || action.action === "error") {
    result.success = true;
    return result;
  }

  if (action.element_index == null || action.element_index < 0 || action.element_index >= snapshotElements.length) {
    result.error = `Invalid element index: ${action.element_index}`;
    return result;
  }

  const entry = snapshotElements[action.element_index];
  const el = entry._el;

  if (!el || !el.isConnected) {
    result.error = `Element at index ${action.element_index} is no longer in the DOM`;
    return result;
  }

  try {
    switch (action.action) {
      case "click":
        el.scrollIntoView({ behavior: "instant", block: "center" });
        el.focus();
        el.click();
        result.success = true;
        break;

      case "type":
        el.scrollIntoView({ behavior: "instant", block: "center" });
        el.focus();
        el.value = "";
        el.dispatchEvent(new Event("input", { bubbles: true }));

        if (action.value) {
          el.value = action.value;
          el.dispatchEvent(new Event("input", { bubbles: true }));
          el.dispatchEvent(new Event("change", { bubbles: true }));

          if (action.value.endsWith("\n")) {
            el.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", code: "Enter", bubbles: true }));
            el.dispatchEvent(new KeyboardEvent("keyup", { key: "Enter", code: "Enter", bubbles: true }));
          }
        }
        result.success = true;
        break;

      case "select":
        if (el.tagName === "SELECT" && action.value) {
          for (const opt of el.options) {
            if (opt.value === action.value ||
                opt.textContent.trim().toLowerCase() === action.value.toLowerCase()) {
              el.value = opt.value;
              el.dispatchEvent(new Event("change", { bubbles: true }));
              result.success = true;
              break;
            }
          }
          if (!result.success) {
            result.error = `Option "${action.value}" not found in select`;
          }
        } else {
          el.click();
          result.success = true;
        }
        break;

      default:
        result.error = `Unknown action: ${action.action}`;
    }
  } catch (e) {
    result.error = `Action failed: ${e.message}`;
  }

  return result;
}

/* ══════════════════════════════════════════════════════════════════
   Wait for Stable — waits for DOM mutations to settle
   ══════════════════════════════════════════════════════════════════ */

function waitForStable(timeoutMs = 5000, quietMs = 600) {
  return new Promise((resolve) => {
    let timer = null;
    let settled = false;

    const observer = new MutationObserver(() => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        settled = true;
        observer.disconnect();
        resolve({ settled: true });
      }, quietMs);
    });

    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
    });

    timer = setTimeout(() => {
      if (!settled) {
        observer.disconnect();
        resolve({ settled: true });
      }
    }, quietMs);

    setTimeout(() => {
      if (!settled) {
        observer.disconnect();
        resolve({ settled: false, timeout: true });
      }
    }, timeoutMs);
  });
}

/* ══════════════════════════════════════════════════════════════════
   extractFormSchema (original, preserved)
   ══════════════════════════════════════════════════════════════════ */

function extractFormSchema() {
  const elements = document.querySelectorAll("input, select, textarea");
  const schema = [];

  for (const el of elements) {
    if (
      el.type === "hidden" ||
      el.type === "submit" ||
      el.type === "button" ||
      el.type === "reset"
    ) continue;

    const style = window.getComputedStyle(el);
    if (style.display === "none" || style.visibility === "hidden") continue;

    const fieldId = el.id || el.getAttribute("data-field-id") || "";
    const fieldName = el.name || "";
    const fieldLabel = resolveLabel(el);
    const fieldType = el.tagName === "SELECT" ? "select" : (el.type || "text");
    const isRequired =
      el.required ||
      el.getAttribute("aria-required") === "true" ||
      el.classList.contains("required");

    const options = [];
    if (el.tagName === "SELECT") {
      for (const opt of el.options) {
        if (opt.value) options.push(opt.textContent.trim());
      }
    }

    schema.push({
      id: fieldId,
      name: fieldName,
      label: fieldLabel,
      type: fieldType,
      required: isRequired,
      options,
      placeholder: el.placeholder || "",
    });
  }

  return schema;
}

function resolveLabel(el) {
  if (el.id) {
    const label = document.querySelector(`label[for="${CSS.escape(el.id)}"]`);
    if (label) return label.textContent.trim();
  }

  const parentLabel = el.closest("label");
  if (parentLabel) {
    const clone = parentLabel.cloneNode(true);
    clone.querySelectorAll("input, select, textarea").forEach((inp) => inp.remove());
    const text = clone.textContent.trim();
    if (text) return text;
  }

  const ariaLabel = el.getAttribute("aria-label");
  if (ariaLabel) return ariaLabel.trim();

  const ariaLabelledBy = el.getAttribute("aria-labelledby");
  if (ariaLabelledBy) {
    const ref = document.getElementById(ariaLabelledBy);
    if (ref) return ref.textContent.trim();
  }

  return el.placeholder || el.name || el.id || "Unlabeled Field";
}

/* ══════════════════════════════════════════════════════════════════
   highlightDecisions (original, preserved)
   ══════════════════════════════════════════════════════════════════ */

function highlightDecisions(decisions) {
  if (!Array.isArray(decisions)) return { success: false, filled: 0, errors: ["No decisions provided"] };

  let filled = 0;
  const errors = [];

  for (const d of decisions) {
    const el = findElement(d.field_id, d.label);
    if (!el) {
      if (d.action === "fill") {
        errors.push(`Field '${d.label || d.field_id}' not found in DOM`);
      }
      continue;
    }

    el.style.removeProperty("outline");
    el.style.removeProperty("background-color");
    el.removeAttribute("title");

    switch (d.action) {
      case "fill":
        el.style.outline = "2px solid #16a34a";
        if (d.value !== null && d.value !== undefined) {
          try {
            setFieldValue(el, d.value);
            filled++;
          } catch (e) {
            errors.push(`Field '${d.label || d.field_id}': ${e.message}`);
          }
        }
        break;

      case "skip":
        el.style.backgroundColor = "#f3f4f6";
        el.title = `Skipped: ${d.reasoning || "No reason provided"}`;
        break;

      case "flag":
        el.style.outline = "3px solid #dc2626";
        el.title = `Needs manual input: ${d.reasoning || "Required but not found in transcript"}`;
        el.scrollIntoView({ behavior: "smooth", block: "center" });
        break;
    }
  }

  return { success: errors.length === 0, filled, errors };
}

function findElement(fieldId, labelText) {
  if (fieldId) {
    const byId = document.getElementById(fieldId);
    if (byId) return byId;
    const byName = document.querySelector(`[name="${CSS.escape(fieldId)}"]`);
    if (byName) return byName;
  }

  if (labelText) {
    const labels = document.querySelectorAll("label");
    for (const label of labels) {
      if (label.textContent.trim().toLowerCase().includes(labelText.toLowerCase())) {
        const forId = label.getAttribute("for");
        if (forId) {
          const el = document.getElementById(forId);
          if (el) return el;
        }
        const nested = label.querySelector("input, select, textarea");
        if (nested) return nested;
      }
    }
  }

  return null;
}

function setFieldValue(el, value) {
  if (el.tagName === "SELECT") {
    for (const opt of el.options) {
      if (
        opt.value === value ||
        opt.textContent.trim().toLowerCase() === value.toLowerCase()
      ) {
        el.value = opt.value;
        break;
      }
    }
  } else if (el.type === "checkbox" || el.type === "radio") {
    el.checked = ["true", "yes", "1", "on"].includes(value.toLowerCase());
  } else {
    el.value = value;
  }

  el.dispatchEvent(new Event("input", { bubbles: true }));
  el.dispatchEvent(new Event("change", { bubbles: true }));
}

/* ══════════════════════════════════════════════════════════════════
   Message Listener — handles all communication from popup/background
   ══════════════════════════════════════════════════════════════════ */

let _lastSnapshot = null;

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.action === "extractSchema") {
    sendResponse({ schema: extractFormSchema() });
    return true;
  }

  if (message.action === "highlightDecisions" || message.action === "fillAndHighlight") {
    sendResponse(highlightDecisions(message.decisions));
    return true;
  }

  if (message.action === "getPageSnapshot") {
    _lastSnapshot = getPageSnapshot();
    const { _rawElements, ...cleanSnapshot } = _lastSnapshot;
    sendResponse({ snapshot: cleanSnapshot });
    return true;
  }

  if (message.action === "executeAction") {
    if (!_lastSnapshot || !_lastSnapshot._rawElements) {
      sendResponse({ success: false, error: "No snapshot available. Call getPageSnapshot first." });
      return true;
    }
    const result = executeAction(message.navAction, _lastSnapshot._rawElements);
    sendResponse(result);
    return true;
  }

  if (message.action === "waitForStable") {
    const timeout = message.timeout || 5000;
    waitForStable(timeout).then((result) => sendResponse(result));
    return true;
  }

  if (message.action === "ping") {
    sendResponse({ alive: true, url: window.location.href });
    return true;
  }

  return false;
});
