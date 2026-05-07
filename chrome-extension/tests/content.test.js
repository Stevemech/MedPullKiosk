const fs = require("fs");
const path = require("path");
const { createChromeMock } = require("./__mocks__/chrome");
const { readSource } = require("./helpers");

/* ── Load fixture HTML ───────────────────────────────────────── */
const FIXTURE_HTML = fs.readFileSync(
  path.resolve(__dirname, "__fixtures__", "ecw-form.html"),
  "utf-8"
);

/* ── Exported functions from content.js ──────────────────────── */
const EXPORTS = [
  "extractFormSchema",
  "resolveLabel",
  "highlightDecisions",
  "findElement",
  "setFieldValue",
];

/**
 * content.js uses `window.getComputedStyle` to skip invisible elements.
 * jsdom's implementation is limited, so we provide a minimal mock that
 * reads inline style properties — sufficient for our test fixtures.
 */
const mockWindow = {
  getComputedStyle: (el) => ({
    display: el.style ? el.style.display : "",
    visibility: el.style ? el.style.visibility : "",
  }),
};

let fns;
let chromeMock;
let messageListener;

beforeAll(() => {
  chromeMock = createChromeMock();
  const code = readSource("content.js");
  const factory = new Function(
    "chrome",
    "window",
    `${code}\nreturn { ${EXPORTS.join(", ")} };`
  );
  fns = factory(chromeMock, mockWindow);
  messageListener = chromeMock.runtime.onMessage._listeners[0];
});

afterEach(() => {
  document.body.innerHTML = "";
});

/* ════════════════════════════════════════════════════════════════
   extractFormSchema
   ════════════════════════════════════════════════════════════════ */

describe("extractFormSchema", () => {
  test("extracts visible text inputs with id, name, type, placeholder", () => {
    document.body.innerHTML =
      '<input type="text" id="fname" name="fname" placeholder="First name" />';
    const schema = fns.extractFormSchema();
    expect(schema).toHaveLength(1);
    expect(schema[0]).toMatchObject({
      id: "fname",
      name: "fname",
      type: "text",
      placeholder: "First name",
    });
  });

  test("extracts <select> elements with their options (skips empty-value option)", () => {
    document.body.innerHTML = `
      <select id="color" name="color">
        <option value="">Choose</option>
        <option value="r">Red</option>
        <option value="g">Green</option>
      </select>`;
    const schema = fns.extractFormSchema();
    expect(schema).toHaveLength(1);
    expect(schema[0].type).toBe("select");
    expect(schema[0].options).toEqual(["Red", "Green"]);
  });

  test("extracts <textarea> elements", () => {
    document.body.innerHTML =
      '<textarea id="notes" name="notes" placeholder="Notes"></textarea>';
    const schema = fns.extractFormSchema();
    expect(schema).toHaveLength(1);
    expect(schema[0].type).toBe("textarea");
  });

  test("skips hidden inputs", () => {
    document.body.innerHTML = '<input type="hidden" id="tok" value="x" />';
    expect(fns.extractFormSchema()).toHaveLength(0);
  });

  test("skips submit / button / reset inputs", () => {
    document.body.innerHTML = `
      <input type="submit" value="Go" />
      <input type="button" value="Click" />
      <input type="reset" value="Clear" />`;
    expect(fns.extractFormSchema()).toHaveLength(0);
  });

  test("skips elements with display:none", () => {
    document.body.innerHTML =
      '<input type="text" id="x" style="display:none" />';
    expect(fns.extractFormSchema()).toHaveLength(0);
  });

  test("skips elements with visibility:hidden", () => {
    document.body.innerHTML =
      '<input type="text" id="x" style="visibility:hidden" />';
    expect(fns.extractFormSchema()).toHaveLength(0);
  });

  test("detects required via the required attribute", () => {
    document.body.innerHTML = '<input type="text" id="r" required />';
    expect(fns.extractFormSchema()[0].required).toBe(true);
  });

  test("detects required via aria-required='true'", () => {
    document.body.innerHTML =
      '<input type="text" id="r" aria-required="true" />';
    expect(fns.extractFormSchema()[0].required).toBe(true);
  });

  test("detects required via .required CSS class", () => {
    document.body.innerHTML =
      '<input type="text" id="r" class="required" />';
    expect(fns.extractFormSchema()[0].required).toBe(true);
  });

  test("marks non-required fields as required: false", () => {
    document.body.innerHTML = '<input type="text" id="opt" />';
    expect(fns.extractFormSchema()[0].required).toBe(false);
  });

  test("uses data-field-id when id is absent", () => {
    document.body.innerHTML =
      '<input type="text" data-field-id="med" name="med" />';
    expect(fns.extractFormSchema()[0].id).toBe("med");
  });

  test("full fixture yields expected field count", () => {
    document.body.innerHTML = FIXTURE_HTML;
    const schema = fns.extractFormSchema();
    // 16 visible inputs/selects/textareas minus 4 skipped (hidden, submit, button, reset)
    // minus 2 invisible = at least 10
    expect(schema.length).toBeGreaterThanOrEqual(10);

    const ids = schema.map((f) => f.id);
    expect(ids).toContain("patient_name");
    expect(ids).toContain("blood_type");
    expect(ids).toContain("chief_complaint");
    expect(ids).not.toContain("encounter_id");
    expect(ids).not.toContain("hidden_field");
    expect(ids).not.toContain("invisible_field");
  });
});

