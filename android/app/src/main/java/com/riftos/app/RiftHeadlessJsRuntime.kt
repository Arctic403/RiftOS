package com.riftos.app

import android.content.Context
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Headless trusted JavaScript service runtime.
 *
 * This is deliberately not a browser surface. It hosts the frozen Rift++ Core/RiftVM JavaScript
 * modules inside QuickJS with a tiny capability set: confined RiftFS text I/O, UTF-8 and SHA-256.
 * No DOM, network, Android intents, arbitrary native calls, or ambient shell globals are exposed.
 */
class RiftHeadlessJsRuntime(context: Context) {
    companion object {
        private const val MAX_TEXT_BYTES = 8L * 1024L * 1024L
        private const val EVALUATION_TIMEOUT_MS = 120_000L
    }

    data class CommandResult(val output: String, val result: JSONObject?)

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile

    @Volatile private var vmSourceCache: String? = null
    @Volatile private var coreSourceCache: String? = null

    fun executeRiftpp(args: List<String>, cwd: String): CommandResult {
        val request = JSONObject()
            .put("args", org.json.JSONArray(args))
            .put("cwd", cwd)
        var resultJson: String? = null

        runBlocking {
            quickJs {
                evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS

                function("__rift_request") { request.toString() }
                function("__rift_result") { values ->
                    resultJson = values.firstOrNull()?.toString()
                    Unit
                }
                function("__rift_utf8") { values ->
                    values.firstOrNull()?.toString().orEmpty().toByteArray(Charsets.UTF_8)
                }
                function("__rift_sha256") { values ->
                    val value = values.firstOrNull()
                    val bytes = when (value) {
                        is ByteArray -> value
                        is List<*> -> ByteArray(value.size) { index -> (value[index] as Number).toByte() }
                        else -> throw IllegalArgumentException("SHA-256 input must be a byte array")
                    }
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                }
                function("__rift_read_text") { values ->
                    val path = values.firstOrNull()?.toString().orEmpty()
                    val file = resolveFile(path, cwd)
                    require(file.isFile) { "file not found: $path" }
                    require(file.length() <= MAX_TEXT_BYTES) { "file exceeds headless runtime text limit: $path" }
                    file.readText(Charsets.UTF_8)
                }
                function("__rift_write_text") { values ->
                    val path = values.getOrNull(0)?.toString().orEmpty()
                    val text = values.getOrNull(1)?.toString().orEmpty()
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_TEXT_BYTES) { "output exceeds headless runtime text limit" }
                    val file = resolveFile(path, cwd)
                    file.parentFile?.mkdirs()
                    atomicWrite(file, bytes)
                    true
                }

                evaluate<Any?>(Scripts.POLYFILLS, filename = "rift-headless-polyfills.js")
                evaluate<Any?>(preparedVmSource(), filename = "riftvm.headless.js")
                evaluate<Any?>(preparedCoreSource(), filename = "riftpp-core.headless.js")
                evaluate<Any?>(
                    Scripts.RIFTPP_COMMAND_ENTRY,
                    filename = "riftpp-command.headless.js"
                )
            }
        }

