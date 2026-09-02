(() => {
  "use strict";
  const $ = id => document.getElementById(id);

  async function saveDirtyEditor() {
    const editor = $("editor");
    const path = editor?.dataset?.filename || "";
    if (!path || typeof saveFileToDb !== "function") return;
    if (typeof isDirty !== "undefined" && !isDirty) return;
    await saveFileToDb(path, editor.value);
    if (typeof updateDirtyIndicator === "function") updateDirtyIndicator(false);
  }

  async function openPreview() {
    const button = $("localTestBtn");
    const original = button?.textContent || "⚡ Local Test";
    try {
      if (button) { button.disabled = true; button.textContent = "⚡ Opening…"; }
      await saveDirtyEditor();
      if (typeof getAllWorkspaceFiles !== "function") throw new Error("Workspace read API is unavailable.");
      const files = await getAllWorkspaceFiles();
      const names = new Set(files.map(file => String(file.name || "").replace(/^\/+/, "")));
      let root = "", entry = "index.html";
      if (names.has("public/index.html")) root = "public";
      else if (!names.has("index.html")) throw new Error("Local Test needs public/index.html or index.html in the workspace.");
      const native = window.parent.RiftOSCore?.native;
      if (!native?.connected) throw new Error("Android native preview host is unavailable.");
      await native.call("preview.open", { root, entry });
    } catch (error) {
      alert("Local Test failed: " + (error?.message || error));
    } finally {
      if (button) { button.disabled = false; button.textContent = original; }
    }
  }

  $("localTestBtn")?.addEventListener("click", openPreview);
  console.info("[RiftDev Android] native Local Test ready");
})();
