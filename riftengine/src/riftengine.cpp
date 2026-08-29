#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>
#include <emscripten/emscripten.h>

namespace {
std::vector<std::uint32_t> pixels;
int width = 0;
int height = 0;
float pointer_x = -100.0f;
float pointer_y = -100.0f;
double elapsed = 0.0;
std::string document_source;

std::uint32_t rgba(std::uint8_t r, std::uint8_t g, std::uint8_t b, std::uint8_t a = 255) {
  return static_cast<std::uint32_t>(r)
      | (static_cast<std::uint32_t>(g) << 8)
      | (static_cast<std::uint32_t>(b) << 16)
      | (static_cast<std::uint32_t>(a) << 24);
}

void put_pixel(int x, int y, std::uint32_t color) {
  if (x < 0 || y < 0 || x >= width || y >= height) return;
  pixels[static_cast<std::size_t>(y) * width + x] = color;
}

void fill_rect(int x, int y, int w, int h, std::uint32_t color) {
  const int x0 = std::max(0, x);
  const int y0 = std::max(0, y);
  const int x1 = std::min(width, x + w);
  const int y1 = std::min(height, y + h);
  for (int yy = y0; yy < y1; ++yy) {
    for (int xx = x0; xx < x1; ++xx) put_pixel(xx, yy, color);
  }
}

void fill_circle(int cx, int cy, int radius, std::uint32_t color) {
  const int rr = radius * radius;
  for (int y = cy - radius; y <= cy + radius; ++y) {
    for (int x = cx - radius; x <= cx + radius; ++x) {
      const int dx = x - cx;
      const int dy = y - cy;
      if (dx * dx + dy * dy <= rr) put_pixel(x, y, color);
    }
  }
}

void paint() {
  if (width <= 0 || height <= 0 || pixels.empty()) return;

  for (int y = 0; y < height; ++y) {
    const float fy = static_cast<float>(y) / std::max(1, height - 1);
    for (int x = 0; x < width; ++x) {
      const float fx = static_cast<float>(x) / std::max(1, width - 1);
      const auto r = static_cast<std::uint8_t>(10 + 18 * fx);
      const auto g = static_cast<std::uint8_t>(14 + 20 * fy);
      const auto b = static_cast<std::uint8_t>(24 + 30 * (1.0f - fy));
      pixels[static_cast<std::size_t>(y) * width + x] = rgba(r, g, b);
    }
  }

  const int margin = std::max(14, width / 24);
  fill_rect(margin, margin, width - margin * 2, std::max(52, height / 7), rgba(24, 31, 48));
  fill_rect(margin + 18, margin + 18, std::max(70, width / 4), 10, rgba(105, 217, 255));
  fill_rect(margin + 18, margin + 36, std::max(110, width / 2), 7, rgba(153, 163, 186));

  const int card_y = margin + std::max(72, height / 7) + 18;
  const int card_h = std::max(96, height - card_y - margin);
  fill_rect(margin, card_y, width - margin * 2, card_h, rgba(17, 23, 37));
  fill_rect(margin + 18, card_y + 20, width - margin * 2 - 36, 8, rgba(83, 94, 120));
  fill_rect(margin + 18, card_y + 42, std::max(80, (width - margin * 2 - 36) * 2 / 3), 8, rgba(83, 94, 120));

  const double pulse = (std::sin(elapsed * 0.003) + 1.0) * 0.5;
  const auto pulse_blue = static_cast<std::uint8_t>(170 + 70 * pulse);
  fill_rect(margin + 18, card_y + 72, std::max(80, width / 3), 34, rgba(55, pulse_blue, 245));

  if (pointer_x >= 0 && pointer_y >= 0) {
    fill_circle(static_cast<int>(pointer_x), static_cast<int>(pointer_y), 10, rgba(255, 255, 255, 235));
    fill_circle(static_cast<int>(pointer_x), static_cast<int>(pointer_y), 4, rgba(80, 180, 255));
  }
}
}

extern "C" {

EMSCRIPTEN_KEEPALIVE int rift_engine_create(int w, int h) {
  width = std::max(1, w);
  height = std::max(1, h);
  pixels.assign(static_cast<std::size_t>(width) * height, rgba(0, 0, 0));
  paint();
  return 1;
}

EMSCRIPTEN_KEEPALIVE int rift_engine_resize(int w, int h) {
  return rift_engine_create(w, h);
}

EMSCRIPTEN_KEEPALIVE void rift_engine_tick(double now_ms) {
  elapsed = now_ms;
  paint();
}

EMSCRIPTEN_KEEPALIVE void rift_engine_pointer(int type, float x, float y) {
  (void)type;
  pointer_x = x;
  pointer_y = y;
}

EMSCRIPTEN_KEEPALIVE int rift_engine_load_html(const char* html) {
  document_source = html ? html : "";
  return document_source.empty() ? 0 : 1;
}

EMSCRIPTEN_KEEPALIVE std::uint32_t* rift_engine_pixels() {
  return pixels.empty() ? nullptr : pixels.data();
}

EMSCRIPTEN_KEEPALIVE int rift_engine_width() { return width; }
EMSCRIPTEN_KEEPALIVE int rift_engine_height() { return height; }
EMSCRIPTEN_KEEPALIVE const char* rift_engine_version() { return "RiftEngine port-harness 0.1"; }

}
