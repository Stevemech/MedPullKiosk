/**
 * Chrome Extension API mock for Jest.
 *
 * Provides stubs for chrome.runtime, chrome.storage.{sync,session},
 * chrome.scripting, chrome.tabs, and chrome.alarms.
 *
 * @param {Object} initialStorage  Pre-populated storage key/value pairs.
 * @returns {Object} A chrome-shaped mock object.
 */
function createChromeMock(initialStorage = {}) {
  const store = { ...initialStorage };
  const sessionStore = {};
  const messageListeners = [];
  const alarmListeners = [];

  function makeStorageArea(backing) {
    return {
      get: jest.fn((keys, callback) => {
        let keyList;
        if (typeof keys === "string") keyList = [keys];
        else if (Array.isArray(keys)) keyList = keys;
        else keyList = Object.keys(keys || {});

        const result = {};
        for (const key of keyList) {
          if (backing[key] !== undefined) result[key] = backing[key];
        }
        if (typeof callback === "function") callback(result);
        return Promise.resolve(result);
      }),
      set: jest.fn((items, callback) => {
        Object.assign(backing, items);
        if (typeof callback === "function") callback();
        return Promise.resolve();
      }),
      _store: backing,
    };
  }

  const mock = {
    runtime: {
      sendMessage: jest.fn().mockResolvedValue({}),
      onMessage: {
        addListener: jest.fn((listener) => messageListeners.push(listener)),
        _listeners: messageListeners,
      },
      lastError: null,
    },

    storage: {
      sync: makeStorageArea(store),
      session: makeStorageArea(sessionStore),
    },

    scripting: {
      executeScript: jest
        .fn()
        .mockResolvedValue([{ result: [] }]),
    },

    tabs: {
      query: jest
        .fn()
        .mockResolvedValue([{ id: 1, url: "https://test.eclinicalworks.com" }]),
      sendMessage: jest
        .fn()
        .mockResolvedValue({ success: true, filled: 3, errors: [] }),
    },

    alarms: {
      create: jest.fn(),
      clear: jest.fn(),
      onAlarm: {
        addListener: jest.fn((listener) => alarmListeners.push(listener)),
        _listeners: alarmListeners,
      },
    },
  };

  return mock;
}

module.exports = { createChromeMock };
