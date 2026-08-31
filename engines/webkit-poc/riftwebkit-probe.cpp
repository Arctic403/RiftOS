// RiftWebKit Safari probe: minimal WebCore -> Skia raster -> browser canvas embedder.
//
// This intentionally tests the smallest useful browser-engine slice on iPhone:
// no networking, no pthreads, no SharedArrayBuffer, no GPU/WebGL presentation,
// no persistence, and guest JavaScript disabled. Touch/pointer input is forwarded
// into WebCore's EventHandler as mouse events so the probe tests real engine input.

#include "config.h"

#include "CommonAtomStrings.h"
#include "Document.h"
#include "DocumentLoader.h"
#include "DocumentView.h"
#include "DocumentWriter.h"
#include "EmptyClients.h"
#include "EventHandler.h"
#include "FrameLoader.h"
#include "GraphicsContextSkia.h"
#include "HandleUserInputEventResult.h"
#include "LocalFrame.h"
#include "LocalFrameInlines.h"
#include "LocalFrameView.h"
#include "MediaPlayer.h"
#include "Page.h"
#include "PageConfiguration.h"
#include "PlatformMouseEvent.h"
#include "Settings.h"
#include "SharedBuffer.h"

#include <JavaScriptCore/InitializeThreading.h>
#include <emscripten.h>
#include <emscripten/heap.h>
#include <pal/SessionID.h>
#include <wtf/MonotonicTime.h>
#include <wtf/ProcessPrivilege.h>
#include <wtf/text/WTFString.h>

WTF_IGNORE_WARNINGS_IN_THIRD_PARTY_CODE_BEGIN
#include <skia/core/SkImageInfo.h>
#include <skia/core/SkPixmap.h>
#include <skia/core/SkSurface.h>
WTF_IGNORE_WARNINGS_IN_THIRD_PARTY_CODE_END

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <span>

using namespace WTF::StringLiterals;

// The pinned Emscripten WebKit patch always asks the embedder to register its
// optional BIB media engine. This proof deliberately excludes media, so satisfy
// that port hook without registering an engine or pulling in the helper's media,
// networking, host-Audio, and pthread bridge.
namespace BIB {
void registerBibMediaEngine(WebCore::MediaEngineRegistrar)
{
}
} // namespace BIB

namespace {
constexpr int kWidth = 390;
constexpr int kHeight = 600;

struct ProbeEngine {
    RefPtr<WebCore::Page> page;
    RefPtr<WebCore::LocalFrame> frame;
    sk_sp<SkSurface> surface;
};

ProbeEngine* gEngine = nullptr;
std::array<uint8_t, kWidth * kHeight * 4> gPixels { };
uint64_t gFrameNumber = 0;

constexpr const char* kProbeHTML = R"HTML(<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#111}
#tap{display:block;width:100%;height:65vh;background:#1769e0}
#tap:active{background:#20c56b}
#footer{width:100%;height:35vh;background:#e13d57}
</style>
</head>
<body><a id="tap" href="#noop"></a><div id="footer"></div></body>
</html>)HTML";

bool pushFrame()
{
    if (!gEngine || !gEngine->frame || !gEngine->surface)
        return false;

    RefPtr view = gEngine->frame->view();
    if (!view)
        return false;

    if (RefPtr document = gEngine->frame->document())
        document->updateLayoutIgnorePendingStylesheets();

    WebCore::GraphicsContextSkia context(
        *gEngine->surface->getCanvas(),
        WebCore::RenderingMode::Unaccelerated,
        WebCore::RenderingPurpose::Unspecified);
    view->paint(context, WebCore::IntRect(0, 0, kWidth, kHeight));

    const auto info = SkImageInfo::Make(
        kWidth, kHeight, kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    SkPixmap destination(info, gPixels.data(), kWidth * 4);
    if (!gEngine->surface->readPixels(destination, 0, 0))
        return false;

    ++gFrameNumber;
    EM_ASM({
        const ptr = $0;
        const width = $1;
        const height = $2;
        const frame = $3;
        const count = width * height * 4;
        const copy = HEAPU8.slice(ptr, ptr + count);
        if (Module.riftFrame)
            Module.riftFrame(copy, width, height, frame);
    }, gPixels.data(), kWidth, kHeight, static_cast<int>(gFrameNumber));
    return true;
}

void sendMouseMove(double x, double y)
{
    if (!gEngine || !gEngine->frame)
        return;
    WebCore::PlatformMouseEvent event(
        { x, y }, { x, y }, WebCore::MouseButton::None,
        WebCore::PlatformEvent::Type::MouseMoved, 0, { },
        WTF::MonotonicTime::now(), 0, WebCore::SyntheticClickType::NoTap);
    gEngine->frame->eventHandler().mouseMoved(event);
}

void sendMouseButton(bool down, double x, double y)
{
    if (!gEngine || !gEngine->frame)
        return;
    WebCore::PlatformMouseEvent event(
        { x, y }, { x, y }, WebCore::MouseButton::Left,
        down ? WebCore::PlatformEvent::Type::MousePressed : WebCore::PlatformEvent::Type::MouseReleased,
        1, { }, WTF::MonotonicTime::now(), 0, WebCore::SyntheticClickType::NoTap);
    if (down)
        gEngine->frame->eventHandler().handleMousePressEvent(event);
    else
        gEngine->frame->eventHandler().handleMouseReleaseEvent(event);
}
} // namespace

