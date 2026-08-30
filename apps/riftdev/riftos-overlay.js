(() => {
  "use strict";

  const qs = new URLSearchParams(location.search);
  if (window.parent === window || qs.get("riftos") !== "1") return;

  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? "").replace(/[&<>"']/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;"
  })[char]);

  function token() {
    try { return (localStorage.getItem("gh_token") || "").trim(); }
    catch (_) { return ""; }
  }

  function syncTokenToRiftOS() {
    window.parent.postMessage({ type: "riftdev:github-token", token: token() }, location.origin);
  }

  function repo() { return $("repoSelect")?.value || ""; }
  function branch() { return $("branchSelect")?.value || ""; }

  async function gh(path, options = {}) {
    const headers = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(options.headers || {})
    };
    const auth = token();
    if (auth) headers.Authorization = `Bearer ${auth}`;
    const response = await fetch(`https://api.github.com${path}`, { ...options, headers });
    if (!response.ok) {
      let detail = "";
      try { detail = (await response.json())?.message || ""; } catch (_) {}
      throw new Error(`GitHub ${response.status}${detail ? `: ${detail}` : ""}`);
    }
    if (response.status === 204) return null;
    return response.json();
  }

  function injectStyles() {
    if ($("riftDevOverlayStyle")) return;
    const style = document.createElement("style");
    style.id = "riftDevOverlayStyle";
    style.textContent = `
      html,body{height:100%;min-height:0;overflow:hidden!important;overscroll-behavior:none}
      body.riftos-riftdev{margin:0;min-height:0}
      body.riftos-riftdev .app-container{height:100dvh!important;min-height:0!important;max-height:100dvh!important;overflow:hidden!important;padding:max(8px,env(safe-area-inset-top)) 8px max(8px,env(safe-area-inset-bottom))!important;box-sizing:border-box}
      body.riftos-riftdev #explorerView.page-view.active{overflow-y:auto!important;overflow-x:hidden!important;-webkit-overflow-scrolling:touch!important;touch-action:pan-y!important;min-height:0!important;padding-bottom:4px}
      body.riftos-riftdev #editorView.page-view.active{overflow:hidden!important;min-height:0!important}
      body.riftos-riftdev .toolbar{overflow-x:auto!important;overflow-y:hidden!important;flex:0 0 auto!important;-webkit-overflow-scrolling:touch!important;touch-action:pan-x!important;padding-bottom:3px}
      body.riftos-riftdev .config-panel,body.riftos-riftdev .app-header{flex:0 0 auto!important}
      body.riftos-riftdev #explorerSection{min-height:240px!important;flex:1 0 240px!important}
      body.riftos-riftdev .file-tree{overflow-y:auto!important;-webkit-overflow-scrolling:touch!important;touch-action:pan-y!important}
      body.riftos-riftdev .editor-section,body.riftos-riftdev .workspace-panes,body.riftos-riftdev .editor-workspace,body.riftos-riftdev .editor-wrapper{min-height:0!important}
      body.riftos-riftdev .editor-textarea{overflow:auto!important;-webkit-overflow-scrolling:touch!important;touch-action:pan-x pan-y!important}
      body.riftos-riftdev .editor-header,body.riftos-riftdev .breadcrumb-bar,body.riftos-riftdev .block-navigation-bar{ -webkit-overflow-scrolling:touch!important;touch-action:pan-x!important}
      body.riftos-riftdev .app-header{padding-top:max(8px,env(safe-area-inset-top));}
      .riftos-devbar{display:flex;gap:7px;align-items:center;flex-wrap:wrap;padding:7px 8px;border-bottom:1px solid #303742;background:#0c1118;position:sticky;top:0;z-index:1500;flex:0 0 auto}
      .riftos-devbar strong{font-size:12px;letter-spacing:.08em;color:#9fb3ca;margin-right:auto}
      .riftos-devbtn{border:1px solid #3b4656;background:#182231;color:#eef4fb;border-radius:8px;padding:7px 9px;font:700 12px system-ui,-apple-system,sans-serif}
      .riftos-devbtn.primary{background:#eaf2fb;color:#0b1118}
      .riftos-actions-overlay{position:fixed;inset:0;z-index:300000;background:rgba(3,7,12,.86);display:flex;align-items:stretch;justify-content:center;padding:max(10px,env(safe-area-inset-top)) 10px max(10px,env(safe-area-inset-bottom));box-sizing:border-box}
      .riftos-actions-card{width:min(820px,100%);height:100%;display:flex;flex-direction:column;background:#0d131b;color:#eef4fb;border:1px solid #344154;border-radius:14px;overflow:hidden;box-shadow:0 20px 70px rgba(0,0,0,.5)}
      .riftos-actions-head{display:flex;align-items:center;gap:8px;padding:11px;border-bottom:1px solid #2a3544}
      .riftos-actions-head strong{flex:1;overflow-wrap:anywhere}
      .riftos-actions-body{flex:1;min-height:0;overflow:auto;-webkit-overflow-scrolling:touch;touch-action:pan-y}
      .riftos-actions-row{display:flex;align-items:flex-start;gap:8px;padding:11px;border-bottom:1px solid #212b38}
      .riftos-actions-row>div{flex:1;min-width:0}.riftos-actions-row strong,.riftos-actions-row span,.riftos-actions-row small{display:block;overflow-wrap:anywhere}
      .riftos-actions-row small{color:#93a1b3;margin-top:4px}.riftos-actions-state{font:800 10px system-ui;padding:5px 7px;border:1px solid #3a4657;border-radius:999px;text-transform:uppercase}
      .riftos-actions-empty{padding:28px;text-align:center;color:#9caabc}.riftos-log{margin:0;padding:12px;white-space:pre-wrap;overflow-wrap:anywhere;font:11px/1.42 ui-monospace,SFMono-Regular,Menlo,monospace;color:#dce5ef}
    `;
    document.head.append(style);
  }

  function modal() {
    let overlay = $("riftDevActionsOverlay");
    if (overlay) return overlay;
    overlay = document.createElement("div");
    overlay.id = "riftDevActionsOverlay";
    overlay.className = "riftos-actions-overlay";
    overlay.hidden = true;
    overlay.innerHTML = `
      <section class="riftos-actions-card">
        <header class="riftos-actions-head">
          <strong id="riftDevActionsTitle">GitHub Actions</strong>
          <button class="riftos-devbtn" id="riftDevActionsRefresh" type="button">Refresh</button>
          <button class="riftos-devbtn" id="riftDevActionsClose" type="button">Close</button>
        </header>
        <div class="riftos-actions-body" id="riftDevActionsBody"></div>
      </section>`;
    document.body.append(overlay);
    $("riftDevActionsClose").onclick = () => { overlay.hidden = true; };
    $("riftDevActionsRefresh").onclick = () => loadRuns();
    return overlay;
  }

  function currentRepoParts() {
    const value = repo();
    const match = String(value).match(/^([^/]+)\/([^/]+)$/);
    if (!match) throw new Error("Select a GitHub repository first.");
    return { owner: match[1], name: match[2], full: value };
  }

  async function loadRuns() {
    const overlay = modal();
    overlay.hidden = false;
    const body = $("riftDevActionsBody");
    const title = $("riftDevActionsTitle");
    try {
      const selected = currentRepoParts();
      const selectedBranch = branch();
      title.textContent = `Actions · ${selected.full}${selectedBranch ? ` · ${selectedBranch}` : ""}`;
      body.innerHTML = `<div class="riftos-actions-empty">Loading workflow runs…</div>`;
      const branchQuery = selectedBranch ? `&branch=${encodeURIComponent(selectedBranch)}` : "";
      const data = await gh(`/repos/${encodeURIComponent(selected.owner)}/${encodeURIComponent(selected.name)}/actions/runs?per_page=20${branchQuery}`);
      const runs = data.workflow_runs || [];
      if (!runs.length) {
        body.innerHTML = `<div class="riftos-actions-empty">No workflow runs found.</div>`;
        return;
      }
      body.innerHTML = runs.map(run => `
        <div class="riftos-actions-row">
          <div>
            <strong>${esc(run.name || "Workflow")}</strong>
            <span>${esc(run.display_title || "")}</span>
            <small>${esc(run.head_branch || "")} · ${esc(run.event || "")} · ${new Date(run.created_at).toLocaleString()}</small>
          </div>
          <span class="riftos-actions-state">${esc(run.conclusion || run.status || "?")}</span>
          <button class="riftos-devbtn" data-riftdev-run="${run.id}" type="button">Jobs</button>
        </div>`).join("");
      body.querySelectorAll("[data-riftdev-run]").forEach(button => {
        button.onclick = () => loadJobs(Number(button.dataset.riftdevRun));
      });
    } catch (error) {
      body.innerHTML = `<div class="riftos-actions-empty">${esc(error.message || error)}</div>`;
    }
  }

  async function loadJobs(runId) {
    const body = $("riftDevActionsBody");
    body.innerHTML = `<div class="riftos-actions-empty">Loading jobs…</div>`;
    try {
      const selected = currentRepoParts();
      const data = await gh(`/repos/${encodeURIComponent(selected.owner)}/${encodeURIComponent(selected.name)}/actions/runs/${runId}/jobs?per_page=100`);
      const jobs = data.jobs || [];
      body.innerHTML = `<div class="riftos-actions-row"><button class="riftos-devbtn" id="riftDevBackRuns" type="button">← Runs</button><div><strong>Run ${runId}</strong></div></div>` + (jobs.length ? jobs.map(job => `
        <div class="riftos-actions-row">
          <div><strong>${esc(job.name || "Job")}</strong><small>${esc(job.status || "")} · ${esc(job.conclusion || "running")}</small></div>
          <button class="riftos-devbtn" data-riftdev-log="${job.id}" type="button">Log</button>
        </div>`).join("") : `<div class="riftos-actions-empty">No jobs in this run.</div>`);
      $("riftDevBackRuns").onclick = loadRuns;
      body.querySelectorAll("[data-riftdev-log]").forEach(button => {
        button.onclick = () => loadJobLog(runId, Number(button.dataset.riftdevLog));
      });
    } catch (error) {
      body.innerHTML = `<div class="riftos-actions-empty">${esc(error.message || error)}</div>`;
    }
  }

  async function loadJobLog(runId, jobId) {
    const body = $("riftDevActionsBody");
    body.innerHTML = `<div class="riftos-actions-empty">Loading job log…</div>`;
    try {
      const selected = currentRepoParts();
      const headers = { Accept: "text/plain" };
      if (token()) headers.Authorization = `Bearer ${token()}`;
      const response = await fetch(`https://api.github.com/repos/${encodeURIComponent(selected.owner)}/${encodeURIComponent(selected.name)}/actions/jobs/${jobId}/logs`, { headers, redirect: "follow" });
      if (!response.ok) throw new Error(`GitHub ${response.status}: could not load job log.`);
      let text = await response.text();
      if (text.length > 260000) text = `…log truncated to last 260 KB…\n${text.slice(-260000)}`;
      body.innerHTML = `<div class="riftos-actions-row"><button class="riftos-devbtn" id="riftDevBackJobs" type="button">← Jobs</button><div><strong>Job ${jobId}</strong></div></div><pre class="riftos-log"></pre>`;
      body.querySelector(".riftos-log").textContent = text;
      $("riftDevBackJobs").onclick = () => loadJobs(runId);
    } catch (error) {
      body.innerHTML = `<div class="riftos-actions-row"><button class="riftos-devbtn" id="riftDevBackJobs" type="button">← Jobs</button></div><div class="riftos-actions-empty">${esc(error.message || error)}</div>`;
      $("riftDevBackJobs").onclick = () => loadJobs(runId);
    }
  }

  function installBar() {
    if ($("riftDevNativeBar")) return;
    const anchor = $("appToolbar") || $("explorerView") || document.body.firstElementChild;
    if (!anchor) return;
    const bar = document.createElement("div");
    bar.id = "riftDevNativeBar";
    bar.className = "riftos-devbar";
    bar.innerHTML = `
      <strong>RIFTDEV · RIFTOS</strong>
      <button class="riftos-devbtn" id="riftDevShellBtn" type="button">&gt;_ Shell</button>
      <button class="riftos-devbtn primary" id="riftDevActionsBtn" type="button">Actions</button>`;
    anchor.parentElement?.insertBefore(bar, anchor);
    $("riftDevShellBtn").onclick = () => window.parent.postMessage({ type: "riftdev:open-shell" }, location.origin);
    $("riftDevActionsBtn").onclick = loadRuns;
  }

  function install() {
    document.body.classList.add("riftos-riftdev");
    document.title = "RiftDev";
    const heading = document.querySelector(".app-header h1");
    if (heading) heading.textContent = "RiftDev";
    injectStyles();
    installBar();
    syncTokenToRiftOS();

    $("tokenInput")?.addEventListener("input", () => setTimeout(syncTokenToRiftOS, 0));
    $("connectGhBtn")?.addEventListener("click", () => setTimeout(syncTokenToRiftOS, 500));
    window.addEventListener("storage", event => {
      if (event.key === "gh_token") syncTokenToRiftOS();
    });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", install, { once: true });
  else install();
})();
