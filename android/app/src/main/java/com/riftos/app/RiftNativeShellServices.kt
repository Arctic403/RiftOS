package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Argument parsing only. All authority remains in existing Android-native services. */
class RiftNativeShellServices(context: Context) {
    data class Result(val output: String, val value: JSONObject? = null)

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val llm = RiftLlmDevClient(appContext)

    fun chat(args: MutableList<String>, cwd: String): Result {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "help"
        if (sub == "help") {
            require(args.isEmpty()) { "usage: chat help" }
            return Result(
                "RiftOS chat handoff bundles\n" +
                    "chat handoff <payload.json> [name]\nchat export <payload.json> [name]\nchat list\n" +
                    "chat inspect <bundle.riftchat>\nchat resume <bundle.riftchat>\nchat transcript <bundle.riftchat> [offset-chars] [max-chars]"
            )
        }
        val request = JSONObject()
        when (sub) {
            "list" -> { require(args.isEmpty()) { "usage: chat list" }; request.put("op", "list") }
            "handoff", "export" -> {
                require(args.size in 1..2) { "usage: chat $sub <payload.json> [name]" }
                val raw = args[0]
                val name = args.getOrNull(1).orEmpty()
                require(name.length <= 160) { "chat handoff name is too long" }
                request.put("op", "create").put("payloadPath", resolveDisplay(cwd, raw)).put("name", name)
            }
            "inspect", "resume" -> {
                require(args.size == 1) { "usage: chat $sub <bundle.riftchat>" }
                request.put("op", sub).put("path", resolveDisplay(cwd, args[0]))
            }
            "transcript" -> {
                require(args.size in 1..3) { "usage: chat transcript <bundle.riftchat> [offset-chars] [max-chars]" }
                val offset = if (args.size >= 2) args[1].toLongOrNull() ?: throw IllegalArgumentException("transcript offset must be an integer") else 0L
                require(offset >= 0L) { "transcript offset must be non-negative" }
                val maxChars = if (args.size >= 3) args[2].toIntOrNull() ?: throw IllegalArgumentException("transcript max-chars must be an integer") else 32768
                require(maxChars in 1..65536) { "transcript max-chars must be between 1 and 65536" }
                request.put("op", "transcript").put("path", resolveDisplay(cwd, args[0]))
                    .put("offsetChars", offset)
                    .put("maxChars", maxChars)
            }
            else -> throw IllegalArgumentException("unknown chat command: $sub")
        }
        val value = RiftChatHandoff.execute(riftRoot, request)
        return Result(value.toString(2), value)
    }

    fun devLab(args: MutableList<String>, cwd: String): Result {
        val action = args.removeFirstOrNull()?.lowercase() ?: "help"
        if (action == "help") return Result(
            "RiftOS Android-native Dev Lab\n" +
                "devlab status\ndevlab load <project-path>\ndevlab staged\ndevlab stage <project-path> <text>\n" +
                "devlab stage-file <project-path> <riftfs-source-file>\ndevlab delete <project-path>\ndevlab unstage <project-path>\n" +
                "devlab snapshot [note]\ndevlab snapshots [limit]\ndevlab load-snapshot [id|latest]\n" +
                "devlab preview [id|latest]\ndevlab publish [id|latest]\ndevlab reset\n" +
                "Web execution/HTML preview is owned by RiftBrowser."
        )
        val request = JSONObject().put("action", action).put("cwd", cwd)
        when (action) {
            "status", "staged", "reset" -> Unit
            "load", "delete", "unstage" -> request.put("path", args.removeFirstOrNull()
                ?: throw IllegalArgumentException("usage: devlab $action <project-path>"))
            "stage" -> {
                require(args.size >= 2) { "usage: devlab stage <project-path> <text>" }
                request.put("path", args.removeAt(0)).put("text", args.joinToString(" ")).put("reason", "native shell")
            }
            "stage-file" -> {
                require(args.size >= 2) { "usage: devlab stage-file <project-path> <riftfs-source-file>" }
                request.put("path", args.removeAt(0)).put("sourcePath", resolveDisplay(cwd, args.removeAt(0))).put("reason", "native shell")
            }
            "snapshot" -> request.put("note", args.joinToString(" "))
            "snapshots" -> request.put("limit", (args.firstOrNull()?.toIntOrNull() ?: 50).coerceIn(1, 200))
            "load-snapshot", "preview", "publish" -> request.put("snapshotId", args.firstOrNull() ?: "latest")
            else -> throw IllegalArgumentException(
                if (action in setOf("run","run-file","css","css-off","open"))
                    "Dev Lab web execution moved to RiftBrowser; use the native Dev Lab UI/browser runner."
                else "unknown devlab command: $action"
            )
        }
        val value = RiftNativeDevLab.execute(appContext, request)
        return Result(value.toString(2), value)
    }

