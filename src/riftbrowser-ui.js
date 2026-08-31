// Stable RiftBrowser entrypoint. The implementation lives in a separate module
// so the shell keeps a small, cache-friendly import while the browser runtime
// can evolve independently.
import "./riftbrowser-ui-runtime.js";
