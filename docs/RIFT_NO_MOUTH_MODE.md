# Rift no-mouth mode

This build keeps the Rift MCP tool layer but disables the ChatGPT Web composer automation path.

Kept:
- MCP runtime
- tool discovery
- native bridge
- workspace tools

Disabled:
- automatic composer submission
- AI task relay loop
- fake user-message round trips

The goal is to test tool infrastructure without driving the web chat UI.