extern "C" {

EMSCRIPTEN_KEEPALIVE int rift_render()
{
    return pushFrame() ? 1 : 0;
}

// type: 0 move, 1 press, 2 release.
EMSCRIPTEN_KEEPALIVE int rift_pointer(int type, double x, double y)
{
    if (!gEngine)
        return 0;

    x = std::max(0.0, std::min(x, static_cast<double>(kWidth - 1)));
    y = std::max(0.0, std::min(y, static_cast<double>(kHeight - 1)));

    if (type == 0)
        sendMouseMove(x, y);
    else if (type == 1) {
        sendMouseMove(x, y);
        sendMouseButton(true, x, y);
    } else if (type == 2)
        sendMouseButton(false, x, y);
    else
        return 0;

    // WebCore's press/release dispatch is synchronous for this local page.
    pushFrame();
    return 1;
}

EMSCRIPTEN_KEEPALIVE size_t rift_memory_bytes()
{
    return emscripten_get_heap_size();
}

} // extern "C"

int main()
{
    std::printf("RIFTWEBKIT: boot start\n");

    JSC::initialize();
    WTF::initializeMainThread();
    WTF::setProcessPrivileges(WTF::allPrivileges());
    WebCore::initializeCommonAtomStrings();
    std::printf("RIFTWEBKIT: core initialized\n");

    auto configuration = WebCore::pageConfigurationWithEmptyClients(
        std::nullopt, PAL::SessionID::defaultSessionID());
    auto page = WebCore::Page::create(WTF::move(configuration));
    page->settings().setScriptEnabled(false);
    page->settings().setAcceleratedCompositingEnabled(false);
    page->settings().setLoadsImagesAutomatically(false);

    RefPtr frame = page->localMainFrame();
    if (!frame) {
        std::fprintf(stderr, "RIFTWEBKIT: no local main frame\n");
        return 2;
    }

    frame->setView(WebCore::LocalFrameView::create(*frame, WebCore::IntSize(kWidth, kHeight)));
    frame->init();

    RefPtr view = frame->view();
    if (!view) {
        std::fprintf(stderr, "RIFTWEBKIT: no frame view\n");
        return 3;
    }
    view->setCanHaveScrollbars(false);

    Ref loader = frame->loader();
    RefPtr documentLoader = loader->activeDocumentLoader();
    if (!documentLoader) {
        std::fprintf(stderr, "RIFTWEBKIT: no document loader\n");
        return 4;
    }

    const auto html = std::span(
        reinterpret_cast<const uint8_t*>(kProbeHTML), std::strlen(kProbeHTML));
    documentLoader->writer().setMIMEType("text/html"_s);
    documentLoader->writer().begin({ });
    documentLoader->writer().addData(WebCore::SharedBuffer::create(html));
    documentLoader->writer().end();

    // Writer commits can replace the initial view.
    view = frame->view();
    if (!view) {
        std::fprintf(stderr, "RIFTWEBKIT: view disappeared after document commit\n");
        return 5;
    }
    view->resize(kWidth, kHeight);
    frame->protectedDocument()->updateLayoutIgnorePendingStylesheets();
    std::printf("RIFTWEBKIT: layout ready\n");

    const auto info = SkImageInfo::Make(
        kWidth, kHeight, kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    auto surface = SkSurfaces::Raster(info);
    if (!surface) {
        std::fprintf(stderr, "RIFTWEBKIT: raster surface failed\n");
        return 6;
    }

    gEngine = new ProbeEngine { WTF::move(page), WTF::move(frame), WTF::move(surface) };
    if (!pushFrame()) {
        std::fprintf(stderr, "RIFTWEBKIT: first paint failed\n");
        return 7;
    }

    std::printf("RIFTWEBKIT: first paint ready\n");
    EM_ASM({
        if (Module.riftReady)
            Module.riftReady();
    });

    emscripten_exit_with_live_runtime();
}