/* ════════════════════════════════════════════════════════════════
   resolveLabel
   ════════════════════════════════════════════════════════════════ */

describe("resolveLabel", () => {
  test("resolves via <label for='id'>", () => {
    document.body.innerHTML = `
      <label for="f1">Full Name</label>
      <input id="f1" />`;
    const el = document.getElementById("f1");
    expect(fns.resolveLabel(el)).toBe("Full Name");
  });

  test("resolves via ancestor <label>", () => {
    document.body.innerHTML = `
      <label>Birth Date <input id="bd" /></label>`;
    const el = document.getElementById("bd");
    expect(fns.resolveLabel(el)).toBe("Birth Date");
  });

  test("resolves via aria-label", () => {
    document.body.innerHTML =
      '<input id="cc" aria-label="Chief Complaint" />';
    const el = document.getElementById("cc");
    expect(fns.resolveLabel(el)).toBe("Chief Complaint");
  });

  test("resolves via aria-labelledby", () => {
    document.body.innerHTML = `
      <span id="lbl">Blood Pressure</span>
      <input id="bp" aria-labelledby="lbl" />`;
    const el = document.getElementById("bp");
    expect(fns.resolveLabel(el)).toBe("Blood Pressure");
  });

  test("falls back to placeholder", () => {
    document.body.innerHTML =
      '<input id="n" placeholder="Enter notes" />';
    const el = document.getElementById("n");
    expect(fns.resolveLabel(el)).toBe("Enter notes");
  });

  test("falls back to name attribute", () => {
    document.body.innerHTML = '<input name="dosage" />';
    const el = document.querySelector("input");
    expect(fns.resolveLabel(el)).toBe("dosage");
  });

  test("falls back to 'Unlabeled Field' when nothing available", () => {
    document.body.innerHTML = "<input />";
    const el = document.querySelector("input");
    expect(fns.resolveLabel(el)).toBe("Unlabeled Field");
  });
});

/* ════════════════════════════════════════════════════════════════
   findElement
   ════════════════════════════════════════════════════════════════ */

describe("findElement", () => {
  test("finds element by id", () => {
    document.body.innerHTML = '<input id="f1" />';
    expect(fns.findElement("f1", null)).toBe(document.getElementById("f1"));
  });

  test("finds element by name when id lookup fails", () => {
    document.body.innerHTML = '<input name="bp" />';
    expect(fns.findElement("bp", null)).toBe(
      document.querySelector('[name="bp"]')
    );
  });

  test("finds element by matching label text (via for attribute)", () => {
    document.body.innerHTML = `
      <label for="diag">Diagnosis</label>
      <input id="diag" />`;
    expect(fns.findElement(null, "Diagnosis")).toBe(
      document.getElementById("diag")
    );
  });

  test("finds nested input when label has no for attribute", () => {
    document.body.innerHTML = `
      <label>Weight <input id="w" /></label>`;
    expect(fns.findElement(null, "Weight")).toBe(
      document.getElementById("w")
    );
  });

  test("returns null when no match exists", () => {
    document.body.innerHTML = '<input id="a" />';
    expect(fns.findElement("zzz", "Nonexistent")).toBeNull();
  });
});

