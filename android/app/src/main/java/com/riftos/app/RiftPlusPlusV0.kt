package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Rift++ V0: bounded declarative language for describing RiftCLI brains, agents, swarms and tasks.
 *
 * V0 compiles source into Rift Swarm IR only. It cannot execute arbitrary code, spawn processes,
 * call shell commands, mutate workspace files or invoke a model backend.
 */
object RiftPlusPlusV0 {
    private const val LANGUAGE_VERSION = 0
    private const val IR_SCHEMA = "rift.swarm-ir/0"
    private const val MAX_SOURCE_BYTES = 128 * 1024L
    private const val MAX_BLOCKS = 64
    private const val MAX_LIST_ITEMS = 64
    private const val MAX_CONTEXT_TOKENS = 65_536
    private const val MAX_RETRIES = 5
    private const val MAX_REVIEWERS = 8

    private val blockKinds = setOf("backend", "brain", "agent", "swarm", "task")
    private val backendTypes = setOf("rules", "riftllm", "remote", "mock")
    private val brainMemories = setOf("none", "session", "project")
    private val roles = setOf(
        "lead", "architect", "research", "cpp", "android", "js", "debug", "build",
        "tests", "security", "performance", "docs", "review"
    )
    private val requirements = setOf("tests", "security", "review", "performance", "docs", "build")
    private val capabilities = setOf(
        "workspace.read", "workspace.graph", "workspace.diff", "workspace.write",
        "git.status", "git.diff", "git.commit", "git.push",
        "build.plan", "build.run", "tests.run",
        "docs.read", "docs.write", "browser.inspect",
        "device.view", "device.control", "research.web",
        "mcp.read", "mcp.write"
    )
    private val mutatingCapabilities = setOf(
        "workspace.write", "git.commit", "git.push", "build.run", "docs.write",
        "device.control", "mcp.write"
    )
    private val readOnlyRoles = setOf("review", "security")

    private enum class TokenKind { IDENT, STRING, NUMBER, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA, ARROW, EOF }
    private data class Token(val kind: TokenKind, val text: String, val line: Int)
    private data class Edge(val from: String, val to: String)
    private data class FieldValue(
        val scalar: String? = null,
        val number: Int? = null,
        val items: List<String>? = null,
        val edge: Edge? = null
    )
    private data class Block(
        val kind: String,
        val name: String,
        val fields: LinkedHashMap<String, MutableList<FieldValue>>,
        val line: Int
    )

