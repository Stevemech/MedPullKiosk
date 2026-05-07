/**
 * Jest global setup — polyfills for APIs missing in jsdom.
 */

/* CSS.escape — not implemented in jsdom */
if (!global.CSS) global.CSS = {};
if (!global.CSS.escape) {
  global.CSS.escape = function (value) {
    const str = String(value);
    return str.replace(/([^\w-])/g, "\\$1");
  };
}

/* Element.scrollIntoView — stub for jsdom */
if (typeof Element !== "undefined" && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = function () {};
}

/* AbortSignal.timeout — may be missing in older Node/jsdom */
if (typeof AbortSignal !== "undefined" && !AbortSignal.timeout) {
  AbortSignal.timeout = function (ms) {
    const controller = new AbortController();
    setTimeout(() => controller.abort(), ms);
    return controller.signal;
  };
}