/* ════════════════════════════════════════════════════════════════
   setFieldValue
   ════════════════════════════════════════════════════════════════ */

describe("setFieldValue", () => {
  test("sets value on text input", () => {
    document.body.innerHTML = '<input type="text" id="t" />';
    const el = document.getElementById("t");
    fns.setFieldValue(el, "Hello");
    expect(el.value).toBe("Hello");
  });

  test("selects matching option on <select> by value", () => {
    document.body.innerHTML = `
      <select id="s">
        <option value="">Pick</option>
        <option value="A+">A Positive</option>
      </select>`;
    const el = document.getElementById("s");
    fns.setFieldValue(el, "A+");
    expect(el.value).toBe("A+");
  });

  test("selects matching option on <select> by text (case-insensitive)", () => {
    document.body.innerHTML = `
      <select id="s">
        <option value="">Pick</option>
        <option value="O-">O Negative</option>
      </select>`;
    const el = document.getElementById("s");
    fns.setFieldValue(el, "o negative");
    expect(el.value).toBe("O-");
  });

  test("checks a checkbox for truthy string values", () => {
    document.body.innerHTML = '<input type="checkbox" id="c" />';
    const el = document.getElementById("c");
    fns.setFieldValue(el, "yes");
    expect(el.checked).toBe(true);
  });

  test("unchecks a checkbox for falsy string values", () => {
    document.body.innerHTML = '<input type="checkbox" id="c" checked />';
    const el = document.getElementById("c");
    fns.setFieldValue(el, "no");
    expect(el.checked).toBe(false);
  });

  test("checks a radio button for 'true'", () => {
    document.body.innerHTML = '<input type="radio" id="r" />';
    const el = document.getElementById("r");
    fns.setFieldValue(el, "true");
    expect(el.checked).toBe(true);
  });

  test("dispatches input and change events", () => {
    document.body.innerHTML = '<input type="text" id="t" />';
    const el = document.getElementById("t");
    const inputSpy = jest.fn();
    const changeSpy = jest.fn();
    el.addEventListener("input", inputSpy);
    el.addEventListener("change", changeSpy);

    fns.setFieldValue(el, "X");

    expect(inputSpy).toHaveBeenCalledTimes(1);
    expect(changeSpy).toHaveBeenCalledTimes(1);
  });
});

/* ════════════════════════════════════════════════════════════════
   highlightDecisions
   ════════════════════════════════════════════════════════════════ */

