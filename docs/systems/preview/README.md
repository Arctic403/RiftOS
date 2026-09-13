# Workspace File Preview

## Purpose

`RiftPreviewActivity` provides a native Android WebView preview for RiftFS/workspace files without exposing arbitrary Android filesystem navigation to preview content.

## Source ownership

`android/app/src/main/java/com/riftos/app/RiftPreviewActivity.kt`, launched by `RiftNativeDispatcher.openPreview(root,entry)`.

## Runtime model

The Activity receives a permitted root/entry, normalizes requested path segments and serves matching local files through WebView request interception (`responseFor`). The preview WebView can navigate within the served local content and supports back navigation; it is destroyed with the Activity.

## Boundary

Path resolution stays within the approved root. Preview content should not become a general `file://` browser and should not receive the RiftOS native dispatcher/MCP bridges.

## Failure signatures

- Entry opens external browser instead of preview -> dispatcher/open type decision.
- Relative resources 404 -> request URI to local path/MIME response mapping.
- Preview can escape root -> `normalize`/`fileFor` containment bug; treat as security-critical.
- Back button closes too early -> WebView history handling.

## Fix map

Local preview serving/path/MIME/history -> `RiftPreviewActivity`.
Which files are sent to preview -> Files/open logic and dispatcher.
Actual file IO correctness -> RiftFS.

## Validation

Test HTML with relative CSS/images, plain previewable content, nested paths, encoded/traversal attempts, missing file, back navigation and Activity destruction.