        val payload = resultJson?.let(::JSONObject)
            ?: throw IllegalStateException("Headless Rift++ runtime returned no result")
        return CommandResult(
            output = payload.optString("output"),
            result = payload.optJSONObject("result")
        )
    }


    private fun preparedVmSource(): String {
        vmSourceCache?.let { return it }
        val source = readAsset("www/src/riftvm.js")
        val stripped = source.replace(Regex("(?m)^export\\s+"), "")
        return ("(function(){\n" + stripped + "\n" +
            "globalThis.RiftVMHeadless=Object.freeze({" +
            "RIFT_EXEC_FORMAT:RIFT_EXEC_FORMAT,RIFT_VM_ABI:RIFT_VM_ABI," +
            "prepareRiftExecutable:prepareRiftExecutable,executeRiftExecutable:executeRiftExecutable," +
            "inspectRiftExecutable:inspectRiftExecutable});\n})();").also { vmSourceCache = it }
    }

    private fun preparedCoreSource(): String {
        coreSourceCache?.let { return it }
        var source = readAsset("www/src/riftpp-core.js")
        source = source.replace(
            "import { RIFT_EXEC_FORMAT, RIFT_VM_ABI, prepareRiftExecutable } from './riftvm.js';",
            "const {RIFT_EXEC_FORMAT,RIFT_VM_ABI,prepareRiftExecutable}=globalThis.RiftVMHeadless;"
        )
        source = source.replace(Regex("(?m)^export\\s+"), "")
        return ("(function(){\n" + source + "\n})();").also { coreSourceCache = it }
    }

    private fun readAsset(path: String): String =
        appContext.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resolveFile(rawPath: String, cwd: String): File {
        var raw = rawPath.trim().replace('\\', '/')
        require(raw.isNotBlank()) { "RiftFS path is required" }
        if (!raw.startsWith("/") && !Regex("^[A-Za-z]:($|/)").containsMatchIn(raw)) {
            raw = cwd.trimEnd('/') + "/" + raw
        }
        val display = RiftVolumePaths.normalizeDisplay(raw)
        val relative = if (display.startsWith("/C:", true) || display.startsWith("/D:", true)) {
            RiftVolumePaths.resolveRelative(display)
        } else {
            display.trimStart('/')
        }
        val file = if (relative.isBlank()) riftRoot else File(riftRoot, relative).canonicalFile
        require(file == riftRoot || file.path.startsWith(riftRoot.path + File.separator)) { "Path escaped RiftFS" }
        return file
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.headless-${System.nanoTime()}")
        temp.writeBytes(bytes)
        val backup = File(target.parentFile, ".${target.name}.backup-${System.nanoTime()}")
        var backedUp = false
        try {
            if (target.exists()) {
                require(target.isFile) { "Headless output target is not a file" }
                require(target.renameTo(backup)) { "Could not stage existing output for atomic replacement" }
                backedUp = true
            }
            require(temp.renameTo(target)) { "Atomic output publish failed" }
            if (backedUp) backup.delete()
        } catch (error: Throwable) {
            temp.delete()
            if (backedUp && !target.exists()) backup.renameTo(target)
            throw error
        }
    }

    private object Scripts {
        private const val POLYFILLS = """
            globalThis.TextEncoder = class {
              encode(value) { return __rift_utf8(String(value)); }
            };
            globalThis.crypto = Object.freeze({
              subtle: Object.freeze({
                digest: async function(name, data) {
                  if (String(name).toUpperCase() !== 'SHA-256') throw new Error('Only SHA-256 is available');
                  const view = data instanceof ArrayBuffer ? new Int8Array(data) : data;
                  const out = __rift_sha256(view);
                  return out.buffer.slice(out.byteOffset, out.byteOffset + out.byteLength);
                }
              })
            });
        """


        private const val RIFTPP_COMMAND_ENTRY = """
            (async function() {
              const request = JSON.parse(__rift_request());
              const args = Array.from(request.args || []);
              const cwd = String(request.cwd || '/');
              const compiler = globalThis.RiftPlusPlusCore;
              const vm = globalThis.RiftVMHeadless;
              if (!compiler || !vm) throw new Error('Rift++ headless modules are unavailable');

              const output = [];
              const emit = value => output.push(String(value == null ? '' : value));
              const finish = result => __rift_result(JSON.stringify({output: output.join('\n'), result: result || null}));
              const usage = 'Rift++ Core shell (headless QuickJS)\n' +
                'riftpp help\nriftpp version\nriftpp self-test\nriftpp check <source.riftpp>\n' +
                'riftpp compile <source.riftpp> [output.rxe]\nriftpp inspect <source.riftpp|program.rxe>\n' +
                'riftpp run <source.riftpp>\nriftpp exec <program.rxe>';

              const normalizePath = value => {
                const raw = String(value || '').replaceAll('\\\\','/');
                const absolute = raw.startsWith('/') || /^[A-Za-z]:($|\/)/.test(raw);
                const joined = absolute ? raw : String(cwd).replace(/\/$/,'') + '/' + raw;
                const parts = [];
                for (const part of joined.split('/')) {
                  if (!part || part === '.') continue;
                  if (part === '..') { if (!parts.length) throw new Error('path escaped root'); parts.pop(); continue; }
                  parts.push(part);
                }
                return '/' + parts.join('/');
              };
              const read = path => __rift_read_text(normalizePath(path));
              const write = (path,text) => __rift_write_text(normalizePath(path), String(text));
              const sourcePath = value => {
                if (!value) throw new Error('Rift++ source path is required');
                const path = normalizePath(value);
                if (!/\.riftpp$/i.test(path)) throw new Error('Rift++ source must end in .riftpp: ' + path);
                return path;
              };
              const execPath = value => {
                if (!value) throw new Error('Rift executable path is required');
                const path = normalizePath(value);
                if (!/\.rxe$/i.test(path)) throw new Error('Rift executable must end in .rxe: ' + path);
                return path;
              };

              const compileSource = (path, source) => {
                const ast = compiler.parse(source);
                if (!ast.uses || !ast.uses.length) return compiler.compile(source);
                const normalized = String(path).replaceAll('\\\\','/');
                const suffix = ast.module.replaceAll('.','/') + '.riftpp';
                if (!normalized.endsWith(suffix)) throw new Error("Rift++ imported source path must mirror module '" + ast.module + "' as " + suffix);
                const moduleRoot = normalized.slice(0, normalized.length - suffix.length);
                const modules = Object.create(null);
                const loaded = new Set();
                const loadModule = name => {
                  if (name === ast.module) throw new Error("Rift++ cyclic module import returns to root '" + name + "'");
                  if (loaded.has(name)) return;
                  if (loaded.size >= 63) throw new Error('Rift++ module graph exceeds 64 total modules');
                  const modulePath = moduleRoot + name.replaceAll('.','/') + '.riftpp';
                  const text = read(modulePath);
                  const depAst = compiler.parse(text);
                  if (depAst.module !== name) throw new Error('Rift++ module identity mismatch: expected ' + name + ', found ' + depAst.module + ' in ' + modulePath);
                  modules[name] = text;
                  loaded.add(name);
                  for (const use of depAst.uses || []) loadModule(use.module);
                };
                for (const use of ast.uses) loadModule(use.module);
                return compiler.compileProgram(source, modules);
              };

              const inspectCompiled = result => ({
                schema: result.schema,
                language: result.language,
                compiler: result.compiler,
                module: result.module,
                modules: Array.from(result.modules || []),
                structs: result.ast.structs.map(item => item.name),
                enums: result.ast.enums.map(item => item.name),
                functions: result.ast.functions.map(item => item.name),
                imports: Array.from(result.executable.imports || []),
                bytes: new TextEncoder().encode(result.executableText).byteLength,
                targetFormat: result.executable.format,
                targetAbi: result.executable.abi
              });

              const execute = async (raw, label) => {
                const info = vm.inspectRiftExecutable(raw);
                if (info.imports.length) throw new Error('riftpp shell execution denies host imports: ' + info.imports.join(', '));
                const lines = [];
                let bytes = 0;
                const host = {
                  write: value => {
                    const text = String(value);
                    bytes += new TextEncoder().encode(text).byteLength + 1;
                    if (lines.length >= 256 || bytes > 65536) throw new Error('riftpp shell output limit exceeded');
                    lines.push(text);
                  }
                };
                const result = await vm.executeRiftExecutable(raw, host, {maxSteps:100000,maxStack:1024,maxCallDepth:32,yieldEvery:512});
                for (const line of lines) emit(line);
                const summary = {schema:'riftpp-shell-run/1',label:label,steps:result.steps,prints:result.prints,result:result.result};
                emit(JSON.stringify(summary,null,2));
                return {result:result,output:lines};
              };

              const sub = String(args.shift() || 'help').toLowerCase();
              if (sub === 'help') { emit(usage); finish({backend:'headless-quickjs'}); return; }
              if (sub === 'version') {
                const value = {language:compiler.language,compiler:compiler.version,targetFormat:compiler.targetFormat,targetAbi:compiler.targetAbi,backend:'headless-quickjs'};
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'self-test' || sub === 'selftest') {
                const source = 'riftpp 1\nmodule shell.selftest\nfn multiply(a: u32, b: u32) -> u32 { return a * b }\nfn main() { print("Rift++ shell self-test") print(multiply(6, 7)) print(multiply(6, 7) == 42) }\n';
                const compiled = compiler.compile(source);
                const executed = await execute(compiled.executable, 'embedded:self-test');
                const expected = ['Rift++ shell self-test','42','true'];
                if (JSON.stringify(executed.output) !== JSON.stringify(expected)) throw new Error('riftpp self-test output mismatch');
                const value = {ok:true,schema:'riftpp-shell-self-test/2',backend:'headless-quickjs',compiler:compiler.version,format:compiled.executable.format,abi:compiled.executable.abi,steps:executed.result.steps,prints:executed.result.prints};
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'check') {
                const path = sourcePath(args[0]), source = read(path), result = compileSource(path, source), info = inspectCompiled(result);
                const value = {ok:true,path:path,module:result.module,modules:Array.from(result.modules || []),functions:info.functions,bytes:info.bytes,targetFormat:info.targetFormat,targetAbi:info.targetAbi,backend:'headless-quickjs'};
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'compile') {
                const path = sourcePath(args[0]), source = read(path), result = compileSource(path, source);
                const out = args[1] ? normalizePath(args[1]) : path.replace(/\.riftpp$/i,'.rxe');
                if (!/\.rxe$/i.test(out)) throw new Error('Rift executable output must end in .rxe: ' + out);
                write(out, result.executableText);
                const value = {ok:true,source:path,output:out,module:result.module,modules:Array.from(result.modules || []),bytes:new TextEncoder().encode(result.executableText).byteLength,format:result.executable.format,abi:result.executable.abi,backend:'headless-quickjs'};
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'inspect') {
                if (!args[0]) throw new Error('usage: riftpp inspect <source.riftpp|program.rxe>');
                const path = normalizePath(args[0]), text = read(path);
                const value = /\.riftpp$/i.test(path) ? inspectCompiled(compileSource(path,text)) :
                  (/\.rxe$/i.test(path) ? vm.inspectRiftExecutable(text) : (()=>{throw new Error('riftpp inspect expects .riftpp or .rxe: ' + path)})());
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'run') {
                const path = sourcePath(args[0]), source = read(path), result = compileSource(path, source), executed = await execute(result.executable, path);
                finish({backend:'headless-quickjs',steps:executed.result.steps,prints:executed.result.prints}); return;
              }
              if (sub === 'exec') {
                const path = execPath(args[0]), executed = await execute(read(path), path);
                finish({backend:'headless-quickjs',steps:executed.result.steps,prints:executed.result.prints}); return;
              }
              throw new Error('unknown riftpp command: ' + sub + '\n' + usage);
            })();
        """
    }
}
