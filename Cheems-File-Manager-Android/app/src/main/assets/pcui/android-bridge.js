/* Android adapter for the ORIGINAL Windows CFM app.js.
 * Keep the desktop interface and app.js unchanged; only translate its bridge.
 */
(function () {
  'use strict';
  const listeners = [];
  window.chrome = window.chrome || {};
  window.chrome.webview = {
    addEventListener: function(type, listener) {
      if (type === 'message' && typeof listener === 'function') listeners.push(listener);
    },
    postMessage: function(message) {
      if (!window.AndroidFileApi) throw new Error('Android 原生文件模块未连接');
      window.AndroidFileApi.postMessage(JSON.stringify(message));
    }
  };
  window.__cfmNativeReceive = function(raw) {
    try {
      const response = JSON.parse(raw);
      for (const listener of listeners) listener({ data: response });
    } catch (err) {
      console.error('CFM Android bridge', err);
    }
  };
})();
