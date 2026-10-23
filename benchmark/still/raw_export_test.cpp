#include "../../app/src/main/cpp/raw_still.cpp"
#include <stdexcept>
#include <cstdlib>

static void check(bool ok, const char* text) { if (!ok) throw std::runtime_error(text); }
int main() {
    constexpr unsigned w = 10000, h = 4;
    const size_t size = w * h * 3 * 2;
    auto* image = static_cast<libraw_processed_image_t*>(std::calloc(1, sizeof(libraw_processed_image_t) + size));
    image->type = LIBRAW_IMAGE_BITMAP;
    image->width = w; image->height = h; image->colors = 3; image->bits = 16; image->data_size = size;
    auto* samples = reinterpret_cast<uint16_t*>(image->data);
    for (size_t i = 0; i < w*h; ++i) for (unsigned c=0;c<3;++c) samples[3*i+c] = uint16_t(1024 + ((i+c*13)%1024)*60);
    for (int present : {1, 3}) {
        rawlook::CameraLook look;
        rawlook::prepare_camera_look(nullptr, 0, 5500.f, present == 3 ? 2 : 1, look);
        check(look.tone_active, "camera tone curve missing");
        Decoded camera, linear;
        check(pack_image(image, 0, present, 10000.f/203.f, 0.f, 0.f, &look, false, 0.5f, 16384.f, 0.f, 0.f, 0.f, camera), "RAW camera pack failed");
        check(pack_image(image, 0, present, 10000.f/203.f, 0.f, 0.f, nullptr, false, 0.5f, 16384.f, 0.f, 0.f, 0.f, linear), "RAW reference pack failed");
        check(camera.w == w && camera.h == h, "RAW export was capped to reader size");
        check(camera.format == 1 && camera.gamut == (present == 3 ? 2 : 1), "RAW precision or gamut lost");
        check(camera.is_hdr == (present == 3), "RAW dynamic range does not follow target");
        check(camera.pixels != linear.pixels, "RAW camera look not applied");
        if (present == 3) check(camera.boost > 1.f, "RAW HDR headroom lost");
        std::printf("RAW %s camera look: %dx%d F16, gamut=%d boost=%.3f\n", present==3?"HDR":"SDR",camera.w,camera.h,camera.gamut,camera.boost);
    }
    std::free(image);
}
