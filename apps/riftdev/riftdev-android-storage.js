(() => {
  "use strict";

  const parentWindow = window.parent;
  const workspace = () => parentWindow.RiftWorkspace;
  const nativeApi = () => parentWindow.RiftAndroidAPI;
  const visible = path => path && path !== ".rift" && !path.startsWith(".rift/");
  const normalize = value => String(value || "").replace(/\\/g, "/").replace(/^\/+|\/+$/g, "").split("/").filter(p => p && p !== "." && p !== "..").join("/");

  async function rows() {
    const ws = workspace();
    if (!ws?.available) throw new Error("RiftWorkspace Android bridge is unavailable.");
    return (await ws.list("", { recursive: true, includeHidden: true })).filter(row => visible(row.path));
  }

  async function getRecord(store, key) {
    const path = normalize(key);
    if (!path) return undefined;
    const ws = workspace();
    const stat = await ws.stat(path).catch(() => null);
    if (!stat) return undefined;
    if (store === "files") {
      if (stat.kind !== "file") return undefined;
      return { name: path, content: String(await ws.readText(path) ?? "") };
    }
    if (stat.kind !== "directory") return undefined;
    return { path };
  }

  async function getAll(store) {
    const ws = workspace();
    const list = await rows();
    if (store === "folders") return list.filter(row => row.kind === "directory").map(row => ({ path: row.path }));
    const files = list.filter(row => row.kind === "file");
    return Promise.all(files.map(async row => ({ name: row.path, content: String(await ws.readText(row.path) ?? "") })));
  }

  async function putRecord(store, record) {
    const ws = workspace();
    if (store === "folders") {
      const path = normalize(record?.path);
      if (!path) return record;
      await ws.mkdir(path).catch(async error => { if (!(await ws.stat(path).catch(() => null))) throw error; });
      return { path };
    }
    const name = normalize(record?.name);
    if (!name) throw new Error("Workspace file path is required.");
    await ws.writeText(name, String(record?.content ?? ""));
    return { name, content: String(record?.content ?? "") };
  }

  async function deleteRecord(store, key) {
    const path = normalize(key);
    if (!path) return true;
    const ws = workspace();
    const stat = await ws.stat(path).catch(() => null);
    if (!stat) return true;
    if (store === "files" && stat.kind !== "file") return true;
    if (store === "folders" && stat.kind !== "directory") return true;
    await ws.remove(path);
    return true;
  }

  async function clearStore(store) {
    const ws = workspace();
    const list = await rows();
    if (store === "files") {
      for (const row of list.filter(row => row.kind === "file")) await ws.remove(row.path).catch(() => {});
    } else {
      const dirs = list.filter(row => row.kind === "directory").sort((a, b) => b.path.length - a.path.length);
      for (const row of dirs) await ws.remove(row.path).catch(() => {});
    }
    return true;
  }

  function transaction(storeNames) {
    const names = Array.isArray(storeNames) ? storeNames : [storeNames];
    let chain = Promise.resolve();
    let completionScheduled = false;
    const tx = { oncomplete: null, onerror: null, onabort: null, error: null };

    const scheduleComplete = () => {
      if (completionScheduled) return;
      completionScheduled = true;
      setTimeout(async () => {
        completionScheduled = false;
        try { await chain; tx.oncomplete?.({ target: tx }); }
        catch (error) { tx.error = error; }
      }, 0);
    };

    const enqueue = task => {
      const req = { result: undefined, error: null, onsuccess: null, onerror: null };
      chain = chain.then(task).then(value => {
        req.result = value;
        queueMicrotask(() => req.onsuccess?.({ target: req }));
        return value;
      }, error => {
        req.error = error; tx.error = error;
        queueMicrotask(() => req.onerror?.({ target: req }));
        queueMicrotask(() => tx.onerror?.({ target: tx }));
        throw error;
      });
      scheduleComplete();
      return req;
    };

    tx.objectStore = name => {
      if (!names.includes(name)) throw new Error(`Object store is not in this transaction: ${name}`);
      return {
        get: key => enqueue(() => getRecord(name, key)),
        getAll: () => enqueue(() => getAll(name)),
        getAllKeys: () => enqueue(async () => (await getAll(name)).map(row => name === "files" ? row.name : row.path)),
        put: record => enqueue(() => putRecord(name, record)),
        delete: key => enqueue(() => deleteRecord(name, key)),
        clear: () => enqueue(() => clearStore(name))
      };
    };
    scheduleComplete();
    return tx;
  }

  const db = {
    objectStoreNames: { contains: name => name === "files" || name === "folders" },
    createObjectStore: name => ({ name }),
    transaction,
    close() {},
    onversionchange: null
  };

  const RiftDevAndroidDB = Object.freeze({
    open() {
      const req = { result: db, error: null, onupgradeneeded: null, onsuccess: null, onerror: null, onblocked: null };
      setTimeout(() => req.onsuccess?.({ target: req }), 0);
      return req;
    }
  });
  window.RiftDevAndroidDB = RiftDevAndroidDB;

  async function hydrateSecret() {
    try {
      const secret = await nativeApi()?.secrets?.get?.("github.pat");
      const value = secret?.value && secret.value !== null ? String(secret.value) : "";
      if (value) localStorage.setItem("gh_token", value);
      else localStorage.removeItem("gh_token");
    } catch (error) {
      console.warn("[RiftDev Android] GitHub token hydrate skipped", error);
    }
  }

  window.RiftDevAndroidReady = (async () => {
    await parentWindow.RiftOSCore?.ready;
    await workspace()?.local?.ready;
    await hydrateSecret();
    return true;
  })();

  window.RiftDevAndroidBindSecrets = () => {
    const input = document.getElementById("tokenInput");
    if (input && !input.dataset.androidSecretBound) {
      input.dataset.androidSecretBound = "1";
      input.addEventListener("input", () => {
        const value = input.value.trim();
        if (value) nativeApi()?.secrets?.set?.("github.pat", value).catch(console.warn);
        else nativeApi()?.secrets?.remove?.("github.pat").catch(console.warn);
      });
    }
    window.addEventListener("pagehide", () => { try { localStorage.removeItem("gh_token"); } catch {} }, { once: true });
  };

  console.info("[RiftDev Android] native RiftWorkspace storage bridge ready");
})();