    fun vortex(args: MutableList<String>, cwd: String): Result {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "help"
        if (sub == "help") {
            require(args.isEmpty()) { "usage: vortex help" }
            return Result(
                "Vortex3D native bridge\nvortex status\nvortex catalog\nvortex api\nvortex snapshot\nvortex ui-tree [limit]\n" +
                    "vortex screenshot [name]\nvortex click <target>\nvortex touch <down|move|up|cancel|0..3> <x> <y>\n" +
                    "vortex test [all|system|case-id]\nvortex test-wait <system|case-id>\n" +
                    "vortex script <RiftFS-path> [--unsafe] [--live]\nvortex script-wait <RiftFS-path> [--unsafe] [--live]\n" +
                    "vortex job <id> [--image]\nvortex pull <artifact-id> [filename]\nvortex cleanup"
            )
        }
        val bridge = RiftMcpRuntime.vortexBridge(appContext)
        val request = JSONObject()
        val session: Boolean
        when (sub) {
            "status", "catalog", "api", "snapshot", "cleanup" -> { require(args.isEmpty()) { "usage: vortex $sub" }; request.put("op", sub); session = false }
            "ui-tree", "ui_tree" -> {
                require(args.size <= 1) { "usage: vortex ui-tree [limit]" }
                val limit = if (args.isEmpty()) 256 else args[0].toIntOrNull() ?: throw IllegalArgumentException("ui-tree limit must be an integer")
                require(limit in 1..1024) { "ui-tree limit must be between 1 and 1024" }
                request.put("op", "ui_tree").put("limit", limit); session = false
            }
            "screenshot" -> { require(args.size <= 1) { "usage: vortex screenshot [name]" }; val name=args.firstOrNull()?:"current"; require(name.length<=120){"vortex screenshot name is too long"}; request.put("op","screenshot").put("name",name).put("includeImage",true); session=false }
            "click" -> { require(args.isNotEmpty()){"usage: vortex click <target>"}; val target=args.joinToString(" "); require(target.length<=256){"vortex click target is too long"}; request.put("op","click").put("target",target); session=false }
            "touch" -> {
                require(args.size==3){"usage: vortex touch <action> <x> <y>"}
                val actions=mapOf("down" to 0,"up" to 1,"move" to 2,"cancel" to 3)
                val raw=args[0].lowercase(); val action=actions[raw]?:raw.toIntOrNull()
                require(action!=null&&action in 0..3){"touch action must be down/move/up/cancel or 0..3"}
                val x=args[1].toDoubleOrNull(); val y=args[2].toDoubleOrNull()
                require(x!=null&&y!=null){"touch coordinates must be numeric"}
                require(x.isFinite()&&y.isFinite()){"touch coordinates must be finite"}
                request.put("op","touch").put("action",action).put("x",x).put("y",y); session=false
            }
            "test", "validate" -> { require(args.size<=1){"usage: vortex test [all|system|case-id]"}; val target=args.firstOrNull()?:"all"; require(target.length<=160){"vortex validation target is too long"}; request.put("op","validate").put("target",target); session=false }
            "test-wait", "validate-wait" -> {
                require(args.size==1){"usage: vortex test-wait <target>"}
                require(args[0].length<=160){"vortex validation target is too long"}
                request.put("kind","validation").put("target",args[0]).put("includeImage",true); session=true
            }
            "script", "script-wait" -> {
                val unsafe=args.remove("--unsafe"); val live=args.remove("--live")
                require(args.size==1){"usage: vortex $sub <RiftFS-path> [--unsafe] [--live]"}
                val path=resolveDisplay(cwd,args[0])
                val source=readText(path)
                if(sub=="script"){
                    request.put("op","script").put("source",source).put("unsafe",unsafe).put("live",live).put("name",File(path).nameWithoutExtension); session=false
                }else{
                    request.put("kind","script").put("source",source).put("unsafe",unsafe).put("live",live).put("name",File(path).nameWithoutExtension).put("includeImage",true); session=true
                }
            }
            "job" -> {
                require(args.size in 1..2 && (args.size==1 || args[1]=="--image")){"usage: vortex job <id> [--image]"}
                require(args[0].length<=160){"vortex job id is too long"}
                request.put("op","job").put("id",args[0]).put("includeImage",args.size==2); session=false
            }
            "pull" -> {
                require(args.size in 1..2){"usage: vortex pull <artifact-id> [filename]"}
                require(args[0].length<=512){"vortex artifact id is too long"}
                require(args.getOrNull(1)?.length?.let { it<=120 } ?: true){"vortex artifact filename is too long"}
                request.put("op","pull_artifact").put("id",args[0]).put("name",args.getOrNull(1)?:""); session=false
            }
            else -> throw IllegalArgumentException("unknown vortex command: $sub")
        }
        val value=if(session) bridge.executeSession(request) else bridge.execute(request)
        val display=JSONObject(value.toString()).also { if(it.has("_riftImage")) it.put("_riftImage",JSONObject().put("attached",true)) }
        return Result(display.toString(2),value)
    }