    fun execute(context: Context, args: List<String>): JSONObject {
        val action = args.firstOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "help" }
        return when (action) {
            "help" -> JSONObject()
                .put("schema", "rift.riftpp-v0-command/1")
                .put("language", "Rift++")
                .put("version", LANGUAGE_VERSION)
                .put("executable", false)
                .put("commands", JSONArray(listOf(
                    "rift-cli riftpp sample",
                    "rift-cli riftpp validate <workspace-script.riftpp>",
                    "rift-cli riftpp compile <workspace-script.riftpp>",
                    "rift-cli riftpp preview <workspace-script.riftpp> [task-name]"
                )))
            "sample" -> JSONObject()
                .put("schema", "rift.riftpp-v0-sample/1")
                .put("language", "Rift++")
                .put("version", LANGUAGE_VERSION)
                .put("source", sampleSource())
            "validate", "compile", "preview" -> {
                require(args.size >= 2) { "usage: rift-cli riftpp $action <workspace-script.riftpp>${if (action == "preview") " [task-name]" else ""}" }
                require(args.size <= if (action == "preview") 3 else 2) { "too many Rift++ arguments" }
                val ir = compileWorkspace(context, args[1])
                when (action) {
                    "validate" -> validationSummary(ir)
                    "preview" -> RiftSwarmCoordinatorV0.preview(ir, args.getOrNull(2))
                    else -> ir
                }
            }
            else -> throw IllegalArgumentException("unknown Rift++ V0 command: $action")
        }
    }

    fun compileWorkspace(context: Context, rawPath: String): JSONObject {
        val loaded = loadWorkspaceSource(context, rawPath)
        return compile(loaded.second, loaded.first)
    }

    fun compile(source: String, sourceName: String = "inline.riftpp"): JSONObject {
        val bytes = source.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_SOURCE_BYTES) { "Rift++ source exceeds $MAX_SOURCE_BYTES bytes" }
        val blocks = Parser(Lexer(source).scan()).parse()
        require(blocks.isNotEmpty()) { "Rift++ file contains no declarations" }
        require(blocks.size <= MAX_BLOCKS) { "Rift++ file exceeds $MAX_BLOCKS declarations" }
        return compileBlocks(blocks, sourceName, sha256(bytes))
    }

    private fun compileBlocks(blocks: List<Block>, sourceName: String, sourceSha: String): JSONObject {
        val names = LinkedHashSet<String>()
        blocks.forEach { block ->
            require(names.add(block.name)) { "line ${block.line}: duplicate declaration name '${block.name}'" }
            require(block.kind in blockKinds) { "line ${block.line}: unsupported declaration '${block.kind}'" }
        }

        val backends = blocks.filter { it.kind == "backend" }.associateBy { it.name }
        val brains = blocks.filter { it.kind == "brain" }.associateBy { it.name }
        val agents = blocks.filter { it.kind == "agent" }.associateBy { it.name }
        val swarms = blocks.filter { it.kind == "swarm" }.associateBy { it.name }
        val tasks = blocks.filter { it.kind == "task" }.associateBy { it.name }

        require(backends.isNotEmpty()) { "Rift++ V0 requires at least one backend" }
        require(brains.isNotEmpty()) { "Rift++ V0 requires at least one brain" }
        require(agents.isNotEmpty()) { "Rift++ V0 requires at least one agent" }
        require(swarms.isNotEmpty()) { "Rift++ V0 requires at least one swarm" }
        require(tasks.isNotEmpty()) { "Rift++ V0 requires at least one task" }

        val backendJson = JSONArray()
        backends.values.forEach { block ->
            allowedFields(block, setOf("type", "model"))
            val type = requiredScalar(block, "type").lowercase()
            require(type in backendTypes) { "${block.name}: unsupported backend type '$type'" }
            val model = optionalScalar(block, "model")
            backendJson.put(JSONObject()
                .put("name", block.name)
                .put("type", type)
                .put("model", model ?: JSONObject.NULL)
                .put("connected", false))
        }

        val brainJson = JSONArray()
        val brainRoles = LinkedHashMap<String, String>()
        brains.values.forEach { block ->
            allowedFields(block, setOf("backend", "role", "memory", "context"))
            val backend = requiredScalar(block, "backend")
            require(backends.containsKey(backend)) { "${block.name}: unknown backend '$backend'" }
            val role = optionalScalar(block, "role")?.lowercase() ?: "lead"
            require(role in roles) { "${block.name}: unknown brain role '$role'" }
            val memory = optionalScalar(block, "memory")?.lowercase() ?: "session"
            require(memory in brainMemories) { "${block.name}: memory must be one of ${brainMemories.joinToString()}" }
            val context = optionalInt(block, "context") ?: 8_192
            require(context in 256..MAX_CONTEXT_TOKENS) { "${block.name}: context must be 256..$MAX_CONTEXT_TOKENS" }
            brainRoles[block.name] = role
            brainJson.put(JSONObject()
                .put("name", block.name)
                .put("backend", backend)
                .put("role", role)
                .put("memory", memory)
                .put("contextTokens", context))
        }

        val agentJson = JSONArray()
        val agentRoles = LinkedHashMap<String, String>()
        agents.values.forEach { block ->
            allowedFields(block, setOf("role", "tools", "allow", "deny", "context"))
            val role = requiredScalar(block, "role").lowercase()
            require(role in roles && role != "lead") { "${block.name}: invalid specialist role '$role'" }
            val tools = optionalList(block, "tools")
            val allow = optionalList(block, "allow")
            val deny = optionalList(block, "deny")
            listOf(tools, allow, deny).flatten().forEach { capability ->
                require(capability in capabilities) { "${block.name}: unknown capability '$capability'" }
            }
            require(allow.intersect(deny.toSet()).isEmpty()) { "${block.name}: capability cannot be both allowed and denied" }
            require(tools.all { it in allow }) { "${block.name}: every tool capability must also appear in allow" }
            require(tools.none { it in deny }) { "${block.name}: denied capability cannot be used as a tool" }
            if (role in readOnlyRoles) {
                val unsafe = allow.filter { it in mutatingCapabilities }
                require(unsafe.isEmpty()) { "${block.name}: $role is read-only and cannot allow ${unsafe.joinToString()}" }
            }
            val context = optionalInt(block, "context") ?: 6_144
            require(context in 256..MAX_CONTEXT_TOKENS) { "${block.name}: context must be 256..$MAX_CONTEXT_TOKENS" }
            agentRoles[block.name] = role
            agentJson.put(JSONObject()
                .put("name", block.name)
                .put("role", role)
                .put("tools", JSONArray(tools))
                .put("allow", JSONArray(allow))
                .put("deny", JSONArray(deny))
                .put("contextTokens", context))
        }

        val swarmJson = JSONArray()
        val swarmWorkers = LinkedHashMap<String, List<String>>()
        swarms.values.forEach { block ->
            allowedFields(block, setOf("lead", "workers", "flow", "review"), repeatable = setOf("flow"))
            val lead = requiredScalar(block, "lead")
            require(brains.containsKey(lead)) { "${block.name}: unknown lead brain '$lead'" }
            require(brainRoles[lead] == "lead") { "${block.name}: lead brain '$lead' must declare role lead" }
            val workers = requiredList(block, "workers")
            require(workers.isNotEmpty()) { "${block.name}: swarm requires at least one worker" }
            require(workers.size <= 32) { "${block.name}: swarm exceeds 32 workers" }
            require(workers.toSet().size == workers.size) { "${block.name}: workers must be unique" }
            workers.forEach { require(agents.containsKey(it)) { "${block.name}: unknown worker '$it'" } }
            val edges = edgeValues(block, "flow")
            val members = (workers + lead).toSet()
            edges.forEach { edge ->
                require(edge.from in members) { "${block.name}: flow source '${edge.from}' is not a swarm member" }
                require(edge.to in members) { "${block.name}: flow target '${edge.to}' is not a swarm member" }
                require(edge.from != edge.to) { "${block.name}: self-flow is not allowed for '${edge.from}'" }
            }
            val review = optionalInt(block, "review") ?: 1
            require(review in 0..MAX_REVIEWERS) { "${block.name}: review must be 0..$MAX_REVIEWERS" }
            val reviewWorkers = workers.count { agentRoles[it] == "review" }
            require(review <= reviewWorkers) { "${block.name}: review=$review requires at least $review review-role worker(s), found $reviewWorkers" }
            val order = topologicalOrder(block.name, members, edges)
            swarmWorkers[block.name] = workers
            swarmJson.put(JSONObject()
                .put("name", block.name)
                .put("lead", lead)
                .put("workers", JSONArray(workers))
                .put("flows", JSONArray(edges.map { JSONObject().put("from", it.from).put("to", it.to) }))
                .put("reviewersRequired", review)
                .put("scheduleOrder", JSONArray(order)))
        }

        val taskJson = JSONArray()
        tasks.values.forEach { block ->
            allowedFields(block, setOf("swarm", "goal", "retry", "require"))
            val swarm = requiredScalar(block, "swarm")
            require(swarms.containsKey(swarm)) { "${block.name}: unknown swarm '$swarm'" }
            val goal = requiredScalar(block, "goal")
            require(goal.isNotBlank() && goal.length <= 4096) { "${block.name}: goal must be 1..4096 characters" }
            val retry = optionalInt(block, "retry") ?: 0
            require(retry in 0..MAX_RETRIES) { "${block.name}: retry must be 0..$MAX_RETRIES" }
            val required = optionalList(block, "require")
            required.forEach { require(it in requirements) { "${block.name}: unknown requirement '$it'" } }
            require(required.toSet().size == required.size) { "${block.name}: duplicate task requirement" }
            val availableRoles = swarmWorkers.getValue(swarm).mapNotNull { agentRoles[it] }.toSet()
            required.filter { it in roles }.forEach { needed ->
                require(needed in availableRoles) { "${block.name}: requirement '$needed' has no matching worker role in swarm '$swarm'" }
            }
            taskJson.put(JSONObject()
                .put("name", block.name)
                .put("swarm", swarm)
                .put("goal", goal)
                .put("retry", retry)
                .put("require", JSONArray(required)))
        }

        return JSONObject()
            .put("schema", IR_SCHEMA)
            .put("language", "Rift++")
            .put("languageVersion", LANGUAGE_VERSION)
            .put("experimental", true)
            .put("executable", false)
            .put("source", sourceName)
            .put("sourceSha256", sourceSha)
            .put("limits", JSONObject()
                .put("maxSourceBytes", MAX_SOURCE_BYTES)
                .put("maxBlocks", MAX_BLOCKS)
                .put("maxContextTokens", MAX_CONTEXT_TOKENS))
            .put("backends", backendJson)
            .put("brains", brainJson)
            .put("agents", agentJson)
            .put("swarms", swarmJson)
            .put("tasks", taskJson)
    }

    private fun validationSummary(ir: JSONObject): JSONObject = JSONObject()
        .put("schema", "rift.riftpp-v0-validation/1")
        .put("ok", true)
        .put("language", ir.getString("language"))
        .put("languageVersion", ir.getInt("languageVersion"))
        .put("source", ir.getString("source"))
        .put("sourceSha256", ir.getString("sourceSha256"))
        .put("executable", false)
        .put("counts", JSONObject()
            .put("backends", ir.getJSONArray("backends").length())
            .put("brains", ir.getJSONArray("brains").length())
            .put("agents", ir.getJSONArray("agents").length())
            .put("swarms", ir.getJSONArray("swarms").length())
            .put("tasks", ir.getJSONArray("tasks").length()))

    private fun loadWorkspaceSource(context: Context, rawPath: String): Pair<String, String> {
        val normalized = rawPath.trim().replace('\\', '/')
        require(normalized.isNotBlank()) { "Rift++ script path is required" }
        require(!normalized.contains('\u0000')) { "invalid Rift++ path" }
        require(!normalized.split('/').contains("..")) { "Rift++ path traversal is not allowed" }
        val relative = when {
            normalized.startsWith("/D:/Workspace/", true) -> normalized.substring("/D:/Workspace/".length)
            normalized.startsWith("D:/Workspace/", true) -> normalized.substring("D:/Workspace/".length)
            normalized.startsWith("/workspace/", true) -> normalized.substring("/workspace/".length)
            normalized.startsWith("workspace/", true) -> normalized.substring("workspace/".length)
            !normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized) -> normalized
            else -> throw IllegalArgumentException("Rift++ V0 reads scripts only from RiftFS workspace/")
        }.trimStart('/')
        require(relative.isNotBlank()) { "Rift++ script path must name a file inside workspace/" }
        require(relative.endsWith(".riftpp", true) || relative.endsWith(".rift++", true)) {
            "Rift++ V0 script must end in .riftpp or .rift++"
        }
        val root = File(context.filesDir, "riftfs/workspace").apply { mkdirs() }.canonicalFile
        val file = File(root, relative).canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator)) { "Rift++ path escaped workspace/" }
        require(file.isFile) { "Rift++ script not found: workspace/$relative" }
        require(file.length() <= MAX_SOURCE_BYTES) { "Rift++ source exceeds $MAX_SOURCE_BYTES bytes" }
        val bytes = file.readBytes()
        require(bytes.size.toLong() <= MAX_SOURCE_BYTES) { "Rift++ source exceeds $MAX_SOURCE_BYTES bytes" }
        return "workspace/${file.relativeTo(root).invariantSeparatorsPath}" to String(bytes, Charsets.UTF_8)
    }

    private fun allowedFields(block: Block, allowed: Set<String>, repeatable: Set<String> = emptySet()) {
        block.fields.forEach { (key, values) ->
            require(key in allowed) { "line ${block.line}: ${block.kind} ${block.name} does not support '$key'" }
            if (key !in repeatable) require(values.size == 1) { "${block.name}: '$key' may appear only once" }
        }
    }

    private fun requiredScalar(block: Block, key: String): String =
        optionalScalar(block, key) ?: throw IllegalArgumentException("${block.name}: missing '$key'")

    private fun optionalScalar(block: Block, key: String): String? {
        val values = block.fields[key] ?: return null
        require(values.size == 1) { "${block.name}: '$key' may appear only once" }
        val value = values.single()
        require(value.scalar != null && value.number == null && value.items == null && value.edge == null) {
            "${block.name}: '$key' must be a scalar value"
        }
        return value.scalar
    }

    private fun optionalInt(block: Block, key: String): Int? {
        val values = block.fields[key] ?: return null
        require(values.size == 1 && values.single().number != null) { "${block.name}: '$key' must be an integer" }
        return values.single().number
    }

    private fun requiredList(block: Block, key: String): List<String> =
        block.fields[key]?.singleOrNull()?.items ?: throw IllegalArgumentException("${block.name}: missing list '$key'")

    private fun optionalList(block: Block, key: String): List<String> {
        val values = block.fields[key] ?: return emptyList()
        require(values.size == 1 && values.single().items != null) { "${block.name}: '$key' must be a list" }
        val items = values.single().items!!
        require(items.size <= MAX_LIST_ITEMS) { "${block.name}: '$key' exceeds $MAX_LIST_ITEMS items" }
        require(items.toSet().size == items.size) { "${block.name}: '$key' contains duplicates" }
        return items
    }

    private fun edgeValues(block: Block, key: String): List<Edge> =
        block.fields[key].orEmpty().map { value ->
            value.edge ?: throw IllegalArgumentException("${block.name}: '$key' must use A -> B syntax")
        }

    private fun topologicalOrder(name: String, members: Set<String>, edges: List<Edge>): List<String> {
        val outgoing = members.associateWith { mutableListOf<String>() }.toMutableMap()
        val indegree = members.associateWith { 0 }.toMutableMap()
        edges.forEach { edge ->
            if (!outgoing.getValue(edge.from).contains(edge.to)) {
                outgoing.getValue(edge.from).add(edge.to)
                indegree[edge.to] = indegree.getValue(edge.to) + 1
            }
        }
        val ready = members.filter { indegree.getValue(it) == 0 }.sorted().toMutableList()
        val order = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val node = ready.removeAt(0)
            order += node
            outgoing.getValue(node).sorted().forEach { next ->
                indegree[next] = indegree.getValue(next) - 1
                if (indegree.getValue(next) == 0) {
                    ready += next
                    ready.sort()
                }
            }
        }
        require(order.size == members.size) { "$name: swarm flow contains a cycle" }
        return order
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class Lexer(private val source: String) {
        private val tokens = mutableListOf<Token>()
        private var index = 0
        private var line = 1

        fun scan(): List<Token> {
            while (index < source.length) {
                val ch = source[index]
                when {
                    ch == '\n' -> { line++; index++ }
                    ch.isWhitespace() -> index++
                    ch == '#' -> skipLine()
                    ch == '/' && peek(1) == '/' -> skipLine()
                    ch == '{' -> emit(TokenKind.LBRACE, "{")
                    ch == '}' -> emit(TokenKind.RBRACE, "}")
                    ch == '[' -> emit(TokenKind.LBRACKET, "[")
                    ch == ']' -> emit(TokenKind.RBRACKET, "]")
                    ch == ',' -> emit(TokenKind.COMMA, ",")
                    ch == '-' && peek(1) == '>' -> { tokens += Token(TokenKind.ARROW, "->", line); index += 2 }
                    ch == '"' -> string()
                    ch.isDigit() -> number()
                    ch.isLetter() || ch == '_' -> identifier()
                    else -> throw IllegalArgumentException("line $line: unsupported Rift++ character '$ch'")
                }
            }
            tokens += Token(TokenKind.EOF, "", line)
            return tokens
        }

        private fun emit(kind: TokenKind, text: String) {
            tokens += Token(kind, text, line)
            index++
        }

        private fun peek(offset: Int): Char? = source.getOrNull(index + offset)

        private fun skipLine() {
            while (index < source.length && source[index] != '\n') index++
        }

        private fun identifier() {
            val start = index
            while (index < source.length) {
                val ch = source[index]
                if (!(ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' || ch == ':')) break
                index++
            }
            tokens += Token(TokenKind.IDENT, source.substring(start, index), line)
        }

        private fun number() {
            val start = index
            while (index < source.length && source[index].isDigit()) index++
            tokens += Token(TokenKind.NUMBER, source.substring(start, index), line)
        }

        private fun string() {
            val startLine = line
            index++
            val out = StringBuilder()
            var closed = false
            while (index < source.length) {
                val ch = source[index++]
                if (ch == '"') { closed = true; break }
                if (ch == '\n') line++
                if (ch == '\\') {
                    val escaped = source.getOrNull(index++) ?: throw IllegalArgumentException("line $startLine: unterminated string escape")
                    out.append(when (escaped) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '"' -> '"'
                        '\\' -> '\\'
                        else -> throw IllegalArgumentException("line $line: unsupported string escape \\$escaped")
                    })
                } else out.append(ch)
                require(out.length <= 4096) { "line $startLine: Rift++ string exceeds 4096 characters" }
            }
            require(closed) { "line $startLine: unterminated string" }
            tokens += Token(TokenKind.STRING, out.toString(), startLine)
        }
    }

    private class Parser(private val tokens: List<Token>) {
        private var index = 0

        fun parse(): List<Block> {
            val header = expect(TokenKind.IDENT, "expected 'riftpp 0' header")
            require(header.text.equals("riftpp", true)) { "line ${header.line}: Rift++ file must begin with 'riftpp 0'" }
            val version = expect(TokenKind.NUMBER, "expected Rift++ language version")
            require(version.text.toIntOrNull() == LANGUAGE_VERSION) { "line ${version.line}: only Rift++ V0 is supported" }
            val blocks = mutableListOf<Block>()
            while (!check(TokenKind.EOF)) {
                require(blocks.size < MAX_BLOCKS) { "Rift++ file exceeds $MAX_BLOCKS declarations" }
                blocks += declaration()
            }
            return blocks
        }

        private fun declaration(): Block {
            val kind = expect(TokenKind.IDENT, "expected declaration type")
            require(kind.text.lowercase() in blockKinds) { "line ${kind.line}: unknown declaration '${kind.text}'" }
            val name = expect(TokenKind.IDENT, "expected declaration name")
            expect(TokenKind.LBRACE, "expected '{' after ${name.text}")
            val fields = LinkedHashMap<String, MutableList<FieldValue>>()
            while (!check(TokenKind.RBRACE)) {
                require(!check(TokenKind.EOF)) { "line ${kind.line}: unterminated ${kind.text} ${name.text}" }
                val key = expect(TokenKind.IDENT, "expected field name")
                val fieldName = key.text.lowercase()
                val value = if (fieldName == "flow") {
                    val from = expect(TokenKind.IDENT, "expected flow source")
                    expect(TokenKind.ARROW, "expected '->' in flow")
                    val to = expect(TokenKind.IDENT, "expected flow target")
                    FieldValue(edge = Edge(from.text, to.text))
                } else parseValue()
                fields.getOrPut(fieldName) { mutableListOf() }.add(value)
            }
            expect(TokenKind.RBRACE, "expected '}'")
            return Block(kind.text.lowercase(), name.text, fields, kind.line)
        }

        private fun parseValue(): FieldValue {
            val token = current()
            return when (token.kind) {
                TokenKind.STRING -> { index++; FieldValue(scalar = token.text) }
                TokenKind.IDENT -> { index++; FieldValue(scalar = token.text) }
                TokenKind.NUMBER -> {
                    index++
                    val number = token.text.toIntOrNull() ?: throw IllegalArgumentException("line ${token.line}: integer is out of range")
                    FieldValue(number = number)
                }
                TokenKind.LBRACKET -> listValue()
                else -> throw IllegalArgumentException("line ${token.line}: expected scalar, integer or list")
            }
        }

        private fun listValue(): FieldValue {
            expect(TokenKind.LBRACKET, "expected '['")
            val items = mutableListOf<String>()
            while (!check(TokenKind.RBRACKET)) {
                require(items.size < MAX_LIST_ITEMS) { "line ${current().line}: list exceeds $MAX_LIST_ITEMS items" }
                val token = current()
                require(token.kind == TokenKind.IDENT || token.kind == TokenKind.STRING) {
                    "line ${token.line}: lists contain only identifiers or strings"
                }
                items += token.text
                index++
                if (check(TokenKind.COMMA)) index++
                else if (!check(TokenKind.RBRACKET)) throw IllegalArgumentException("line ${current().line}: expected ',' or ']' in list")
            }
            expect(TokenKind.RBRACKET, "expected ']'")
            return FieldValue(items = items)
        }

        private fun current(): Token = tokens[index]
        private fun check(kind: TokenKind): Boolean = current().kind == kind
        private fun expect(kind: TokenKind, message: String): Token {
            val token = current()
            if (token.kind != kind) throw IllegalArgumentException("line ${token.line}: $message")
            index++
            return token
        }
    }

    private fun sampleSource(): String = """
        riftpp 0

        backend RulesBrain {
          type rules
        }

        brain MainBrain {
          backend RulesBrain
          role lead
          memory project
          context 12000
        }

        agent Architect {
          role architect
          tools [workspace.read, workspace.graph]
          allow [workspace.read, workspace.graph]
          deny [workspace.write, git.push]
          context 8000
        }

        agent Debugger {
          role debug
          tools [workspace.read, workspace.graph, workspace.diff]
          allow [workspace.read, workspace.graph, workspace.diff]
          deny [workspace.write, git.push]
          context 8000
        }

        agent Tester {
          role tests
          tools [workspace.read, tests.run]
          allow [workspace.read, tests.run]
          deny [workspace.write, git.push]
          context 6000
        }

        agent Security {
          role security
          tools [workspace.read, workspace.diff]
          allow [workspace.read, workspace.diff]
          deny [workspace.write, git.push, device.control]
          context 6000
        }

        agent Reviewer {
          role review
          tools [workspace.read, workspace.diff]
          allow [workspace.read, workspace.diff]
          deny [workspace.write, git.push, device.control]
          context 6000
        }

        swarm DevTeam {
          lead MainBrain
          workers [Architect, Debugger, Tester, Security, Reviewer]
          flow Architect -> Debugger
          flow Debugger -> Tester
          flow Tester -> Security
          flow Security -> Reviewer
          flow Reviewer -> MainBrain
          review 1
        }

        task RepairRiftOS {
          swarm DevTeam
          goal "Audit a RiftOS failure and return a tested, security-reviewed repair plan"
          retry 2
          require [tests, security, review]
        }
    """.trimIndent()
}