describe("highlightDecisions", () => {
  test("returns {success: false} for non-array input", () => {
    const result = fns.highlightDecisions(null);
    expect(result.success).toBe(false);
    expect(result.errors).toContain("No decisions provided");
  });

  test("fill — sets green outline and fills value", () => {
    document.body.innerHTML = '<input type="text" id="f1" />';
    const decisions = [
      { field_id: "f1", label: "F1", action: "fill", value: "Test", reasoning: "" },
    ];
    const result = fns.highlightDecisions(decisions);
    const el = document.getElementById("f1");

    expect(el.style.outline).toBe("2px solid #16a34a");
    expect(el.value).toBe("Test");
    expect(result.filled).toBe(1);
    expect(result.success).toBe(true);
  });

  test("skip — sets grey background and tooltip", () => {
    document.body.innerHTML = '<input type="text" id="s1" />';
    const decisions = [
      { field_id: "s1", label: "S1", action: "skip", reasoning: "Not in transcript" },
    ];
    fns.highlightDecisions(decisions);
    const el = document.getElementById("s1");

    expect(el.style.backgroundColor).toBe("rgb(243, 244, 246)");
    expect(el.title).toContain("Not in transcript");
  });

  test("flag — sets red border and tooltip", () => {
    document.body.innerHTML = '<input type="text" id="x1" />';
    const scrollSpy = jest.fn();
    document.getElementById("x1").scrollIntoView = scrollSpy;

    const decisions = [
      { field_id: "x1", label: "X1", action: "flag", reasoning: "Needs review" },
    ];
    fns.highlightDecisions(decisions);
    const el = document.getElementById("x1");

    expect(el.style.outline).toBe("3px solid #dc2626");
    expect(el.title).toContain("Needs review");
    expect(scrollSpy).toHaveBeenCalled();
  });

  test("records error when fill target is not found in DOM", () => {
    document.body.innerHTML = "<div></div>";
    const decisions = [
      { field_id: "missing", label: "Gone", action: "fill", value: "V", reasoning: "" },
    ];
    const result = fns.highlightDecisions(decisions);
    expect(result.success).toBe(false);
    expect(result.errors[0]).toContain("Gone");
  });

  test("does not record error when skip/flag target is not found", () => {
    document.body.innerHTML = "<div></div>";
    const decisions = [
      { field_id: "missing", label: "X", action: "skip", reasoning: "N/A" },
      { field_id: "missing2", label: "Y", action: "flag", reasoning: "N/A" },
    ];
    const result = fns.highlightDecisions(decisions);
    expect(result.errors).toHaveLength(0);
  });

  test("fill with null value applies outline but does not set field", () => {
    document.body.innerHTML = '<input type="text" id="f1" value="original" />';
    const decisions = [
      { field_id: "f1", label: "F1", action: "fill", value: null, reasoning: "" },
    ];
    const result = fns.highlightDecisions(decisions);
    expect(document.getElementById("f1").value).toBe("original");
    expect(result.filled).toBe(0);
  });

  test("clears previous MedPull styling before applying new action", () => {
    document.body.innerHTML = '<input type="text" id="f1" />';
    const el = document.getElementById("f1");
    el.style.outline = "5px solid pink";
    el.style.backgroundColor = "yellow";
    el.title = "old";

    fns.highlightDecisions([
      { field_id: "f1", label: "F1", action: "skip", reasoning: "R" },
    ]);

    expect(el.style.outline).toBe("");
    expect(el.style.backgroundColor).toBe("rgb(243, 244, 246)");
  });

  test("handles mixed decisions in one call", () => {
    document.body.innerHTML = `
      <input type="text" id="a" />
      <input type="text" id="b" />
      <input type="text" id="c" />`;
    const decisions = [
      { field_id: "a", label: "A", action: "fill", value: "Val", reasoning: "" },
      { field_id: "b", label: "B", action: "skip", reasoning: "R" },
      { field_id: "c", label: "C", action: "flag", reasoning: "R" },
    ];
    const result = fns.highlightDecisions(decisions);
    expect(result.filled).toBe(1);
    expect(result.success).toBe(true);
  });
});

/* ════════════════════════════════════════════════════════════════
   Message listener
   ════════════════════════════════════════════════════════════════ */

describe("content message listener", () => {
  test("extractSchema action returns {schema}", () => {
    document.body.innerHTML = '<input type="text" id="a" name="a" />';
    const sendResponse = jest.fn();
    const result = messageListener({ action: "extractSchema" }, {}, sendResponse);

    expect(result).toBe(true);
    expect(sendResponse).toHaveBeenCalledTimes(1);
    expect(sendResponse.mock.calls[0][0].schema).toBeInstanceOf(Array);
    expect(sendResponse.mock.calls[0][0].schema.length).toBeGreaterThan(0);
  });

  test("highlightDecisions action returns highlight result", () => {
    document.body.innerHTML = '<input type="text" id="x" />';
    const sendResponse = jest.fn();
    messageListener(
      {
        action: "highlightDecisions",
        decisions: [{ field_id: "x", label: "X", action: "fill", value: "1", reasoning: "" }],
      },
      {},
      sendResponse
    );
    expect(sendResponse).toHaveBeenCalledWith(
      expect.objectContaining({ filled: 1, success: true })
    );
  });

  test("fillAndHighlight action delegates to highlightDecisions", () => {
    document.body.innerHTML = '<input type="text" id="y" />';
    const sendResponse = jest.fn();
    messageListener(
      {
        action: "fillAndHighlight",
        decisions: [{ field_id: "y", label: "Y", action: "skip", reasoning: "R" }],
      },
      {},
      sendResponse
    );
    expect(sendResponse).toHaveBeenCalledWith(
      expect.objectContaining({ success: true })
    );
  });

  test("unknown action returns false", () => {
    const sendResponse = jest.fn();
    const result = messageListener({ action: "bogus" }, {}, sendResponse);
    expect(result).toBe(false);
    expect(sendResponse).not.toHaveBeenCalled();
  });
});
