# Rift++ Editor bootstrap mirror

This directory is a **bootstrap-only mirror** of:

`workspace/rift++/standalone/editor/android`

Authority remains the Rift++ workspace. The mirror exists only so the RiftOS Builder can construct the first standalone Rift++ Editor APK.

The produced editor package:

- has package ID `com.riftpp.editor`;
- contains its own frozen S3 compiler assets;
- contains its own Rift++ frontend and preview-runtime assets;
- contains no `com.riftos` package dependency;
- contains no Codynex dependency;
- does not call RiftOS at runtime.

Once the standalone editor can package/sign Rift++ apps itself, this bootstrap lane is no longer part of the product pipeline.
