# RiftEngine WebKit feature intent for the clean iPhone/WASM port.
#
# Phase 0 does not consume this file yet. It is checked in now so later WebKit
# bring-up work has one source of truth instead of scattering feature toggles
# through scripts.

set(RIFT_WEBKIT_KEEP
  ENABLE_CSS_SELECTORS_LEVEL4
  ENABLE_FETCH_API
  ENABLE_INDEXED_DATABASE
  ENABLE_WEBASSEMBLY
)

set(RIFT_WEBKIT_DISABLE
  ENABLE_GAMEPAD
  ENABLE_MEDIA_CAPTURE
  ENABLE_MEDIA_RECORDER
  ENABLE_MEDIA_SESSION
  ENABLE_MEDIA_SOURCE
  ENABLE_NOTIFICATIONS
  ENABLE_REMOTE_INSPECTOR
  ENABLE_SPEECH_SYNTHESIS
  ENABLE_VIDEO
  ENABLE_WEB_AUDIO
  ENABLE_WEB_CODECS
  ENABLE_WEB_RTC
  ENABLE_WEBDRIVER
  ENABLE_WEBGPU
)

# The exact upstream option names are validated against the WebKit revision we
# pin for Milestone 2. Unknown/renamed options must fail the configure step
# instead of silently enabling desktop features.