    fun codynex(args: MutableList<String>, cwd: String): Result {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "help"

        if (sub == "help") {
            require(args.isEmpty()) { "usage: codynex help" }
            return Result(
                "Codynex LR0 local Binder bridge\n" +
                    "codynex status\n" +
                    "codynex read-state <id>\n" +
                    "codynex call <function-id>\n" +
                    "codynex compile-activate <RiftFS-source-path>\n" +
                    "codynex activate\n" +
                    "codynex corrupt\n" +
                    "codynex recover\n" +
                    "codynex clear\n" +
                    "codynex cold-restart"
            )
        }

        val request = JSONObject()

        when (sub) {
            "status" -> {
                require(args.isEmpty()) { "usage: codynex status" }
                request.put("op", "status")
            }

            "read-state", "read_state" -> {
                require(args.size == 1) {
                    "usage: codynex read-state <id>"
                }

                val id = args[0].toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "state id must be an integer"
                    )

                require(id in 0..65535) {
                    "state id must be between 0 and 65535"
                }

                request
                    .put("op", "read_state")
                    .put("stateId", id)
            }

            "call" -> {
                require(args.size == 1) {
                    "usage: codynex call <function-id>"
                }

                val id = args[0].toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "function id must be an integer"
                    )

                require(id in 0..65535) {
                    "function id must be between 0 and 65535"
                }

                request
                    .put("op", "call")
                    .put("functionId", id)
            }

            "compile-activate", "compile_activate" -> {
                require(args.size == 1) {
                    "usage: codynex compile-activate <RiftFS-source-path>"
                }

                val display = resolveDisplay(cwd, args[0])
                val file = resolveFile(display)

                require(file.isFile) {
                    "Codynex source file not found: $display"
                }

                require(file.length() <= 64L * 1024L) {
                    "Codynex LR0 source exceeds 64 KiB"
                }

                val source = file.readText(Charsets.UTF_8)

                require(
                    source.toByteArray(Charsets.UTF_8).size <=
                        64 * 1024
                ) {
                    "Codynex LR0 source exceeds 64 KiB UTF-8"
                }

                request
                    .put("op", "compile_activate")
                    .put("source", source)
            }

            "activate" -> {
                require(args.isEmpty()) { "usage: codynex activate" }
                request.put("op", "activate_candidate")
            }

