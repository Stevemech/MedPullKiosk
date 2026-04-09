/**
 * content.js — MedPull eClinicalWorks Assistant
 *
 * Injected into eClinicalWorks pages. Provides:
 *   - extractFormSchema()  — scans visible form elements and returns a schema array
 *   - highlightDecisions() — visually marks fields after LLM processing
 */

/* ── extractFormSchema ────────────────────────────────────────── */

/**
 * Scans the current page for all input, select, and textarea elements,
 * returning a structured schema array suitable for LLM processing.
 *
 * @returns {Array<{id: string, name: string, label: string, type: string,
 *                   required: boolean, options: string[], placeholder: string}>}
 */
function extractFormSchema() {
  const elements = document.querySelectorAll(
    "input, select, textarea"
  );
  const schema = [];

  for (const el of elements) {
    /* Skip hidden and submit/button inputs */
    if (
      el.type === "hidden" ||
      el.type === "submit" ||
      el.type === "button" ||
      el.type === "reset"
    ) {
      continue;
    }

    /* Skip invisible elements */
    const style = window.getComputedStyle(el);
    if (style.display === "none" || style.visibility === "hidden") {
      continue;
    }

    const fieldId = el.id || el.getAttribute("data-field-id") || "";
    const fieldName = el.name || "";
    const fieldLabel = resolveLabel(el);
    const fieldType = el.tagName === "SELECT" ? "select" : (el.type || "text");
    const isRequired =
      el.required ||
      el.getAttribute("aria-required") === "true" ||
      el.classList.contains("required");

    /* Collect options for select elements */
    const options = [];
    if (el.tagName === "SELECT") {
      for (const opt of el.options) {
        if (opt.value) {
          options.push(opt.textContent.trim());
        }
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

/**
 * Resolve a human-readable label for a form element by checking:
 *   1. An explicit <label> linked via `for` attribute
 *   2. An ancestor <label> wrapping the element
 *   3. aria-label or aria-labelledby
 *   4. The element's placeholder as a last resort
 */
function resolveLabel(el) {
  /* Explicit <label for="..."> */
  if (el.id) {
    const label = document.querySelector(`label[for="${CSS.escape(el.id)}"]`);
    if (label) return label.textContent.trim();
  }

  /* Ancestor <label> */
  const parentLabel = el.closest("label");
  if (parentLabel) {
    /* Remove the element's own text contribution */
    const clone = parentLabel.cloneNode(true);
    const inputs = clone.querySelectorAll("input, select, textarea");
    inputs.forEach((inp) => inp.remove());
    const text = clone.textContent.trim();
    if (text) return text;
  }

  /* ARIA attributes */
  const ariaLabel = el.getAttribute("aria-label");
  if (ariaLabel) return ariaLabel.trim();

  const ariaLabelledBy = el.getAttribute("aria-labelledby");
  if (ariaLabelledBy) {
    const ref = document.getElementById(ariaLabelledBy);
    if (ref) return ref.textContent.trim();
  }

  /* Fallback */
  return el.placeholder || el.name || el.id || "Unlabeled Field";
}

/* ── highlightDecisions ───────────────────────────────────────── */

/**
 * Visually marks form fields based on LLM decisions:
 *   - fill  → green outline, value set, input+change events dispatched
 *   - skip  → grey background with tooltip explaining why
 *   - flag  → red border with tooltip, scrolled into view
 *
 * @param {Array<{field_id: string, label: string, action: string,
 *                 value: string|null, reasoning: string}>} decisions
 * @returns {{success: boolean, filled: number, errors: string[]}}
 */
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

    /* Remove any previous MedPull styling */
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

/**
 * Find a DOM element by id first, then by name, then by label text.
 */
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

/**
 * Set a field's value and dispatch input + change events to trigger
 * eClinicalWorks's React/Angular change detection.
 */
function setFieldValue(el, value) {
  if (el.tagName === "SELECT") {
    /* Match option by text content or value */
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
    const shouldCheck = ["true", "yes", "1", "on"].includes(
      value.toLowerCase()
    );
    el.checked = shouldCheck;
  } else {
    el.value = value;
  }

  /* Dispatch events for framework change detection */
  el.dispatchEvent(new Event("input", { bubbles: true }));
  el.dispatchEvent(new Event("change", { bubbles: true }));
}

/* ── Message Listener (for popup communication) ───────────────── */
chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.action === "extractSchema") {
    const schema = extractFormSchema();
    sendResponse({ schema });
    return true;
  }

  if (message.action === "highlightDecisions") {
    const result = highlightDecisions(message.decisions);
    sendResponse(result);
    return true;
  }

  if (message.action === "fillAndHighlight") {
    const result = highlightDecisions(message.decisions);
    sendResponse(result);
    return true;
  }

  return false;
});
