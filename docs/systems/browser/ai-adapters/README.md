# AI Site Adapter Registry

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

The registry isolates supported AI-site DOM selectors from the generic Browser MCP compatibility state machine.

It provides only semantic selectors the current compatibility asset actually consumes:
- composer;
- stop;
- assistant messages;
- user messages.

There is intentionally no Send selector. Browser MCP manual-send mode never clicks a site's Send control.

## Source ownership

- `android/app/src/main/assets/adapters/ai-adapter-registry.js` — registry and hostname resolution.
- `android/app/src/main/assets/riftbrowser-mcp-app.js` — only live consumer.
- `scripts/test-rift-ai-adapters.mjs` — source-level registry contract test.

## Supported adapters

Named adapters:
- ChatGPT;
- Gemini;
- Google AI;
- Claude;
- Copilot;
- generic fallback.

Hostname resolution:
- ChatGPT/OpenAI-like hostnames -> chatgpt adapter;
- GitHub/Copilot -> copilot;
- gemini.google.com -> gemini;
- google.com/subdomains -> google;
- claude.ai -> claude;
- otherwise generic.

Actual native injection remains separately restricted by the Browser MCP exact-origin allowlist. Registry hostname resolution alone grants no native bridge.

## Semantic groups

### composer

Used only to stage capability context and MCP results into a visible editable field.

### stop

Used to recognize whether an assistant response is still actively streaming before treating an incomplete tool envelope as malformed.

### assistant

Used to identify assistant turns for historical baselining, newest-message checks and tool-envelope parsing.

### user

Used to identify new user turns for arming/context staging and to compact previously staged Rift result/context messages.

## Generic fallback

The generic adapter carries broad but bounded selector families for composer/stop/assistant/user.

Failure to match a site should degrade browser compatibility behavior; it must never broaden native authority.

## Removed stale surface

The former `send` selector group was removed during this audit.

No current consumer referenced it, and Browser MCP explicitly forbids automatic Send clicks. Keeping the field implied an automation capability the live architecture intentionally does not have.

## Non-ownership boundaries

Adapters do not own:
- origin allowlist;
- native MCP bridge;
- tool permissions;
- tool execution;
- browser navigation;
- stored credentials or account data.

They inspect only DOM structure exposed to the loaded page.

## Critical invariants

- all adapters expose composer/stop/assistant/user arrays;
- no Send selector/control contract returns;
- hostname resolver remains deterministic;
- generic fallback grants no extra native capability;
- selector drift cannot bypass native MCP origin/permission checks.

## Failure signatures

- one supported site stops recognizing messages -> its selector group;
- tool parser fires on historical/navigation content -> assistant/user selectors too broad;
- incomplete streaming calls are rejected too early -> stop selector drift;
- composer cannot stage context/result -> composer selector drift;
- Send selector or click automation returns -> manual-send architecture regression;
- unsupported host gains native MCP merely because generic adapter resolves -> origin-boundary regression elsewhere.

## Fix map

Per-site selectors/hostname resolution -> `ai-adapter-registry.js`.

Generic tool-loop behavior -> `riftbrowser-mcp-app.js`.

Allowed HTTPS origins -> `RiftBrowserMcpAppBridge.kt`.

## Validation

Source verification must confirm:
- each named adapter has the four live selector arrays;
- consumer references only those groups;
- no `siteAdapter.send`;
- no Send selectors in registry contract;
- hostname resolution matches tests;
- fallback remains generic.

Real-site DOM validation remains an installed-browser/manual test because external site markup changes independently of RiftOS source.
