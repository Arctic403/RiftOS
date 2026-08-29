// RiftEngine persistent JavaScriptCore host.
//
// This is intentionally a tiny embedding boundary instead of extending the
// stock `jsc` CLI shell. The WebKit/JSC build remains upstream-pinned; this
// file is RiftEngine-owned and is where browser-facing engine APIs will live.
//
// Phase 1 of the custom host establishes the ABI we want to expose from wasm.
// The build script will wire this target to the pinned JSC libraries only after
// their exact Emscripten link interface has been captured from the successful
// JSCOnly build. Keeping this source in RiftOS means future host changes do not
// require patching WebKit source.

#include <JavaScriptCore/JavaScript.h>
#include <emscripten/emscripten.h>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {
JSGlobalContextRef g_context = nullptr;
std::string g_result;

std::string toUTF8(JSStringRef value)
{
    if (!value)
        return {};
    const size_t maxSize = JSStringGetMaximumUTF8CStringSize(value);
    std::string result(maxSize, '\0');
    const size_t used = JSStringGetUTF8CString(value, result.data(), maxSize);
    if (!used)
        return {};
    result.resize(used - 1);
    return result;
}

const char* storeValue(JSValueRef value, JSValueRef exception)
{
    if (!g_context)
        return "RIFT_ERROR:no-context";

    JSValueRef selected = exception ? exception : value;
    JSStringRef text = JSValueToStringCopy(g_context, selected, nullptr);
    g_result = toUTF8(text);
    if (text)
        JSStringRelease(text);

    if (exception)
        g_result.insert(0, "RIFT_EXCEPTION:");
    return g_result.c_str();
}
}

extern "C" {

EMSCRIPTEN_KEEPALIVE
int rift_jsc_create()
{
    if (!g_context)
        g_context = JSGlobalContextCreate(nullptr);
    return g_context ? 1 : 0;
}

EMSCRIPTEN_KEEPALIVE
const char* rift_jsc_eval(const char* source)
{
    if (!source)
        return "RIFT_ERROR:null-source";
    if (!g_context && !rift_jsc_create())
        return "RIFT_ERROR:create-context";

    JSStringRef script = JSStringCreateWithUTF8CString(source);
    JSValueRef exception = nullptr;
    JSValueRef value = JSEvaluateScript(g_context, script, nullptr, nullptr, 1, &exception);
    JSStringRelease(script);
    return storeValue(value, exception);
}

EMSCRIPTEN_KEEPALIVE
void rift_jsc_destroy()
{
    if (g_context) {
        JSGlobalContextRelease(g_context);
        g_context = nullptr;
    }
    g_result.clear();
}

EMSCRIPTEN_KEEPALIVE
int rift_jsc_alive()
{
    return g_context ? 1 : 0;
}

}

int main()
{
    return rift_jsc_create() ? 0 : 1;
}
