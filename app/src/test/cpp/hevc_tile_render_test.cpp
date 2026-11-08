// Host check: c++ -std=c++17 -O2 -fsanitize=address,undefined \
//   app/src/test/cpp/hevc_tile_render_test.cpp -o /tmp/hevc-tile-test && /tmp/hevc-tile-test
#include "../../main/cpp/hevc_tile_render.h"
#include <array>
#include <cassert>
#include <iostream>
#include <vector>

// Compare the tiled output to the original forward-overwrite sampling, including
// odd dimensions, partial edge tiles, tiny previews and full-resolution renders.
static void sampling() {
    for (int width : {1, 3, 17, 65}) for (int height : {1, 5, 19}) {
        for (int ow = 1; ow <= width; ++ow) for (int oh = 1; oh <= height; ++oh) {
            std::vector<std::array<float,3>> expected(ow*oh), actual(ow*oh);
            std::vector<unsigned> visits(ow*oh);
            auto color = [](int x, int y) { return uint16_t((x*401 + y*983)%65536); };
            for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
                expected[(y*oh/height)*ow+x*ow/width] = {color(x,y)/65535.f, color(x,y)/65535.f, color(x,y)/65535.f};
            }
            for (int y0 = 0; y0 < height; y0 += 7) for (int x0 = 0; x0 < width; x0 += 11) {
                std::vector<uint16_t> plane(11*7);
                for (int y = 0; y < 7; ++y) for (int x = 0; x < 11; ++x) plane[y*11+x] = color(x0+x,y0+y);
                HevcTileRender p{11,7,11,7,16,width,height,ow,oh,x0,y0,std::min(11,width-x0),std::min(7,height-y0),true,0,1,0,true,false,false};
                render_hevc_tile(plane.data(),plane.data(),plane.data(),nullptr,p,[&](size_t at,float r,float g,float b) {
                    visits[at/4]++; actual[at/4] = {r,g,b};
                });
            }
            for (size_t i = 0; i < actual.size(); ++i) {
                assert(visits[i] == 1);
                for (int c = 0; c < 3; ++c) assert(std::abs(expected[i][c]-actual[i][c]) < 1e-6f);
            }
        }
    }
}

static void color() {
    std::vector<float> lut(65537);
    for (size_t i = 0; i < lut.size(); ++i) lut[i] = float(i)/65536;
    uint16_t y = 64, cb = 512, cr = 512;
    HevcTileRender p{1,1,1,1,10,1,1,1,1,0,0,1,1,false,1,1,0,false,false,false};
    std::array<float,3> rgb{};
    auto store = [&](size_t,float r,float g,float b) { rgb = {r,g,b}; };
    render_hevc_tile(&y,&cb,&cr,lut.data(),p,store);
    for (auto c:rgb) assert(c == 0.f); // Limited-range black.
    y = 940;
    render_hevc_tile(&y,&cb,&cr,lut.data(),p,store);
    for (auto c:rgb) assert(std::abs(c-1.f) < 1e-6f); // Limited-range white.

    // BT.2020 red retains its source gamut, or converts to signed linear sRGB.
    p.full_range = true; p.matrix = 0; p.primaries = 9; p.depth = 16;
    y = cb = 0; cr = 65535; p.gamut = 2;
    p.source_bt2020 = true;
    render_hevc_tile(&y,&cb,&cr,lut.data(),p,store);
    assert(rgb[0] == 1.f && rgb[1] == 0.f && rgb[2] == 0.f);
    p.gamut = 0;
    render_hevc_tile(&y,&cb,&cr,lut.data(),p,store);
    assert(std::abs(rgb[0]-1.660491f) < 1e-6f && rgb[1] < 0 && rgb[2] < 0);
    p.preserve_encoded_color = true;
    render_hevc_tile(&y,&cb,&cr,lut.data(),p,store);
    assert(rgb[0] == 1.f && rgb[1] == 0.f); // ICC input stays in its encoded gamut.

    // HLG scene white (E'=1) maps to the 1000-nit reference display.
    p.preserve_encoded_color = false; p.gamut = 2; p.hlg = true;
    for (size_t i = 0; i < lut.size(); ++i) {
        const float x = float(i)/65536;
        lut[i] = x <= .5f ? 4*x*x : std::exp((x-.55991073f)/.17883277f)+.28466892f;
    }
    y = cb = cr = 65535;
    render_hevc_tile(&y,&cb,&cr,lut.data(),p,store);
    for (auto c:rgb) assert(std::abs(c-1000.f/203.f) < 1e-5f);
}

int main() {
    sampling(); color();
    std::cout << "HEVC tiled sampling, color/range, ICC preservation and HLG checks passed\n";
}