            "corrupt" -> {
                require(args.isEmpty()) { "usage: codynex corrupt" }
                request.put("op", "corrupt_candidate")
            }

            "recover" -> {
                require(args.isEmpty()) { "usage: codynex recover" }
                request.put("op", "recover")
            }

            "clear" -> {
                require(args.isEmpty()) { "usage: codynex clear" }
                request.put("op", "clear")
            }

            "cold-restart", "cold_restart" -> {
                require(args.isEmpty()) {
                    "usage: codynex cold-restart"
                }
                request.put("op", "cold_restart")
            }

            else ->
                throw IllegalArgumentException(
                    "unknown codynex command: $sub"
                )
        }

        val value =
            RiftMcpRuntime
                .codynexBridge(appContext)
                .execute(request)

        return Result(
            value.toString(2),
            value
        )
    }

    fun vortexAgent(args: MutableList<String>): Result =
        localAgent("vortex-agent",args){ request -> RiftVortexLocalAgent.execute(appContext,request) }

    fun riftOsAgent(args: MutableList<String>, cwd: String): Result {
        if(args.firstOrNull()?.lowercase()=="devlab"){
            args.removeAt(0)
            return RiftLocalAgentExecutionGate.withAccess {
                devLab(args,cwd)
            }
        }
        val activity=RiftMcpRuntime.activeActivity()
        val context=activity?:appContext
        return localAgent("riftos-agent",args){ request -> RiftOsLocalAgent.execute(context,request) }
    }

    fun riftLlm(args: MutableList<String>, cwd: String): Result {
        val sub=args.removeFirstOrNull()?.lowercase()?:"help"
        if(sub=="help") return Result(
            "RiftLLM native Dev API bridge\nriftllm-agent status\nriftllm-agent unpair\n" +
                "riftllm-agent train-data-status\nriftllm-agent train-data-build\nriftllm-agent train-data-build-status\nriftllm-agent train-data-build-cancel\n" +
                "riftllm-agent train-data-upload\nriftllm-agent train-data-remote-status\nriftllm-agent train-canary-start\nriftllm-agent train-canary-status\n" +
                "Pairing is entered only in native Settings. Legacy corpus-* helpers are unavailable unless explicitly reintroduced behind a bounded native/headless service."
        )
        if(sub=="pair") throw IllegalStateException("RiftLLM pairing token must be entered in native Settings; shell arguments are intentionally rejected.")
        val request=JSONObject()
        val value:Any=when(sub){
            "status","unpair" -> llm.execute(JSONObject().put("op",sub))
            "train-data-status" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","status"))
            "train-data-build" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","build"))
            "train-data-build-status" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","build-status"))
            "train-data-build-cancel" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","build-cancel"))
            "train-data-upload" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","upload"))
            "train-data-remote-status" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","remote-status"))
            "train-canary-start" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","canary-start"))
            "train-canary-status" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","canary-status"))
            else -> {
                if (sub == "preview" || sub == "publish") throw IllegalStateException(
                    "RiftLLM '$sub' requires the retired workspace patch preview/apply composite and is intentionally unavailable until a bounded native publisher is implemented."
                )
                val mapping=mapOf(
                    "staged" to "list_staged","reset" to "reset","snapshots" to "list_snapshots","benchmarks" to "list_benchmarks",
                    "text-encoding-status" to "text_encoding_status"
                )
                val op=mapping[sub]?:throw IllegalStateException(
                    "Legacy RiftLLM shell helper '$sub' is unavailable in the native shell; migrate it to a bounded native/headless service before re-enabling it."
                )
                request.put("op",op)
                val first=args.firstOrNull()
                if(first!=null) request.put("request",JSONObject().put("id",first))
                llm.execute(request)
            }
        }
        val text=when(value){is JSONObject->value.toString(2);is JSONArray->value.toString(2);else->value.toString()}
        return Result(text,value as? JSONObject)
    }

    private fun localAgent(name:String,args:MutableList<String>,call:(JSONObject)->JSONObject):Result{
        val sub=args.removeFirstOrNull()?.lowercase()?:"help"
        if(sub=="help") {
            require(args.isEmpty()) { "usage: $name help" }
            return Result(
                "$name Android-native local agent\n$name status\n$name open\n$name tree [limit]\n$name click <target>\n" +
                    "$name tap <x> <y>\n$name swipe <x1> <y1> <x2> <y2> [ms]\n$name type <target> <text>\n$name back"
            )
        }
        val request=JSONObject().put("op",sub)
        when(sub){
            "status","open","back"->{require(args.isEmpty()){"usage: $name $sub"}}
            "tree"->{
                require(args.size<=1){"usage: $name tree [limit]"}
                val limit=if(args.isEmpty())256 else args[0].toIntOrNull()?:throw IllegalArgumentException("tree limit must be an integer")
                require(limit in 1..1024){"tree limit must be between 1 and 1024"}
                request.put("limit",limit)
            }
            "click"->{require(args.isNotEmpty()){"usage: $name click <target>"};request.put("target",args.joinToString(" "))}
            "tap"->{
                require(args.size==2){"usage: $name tap <x> <y>"}
                val x=args[0].toDoubleOrNull()?:throw IllegalArgumentException("tap x must be numeric")
                val y=args[1].toDoubleOrNull()?:throw IllegalArgumentException("tap y must be numeric")
                request.put("x",x).put("y",y)
            }
            "swipe"->{
                require(args.size in 4..5){"usage: $name swipe <x1> <y1> <x2> <y2> [ms]"}
                val x1=args[0].toDoubleOrNull()?:throw IllegalArgumentException("swipe x1 must be numeric")
                val y1=args[1].toDoubleOrNull()?:throw IllegalArgumentException("swipe y1 must be numeric")
                val x2=args[2].toDoubleOrNull()?:throw IllegalArgumentException("swipe x2 must be numeric")
                val y2=args[3].toDoubleOrNull()?:throw IllegalArgumentException("swipe y2 must be numeric")
                val duration=if(args.size==5) args[4].toLongOrNull()?:throw IllegalArgumentException("swipe duration must be an integer") else 350L
                request.put("x1",x1).put("y1",y1).put("x2",x2).put("y2",y2).put("durationMs",duration)
            }
            "type"->{require(args.size>=2){"usage: $name type <target> <text>"};request.put("target",args.removeAt(0)).put("text",args.joinToString(" "))}
            "type-focused"->{require(name=="riftos-agent"){"unknown $name command: $sub"};require(args.isNotEmpty()){"usage: $name type-focused <text>"};request.put("text",args.joinToString(" "))}
            else->throw IllegalArgumentException("unknown $name command: $sub")
        }
        val value=call(request)
        return Result(value.toString(2),value)
    }

    private fun readText(display:String):String{
        val file=resolveFile(display)
        require(file.isFile){"file not found: $display"}
        require(file.length()<=240L*1024L){"Vortex script source exceeds 240 KiB"}
        val text=file.readText(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).size<=240*1024){"Vortex script source exceeds 240 KiB UTF-8"}
        return text
    }

    private fun resolveDisplay(cwd:String,raw:String):String{
        var value=raw.trim().replace('\\','/')
        if(value=="~"||value.startsWith("~/")) value="/D:/Users/Default"+value.drop(1)
        if(Regex("^[A-Za-z]:($|/)").containsMatchIn(value)) value="/$value"
        if(!value.startsWith('/')) value=cwd.trimEnd('/')+"/"+value
        return RiftVolumePaths.normalizeDisplay(value)
    }

    private fun resolveFile(display:String):File{
        val normalized=RiftVolumePaths.normalizeDisplay(display)
        val relative=if(normalized.startsWith("/C:",true)||normalized.startsWith("/D:",true)) RiftVolumePaths.resolveRelative(normalized) else normalized.trimStart('/')
        val file=if(relative.isBlank())riftRoot else File(riftRoot,relative).canonicalFile
        require(file==riftRoot||file.path.startsWith(riftRoot.path+File.separator)){"Path escaped RiftFS"}
        return file
    }
}
