/* eslint-disable @typescript-eslint/no-require-imports -- Node preload must be CommonJS. */
const { EventEmitter } = require("node:events");

// Test-server-only diagnostic threshold. This does not alter production
// processes or hide listener leaks in the application itself.
EventEmitter.defaultMaxListeners = 0;
