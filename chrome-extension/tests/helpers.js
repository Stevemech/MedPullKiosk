const fs = require("fs");
const path = require("path");

const SRC_DIR = path.resolve(__dirname, "..", "chrome-extension");

function readSource(filename) {
  return fs.readFileSync(path.join(SRC_DIR, filename), "utf-8");
}

/**
 * Load a browser script via `new Function`, injecting named dependencies
 * and returning the listed top-level function declarations.
 *
 * Dependencies are passed as parameters (shadowing globals), so the
 * script uses the mocks for those names while still resolving other
 * identifiers (document, Event, …) from the jsdom global scope.
 */
function loadScript(filename, deps, exportNames) {
  const code = readSource(filename);
  const wrappedCode = `${code}\nreturn { ${exportNames.join(", ")} };`;
  const depNames = Object.keys(deps);
  const depValues = Object.values(deps);
  const factory = new Function(...depNames, wrappedCode);
  return factory(...depValues);
}

module.exports = { readSource, loadScript, SRC_DIR };
