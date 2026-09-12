# RiftOS Transfer Architecture

Filesystem transfers use a queued model.

Flow:

UI -> RiftFS -> RiftTransferQueue -> Native Dispatcher -> Android storage

Rules:
- Large operations must not block UI execution.
- Copy and move operations share the same queue.
- Transfer IDs are preserved for progress reporting.
- Future work: chunk scheduling, cancellation, pause/resume.
