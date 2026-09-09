#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/java/com/riftos/app/RiftNativeDispatcher.kt"
text = path.read_text(encoding="utf-8")

if "private val localAi = RiftLocalAi(activity)" not in text:
    needle = '    private val secrets = RiftSecretStore(activity)\n'
    if text.count(needle) != 1:
        raise SystemExit("RiftNativeDispatcher secrets stanza changed unexpectedly")
    text = text.replace(needle, needle + '    private val localAi = RiftLocalAi(activity)\n', 1)

old_shutdown = '    fun shutdown() { executor.shutdownNow() }'
new_shutdown = '    fun shutdown() { localAi.shutdown(); executor.shutdownNow() }'
if old_shutdown in text:
    text = text.replace(old_shutdown, new_shutdown, 1)
elif new_shutdown not in text:
    raise SystemExit("RiftNativeDispatcher shutdown stanza changed unexpectedly")

if '"ai.local.info" -> localAi.info()' not in text:
    needle = '        "notifications.show" -> showNotification(args.optString("title", "RiftOS"), args.optString("body"))\n'
    if text.count(needle) != 1:
        raise SystemExit("RiftNativeDispatcher notifications stanza changed unexpectedly")
    additions = '''        "ai.local.info" -> localAi.info()\n        "ai.local.status" -> localAi.status()\n        "ai.local.models" -> localAi.models()\n        "ai.local.start" -> localAi.start(args)\n        "ai.local.stop" -> localAi.stop()\n        "ai.local.chat" -> localAi.submitChat(args)\n        "ai.local.chatResult" -> localAi.chatResult(args)\n        "ai.local.log" -> localAi.log()\n'''
    text = text.replace(needle, needle + additions, 1)

path.write_text(text, encoding="utf-8")
print("Patched RiftNativeDispatcher with local gpt-oss routes")
