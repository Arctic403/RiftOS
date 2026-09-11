# Rift Raw Chat Protocol v1

The ChatGPT compatibility adapter uses plain-text command blocks in the conversation. JSON-RPC remains private between the page adapter and the native MCP server.

## Call

```text
[RIFT_CALL]
call scan-1 rift_workspace_exec
set finish false
set operations.0.op list
set operations.0.path workspace/RiftOS-main
set operations.0.recursive true
[RIFT_END]
```

`set` accepts dotted object paths and numeric array indexes. `true`, `false`, `null`, integers, and decimals are typed automatically; other values are strings. Quote values that contain spaces.

For multiline source text use a heredoc:

```text
[RIFT_CALL]
call write-1 rift_write_text
set path workspace/project/file.txt
set text <<RIFT_TEXT
first line
second line
RIFT_TEXT
[RIFT_END]
```

The delimiter must be a single token and the closing line must match it exactly. This also works at nested paths such as `operations.0.text`, `operations.0.edits.0.replace`, and `operations.0.hunks.0.text`.

## Result

Results are returned as `[RIFT_RESULT]` headers plus an indented plain-text body. Result bodies are capped before they are inserted into the ChatGPT composer. If a result is truncated, ChatGPT must request a narrower range or another targeted read.

## Correlation and safety

Every call still requires a unique call id. Native MCP calls retain call/session metadata, duplicate-call protection, workspace scoping, permissions, transaction rollback, and result correlation. The raw chat protocol changes only the model-facing representation.
