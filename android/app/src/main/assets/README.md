# Host packaged assets

Only runtime assets explicitly used by Android host adapters belong here, including browser MCP adapter payloads. Asset packaging and source-to-APK comparison are validated by the user-triggered Riftos-builder final artifact verifier.

No embedded Core/Shell source, old web desktop app, language proofs, build recipes, secret files or TODOs may be packaged as assets. The selected independent Core/Shell DEX revisions are separately staged and activated by the user.
