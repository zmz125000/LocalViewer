#ifndef EHVIEWER_RAW_CAMERA_LOOK_H
#define EHVIEWER_RAW_CAMERA_LOOK_H

// DNG camera look applied on linear RGB after LibRaw.
// Hue/sat maps and the look table are the file's ProfileHueSatMap / ProfileLookTable.
// The tone curve is ProfileToneCurve when the file has one. Otherwise it is the
// Adobe Camera Raw default curve (dng_tone_curve_acr3_default). Maps run in linear
// ProPhoto, which is the space the DNG spec uses for these tables.

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace rawlook {

struct HueMap {
    int hue = 0;
    int sat = 0;
    int val = 0;
    int encoding = 0;
    std::vector<float> data;

    bool valid() const {
        if (hue < 1 || sat < 2 || val < 1) return false;
        size_t n = static_cast<size_t>(hue) * static_cast<size_t>(sat) * static_cast<size_t>(val) * 3u;
        return data.size() == n;
    }
};

struct CameraLook {
    std::vector<float> tone;
    float tone_slope = 1.f;
    bool tone_active = false;
    HueMap hue;
    HueMap look;
    bool has_hue = false;
    bool has_look = false;
    bool overrange = false;
    int space = 0;
};

inline uint32_t u16_at(const uint8_t* p, bool le) {
    return le ? (uint32_t)p[0] | ((uint32_t)p[1] << 8) : (uint32_t)p[1] | ((uint32_t)p[0] << 8);
}

inline uint32_t u32_at(const uint8_t* p, bool le) {
    return le ? (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24)
              : (uint32_t)p[3] | ((uint32_t)p[2] << 8) | ((uint32_t)p[1] << 16) | ((uint32_t)p[0] << 24);
}

inline void mul3(const float* m, float& r, float& g, float& b) {
    float x = m[0] * r + m[1] * g + m[2] * b;
    float y = m[3] * r + m[4] * g + m[5] * b;
    float z = m[6] * r + m[7] * g + m[8] * b;
    r = x;
    g = y;
    b = z;
}

// Linear output RGB (D65) to linear ProPhoto (D50), Bradford adapted, rows scaled so white stays white.
inline const float* to_prophoto(int space) {
    static const float kSrgb[9] = {0.52934589f, 0.33007283f, 0.14058127f, 0.09837435f, 0.87346096f,
                                    0.02816468f, 0.01688319f, 0.11767248f, 0.86544434f};
    static const float kP3[9] = {0.63169750f, 0.21386215f, 0.15444035f, 0.08319331f, 0.88586709f,
                                  0.03093961f, -0.00127049f, 0.05074518f, 0.95052530f};
    static const float kRec[9] = {0.83520617f, 0.04879132f, 0.11600251f, 0.05401916f, 0.92892328f,
                                   0.01705756f, -0.00233835f, 0.03632070f, 0.96601765f};
    if (space == 2) return kRec;
    if (space == 1) return kP3;
    return kSrgb;
}

inline const float* from_prophoto(int space) {
    static const float kSrgb[9] = {2.03407609f, -0.72733440f, -0.30674169f, -0.22881338f, 1.23173028f,
                                    -0.00291690f, -0.00856976f, -0.15328661f, 1.16185637f};
    static const float kP3[9] = {1.63252411f, -0.37963028f, -0.25289383f, -0.15367591f, 1.16668233f,
                                  -0.01300642f, 0.01038627f, -0.06279246f, 1.05240619f};
    static const float kRec[9] = {1.20062504f, -0.05746482f, -0.14316023f, -0.06992092f, 1.08060550f,
                                   -0.01068458f, 0.00553516f, -0.04076811f, 1.03523296f};
    if (space == 2) return kRec;
    if (space == 1) return kP3;
    return kSrgb;
}

inline float srgb_encode(float x) {
    if (!(x > 0.f)) return 0.f;
    if (x >= 1.f) return 1.f;
    if (x <= 0.0031308f) return 12.92f * x;
    return 1.055f * std::pow(x, 1.f / 2.4f) - 0.055f;
}

inline float srgb_decode(float x) {
    if (!(x > 0.f)) return 0.f;
    if (x >= 1.f) return 1.f;
    if (x <= 0.04045f) return x / 12.92f;
    return std::pow((x + 0.055f) / 1.055f, 2.4f);
}

inline float encode_overrange(float x) {
    x = std::max(x, 0.f);
    return x * (256.f + x) / (256.f * (1.f + x));
}

inline float decode_overrange(float x) {
    x = std::max(x, 0.f);
    return 16.f * ((8.f * x) - 8.f + std::sqrt(std::max(0.f, 64.f * x * x - 127.f * x + 64.f)));
}

inline void rgb_to_hsv(float r, float g, float b, float& h, float& s, float& v) {
    r = std::max(r, 0.f);
    g = std::max(g, 0.f);
    b = std::max(b, 0.f);
    v = std::max(r, std::max(g, b));
    float gap = v - std::min(r, std::min(g, b));
    if (gap > 0.f && v > 0.f) {
        if (r == v) {
            h = (g - b) / gap;
            if (h < 0.f) h += 6.f;
        } else if (g == v) {
            h = 2.f + (b - r) / gap;
        } else {
            h = 4.f + (r - g) / gap;
        }
        s = gap / v;
    } else {
        h = 0.f;
        s = 0.f;
    }
}

inline void hsv_to_rgb(float h, float s, float v, float& r, float& g, float& b) {
    if (s > 0.f) {
        if (!std::isfinite(h)) h = 0.f;
        h = std::fmod(h, 6.f);
        if (h < 0.f) h += 6.f;
        int i = static_cast<int>(h);
        float f = h - static_cast<float>(i);
        float p = v * (1.f - s);
        float q = v * (1.f - s * f);
        float t = v * (1.f - s * (1.f - f));
        switch (i) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            case 5: r = v; g = p; b = q; break;
            default: r = v; g = t; b = p; break;
        }
    } else {
        r = g = b = v;
    }
}

inline float tone_map(const CameraLook& look, float x) {
    const int n = static_cast<int>(look.tone.size());
    if (n < 2 || !std::isfinite(x)) return x > 0.f ? x : 0.f;
    if (!(x > 0.f)) return 0.f;
    if (x > 1.f) return look.tone[n - 1] + (x - 1.f) * look.tone_slope;
    float y = x * static_cast<float>(n - 1);
    int i = static_cast<int>(y);
    if (i >= n - 1) return look.tone[n - 1];
    if (i < 0) i = 0;
    float f = y - static_cast<float>(i);
    return look.tone[i] * (1.f - f) + look.tone[i + 1] * f;
}

inline void apply_rgb_tone(const CameraLook& look, float& r, float& g, float& b) {
    auto finish = [&](float rr, float gg, float bb) {
        r = rr;
        g = gg;
        b = bb;
    };
    auto blend = [&](float hi, float mid, float lo, float& ohi, float& omid, float& olo) {
        ohi = tone_map(look, hi);
        olo = tone_map(look, lo);
        float span = hi - lo;
        omid = span > 1e-8f ? olo + (ohi - olo) * (mid - lo) / span : ohi;
    };
    float rr = 0.f;
    float gg = 0.f;
    float bb = 0.f;
    if (r >= g) {
        if (g > b) blend(r, g, b, rr, gg, bb);
        else if (b > r) blend(b, r, g, bb, rr, gg);
        else if (b > g) blend(r, b, g, rr, bb, gg);
        else {
            rr = tone_map(look, r);
            gg = tone_map(look, g);
            bb = gg;
        }
    } else if (r >= b) {
        blend(g, r, b, gg, rr, bb);
    } else if (b > g) {
        blend(b, g, r, bb, gg, rr);
    } else {
        blend(g, b, r, gg, bb, rr);
    }
    finish(rr, gg, bb);
}

inline float map_at(const HueMap& map, int v, int h, int s, int channel) {
    size_t i = ((static_cast<size_t>(v) * static_cast<size_t>(map.hue) + static_cast<size_t>(h)) *
                    static_cast<size_t>(map.sat) +
                static_cast<size_t>(s)) *
                   3u +
               static_cast<size_t>(channel);
    if (i >= map.data.size()) return channel == 0 ? 0.f : 1.f;
    return map.data[i];
}

inline void sample_hue_map(const HueMap& map, float h, float s, float v_enc, float& hue_shift, float& sat_scale,
                           float& val_scale) {
    float h_scale = map.hue < 2 ? 0.f : static_cast<float>(map.hue) * (1.f / 6.f);
    float s_scale = static_cast<float>(map.sat - 1);
    float v_scale = static_cast<float>(map.val - 1);
    int max_hue = map.hue - 1;
    int max_sat = map.sat - 2;
    int max_val = map.val - 2;
    float h_scaled = h * h_scale;
    float s_scaled = std::min(std::max(s, 0.f), 1.f) * s_scale;
    int h0 = static_cast<int>(h_scaled);
    int s0 = static_cast<int>(s_scaled);
    if (s0 > max_sat) s0 = max_sat;
    if (s0 < 0) s0 = 0;
    int h1 = h0 + 1;
    if (h0 >= max_hue) {
        h0 = max_hue;
        h1 = map.hue < 2 ? 0 : 0;
        if (h0 < 0) h0 = 0;
    }
    if (h0 < 0) h0 = 0;
    if (h1 < 0) h1 = 0;
    if (h1 >= map.hue) h1 = 0;
    float hf = h_scaled - static_cast<float>(h0);
    float sf = s_scaled - static_cast<float>(s0);
    if (hf < 0.f) hf = 0.f;
    if (sf < 0.f) sf = 0.f;
    auto lerp_sat = [&](int v_index, int channel) {
        float h0s0 = map_at(map, v_index, h0, s0, channel);
        float h1s0 = map_at(map, v_index, h1, s0, channel);
        float h0s1 = map_at(map, v_index, h0, s0 + 1, channel);
        float h1s1 = map_at(map, v_index, h1, s0 + 1, channel);
        float a = h0s0 * (1.f - hf) + h1s0 * hf;
        float b = h0s1 * (1.f - hf) + h1s1 * hf;
        return a * (1.f - sf) + b * sf;
    };
    if (map.val < 2) {
        hue_shift = lerp_sat(0, 0);
        sat_scale = lerp_sat(0, 1);
        val_scale = lerp_sat(0, 2);
        return;
    }
    float v_scaled = std::min(std::max(v_enc, 0.f), 1.f) * v_scale;
    int v0 = static_cast<int>(v_scaled);
    if (v0 > max_val) v0 = max_val;
    if (v0 < 0) v0 = 0;
    int v1 = std::min(v0 + 1, map.val - 1);
    float vf = v_scaled - static_cast<float>(v0);
    if (vf < 0.f) vf = 0.f;
    hue_shift = lerp_sat(v0, 0) * (1.f - vf) + lerp_sat(v1, 0) * vf;
    sat_scale = lerp_sat(v0, 1) * (1.f - vf) + lerp_sat(v1, 1) * vf;
    val_scale = lerp_sat(v0, 2) * (1.f - vf) + lerp_sat(v1, 2) * vf;
}

inline void apply_hue_map(const HueMap& map, float& r, float& g, float& b, bool overrange = false) {
    if (!map.valid()) return;
    // 3D tables in HDR use the DNG SDK overrange fit so values above 1 still index the table.
    // 2.5D tables do not. The sRGB encoding tag applies only when the value axis has more than one sample.
    bool compress = overrange && map.val > 1;
    bool srgb = map.val > 1 && map.encoding == 1;
    float rr = r;
    float gg = g;
    float bb = b;
    if (compress) {
        rr = encode_overrange(rr);
        gg = encode_overrange(gg);
        bb = encode_overrange(bb);
    }
    float h, s, v;
    rgb_to_hsv(rr, gg, bb, h, s, v);
    float v_enc = v;
    if (srgb) v_enc = srgb_encode(std::min(std::max(v, 0.f), 1.f));
    float hue_shift = 0.f;
    float sat_scale = 1.f;
    float val_scale = 1.f;
    sample_hue_map(map, h, s, v_enc, hue_shift, sat_scale, val_scale);
    h += hue_shift * (6.f / 360.f);
    s = std::min(std::max(s * sat_scale, 0.f), 1.f);
    v_enc = v_enc * val_scale;
    if (v_enc < 0.f) v_enc = 0.f;
    if (v_enc > 1.f) v_enc = 1.f;
    v = srgb ? srgb_decode(v_enc) : v_enc;
    hsv_to_rgb(h, s, v, rr, gg, bb);
    if (compress) {
        rr = decode_overrange(rr);
        gg = decode_overrange(gg);
        bb = decode_overrange(bb);
    }
    r = rr;
    g = gg;
    b = bb;
}

inline void apply_camera_look(const CameraLook& look, float exposure_ev, float& r, float& g, float& b) {
    if (!std::isfinite(r) || !std::isfinite(g) || !std::isfinite(b)) {
        r = g = b = 0.f;
        return;
    }
    r = std::max(r, 0.f);
    g = std::max(g, 0.f);
    b = std::max(b, 0.f);
    const bool color = look.has_hue || look.has_look || look.tone_active;
    float ev = std::isfinite(exposure_ev) ? exposure_ev : 0.f;
    float gain = std::exp2(ev);
    if (!std::isfinite(gain) || gain < 0.f) gain = 1.f;
    if (!color) {
        r *= gain;
        g *= gain;
        b *= gain;
        return;
    }
    mul3(to_prophoto(look.space), r, g, b);
    r = std::max(r, 0.f);
    g = std::max(g, 0.f);
    b = std::max(b, 0.f);
    if (look.has_hue) apply_hue_map(look.hue, r, g, b, look.overrange);
    r *= gain;
    g *= gain;
    b *= gain;
    if (look.has_look) apply_hue_map(look.look, r, g, b, look.overrange);
    if (look.tone_active) apply_rgb_tone(look, r, g, b);
    mul3(from_prophoto(look.space), r, g, b);
    if (!std::isfinite(r) || !std::isfinite(g) || !std::isfinite(b)) r = g = b = 0.f;
}

inline void set_acr3(CameraLook& look) {
    static const float kAcr3[1025] = {
    0.00000f, 0.00078f, 0.00160f, 0.00242f, 0.00314f, 0.00385f, 0.00460f, 0.00539f,
    0.00623f, 0.00712f, 0.00806f, 0.00906f, 0.01012f, 0.01122f, 0.01238f, 0.01359f,
    0.01485f, 0.01616f, 0.01751f, 0.01890f, 0.02033f, 0.02180f, 0.02331f, 0.02485f,
    0.02643f, 0.02804f, 0.02967f, 0.03134f, 0.03303f, 0.03475f, 0.03648f, 0.03824f,
    0.04002f, 0.04181f, 0.04362f, 0.04545f, 0.04730f, 0.04916f, 0.05103f, 0.05292f,
    0.05483f, 0.05675f, 0.05868f, 0.06063f, 0.06259f, 0.06457f, 0.06655f, 0.06856f,
    0.07057f, 0.07259f, 0.07463f, 0.07668f, 0.07874f, 0.08081f, 0.08290f, 0.08499f,
    0.08710f, 0.08921f, 0.09134f, 0.09348f, 0.09563f, 0.09779f, 0.09996f, 0.10214f,
    0.10433f, 0.10652f, 0.10873f, 0.11095f, 0.11318f, 0.11541f, 0.11766f, 0.11991f,
    0.12218f, 0.12445f, 0.12673f, 0.12902f, 0.13132f, 0.13363f, 0.13595f, 0.13827f,
    0.14061f, 0.14295f, 0.14530f, 0.14765f, 0.15002f, 0.15239f, 0.15477f, 0.15716f,
    0.15956f, 0.16197f, 0.16438f, 0.16680f, 0.16923f, 0.17166f, 0.17410f, 0.17655f,
    0.17901f, 0.18148f, 0.18395f, 0.18643f, 0.18891f, 0.19141f, 0.19391f, 0.19641f,
    0.19893f, 0.20145f, 0.20398f, 0.20651f, 0.20905f, 0.21160f, 0.21416f, 0.21672f,
    0.21929f, 0.22185f, 0.22440f, 0.22696f, 0.22950f, 0.23204f, 0.23458f, 0.23711f,
    0.23963f, 0.24215f, 0.24466f, 0.24717f, 0.24967f, 0.25216f, 0.25465f, 0.25713f,
    0.25961f, 0.26208f, 0.26454f, 0.26700f, 0.26945f, 0.27189f, 0.27433f, 0.27676f,
    0.27918f, 0.28160f, 0.28401f, 0.28641f, 0.28881f, 0.29120f, 0.29358f, 0.29596f,
    0.29833f, 0.30069f, 0.30305f, 0.30540f, 0.30774f, 0.31008f, 0.31241f, 0.31473f,
    0.31704f, 0.31935f, 0.32165f, 0.32395f, 0.32623f, 0.32851f, 0.33079f, 0.33305f,
    0.33531f, 0.33756f, 0.33981f, 0.34205f, 0.34428f, 0.34650f, 0.34872f, 0.35093f,
    0.35313f, 0.35532f, 0.35751f, 0.35969f, 0.36187f, 0.36404f, 0.36620f, 0.36835f,
    0.37050f, 0.37264f, 0.37477f, 0.37689f, 0.37901f, 0.38112f, 0.38323f, 0.38533f,
    0.38742f, 0.38950f, 0.39158f, 0.39365f, 0.39571f, 0.39777f, 0.39982f, 0.40186f,
    0.40389f, 0.40592f, 0.40794f, 0.40996f, 0.41197f, 0.41397f, 0.41596f, 0.41795f,
    0.41993f, 0.42191f, 0.42388f, 0.42584f, 0.42779f, 0.42974f, 0.43168f, 0.43362f,
    0.43554f, 0.43747f, 0.43938f, 0.44129f, 0.44319f, 0.44509f, 0.44698f, 0.44886f,
    0.45073f, 0.45260f, 0.45447f, 0.45632f, 0.45817f, 0.46002f, 0.46186f, 0.46369f,
    0.46551f, 0.46733f, 0.46914f, 0.47095f, 0.47275f, 0.47454f, 0.47633f, 0.47811f,
    0.47989f, 0.48166f, 0.48342f, 0.48518f, 0.48693f, 0.48867f, 0.49041f, 0.49214f,
    0.49387f, 0.49559f, 0.49730f, 0.49901f, 0.50072f, 0.50241f, 0.50410f, 0.50579f,
    0.50747f, 0.50914f, 0.51081f, 0.51247f, 0.51413f, 0.51578f, 0.51742f, 0.51906f,
    0.52069f, 0.52232f, 0.52394f, 0.52556f, 0.52717f, 0.52878f, 0.53038f, 0.53197f,
    0.53356f, 0.53514f, 0.53672f, 0.53829f, 0.53986f, 0.54142f, 0.54297f, 0.54452f,
    0.54607f, 0.54761f, 0.54914f, 0.55067f, 0.55220f, 0.55371f, 0.55523f, 0.55673f,
    0.55824f, 0.55973f, 0.56123f, 0.56271f, 0.56420f, 0.56567f, 0.56715f, 0.56861f,
    0.57007f, 0.57153f, 0.57298f, 0.57443f, 0.57587f, 0.57731f, 0.57874f, 0.58017f,
    0.58159f, 0.58301f, 0.58443f, 0.58583f, 0.58724f, 0.58864f, 0.59003f, 0.59142f,
    0.59281f, 0.59419f, 0.59556f, 0.59694f, 0.59830f, 0.59966f, 0.60102f, 0.60238f,
    0.60373f, 0.60507f, 0.60641f, 0.60775f, 0.60908f, 0.61040f, 0.61173f, 0.61305f,
    0.61436f, 0.61567f, 0.61698f, 0.61828f, 0.61957f, 0.62087f, 0.62216f, 0.62344f,
    0.62472f, 0.62600f, 0.62727f, 0.62854f, 0.62980f, 0.63106f, 0.63232f, 0.63357f,
    0.63482f, 0.63606f, 0.63730f, 0.63854f, 0.63977f, 0.64100f, 0.64222f, 0.64344f,
    0.64466f, 0.64587f, 0.64708f, 0.64829f, 0.64949f, 0.65069f, 0.65188f, 0.65307f,
    0.65426f, 0.65544f, 0.65662f, 0.65779f, 0.65897f, 0.66013f, 0.66130f, 0.66246f,
    0.66362f, 0.66477f, 0.66592f, 0.66707f, 0.66821f, 0.66935f, 0.67048f, 0.67162f,
    0.67275f, 0.67387f, 0.67499f, 0.67611f, 0.67723f, 0.67834f, 0.67945f, 0.68055f,
    0.68165f, 0.68275f, 0.68385f, 0.68494f, 0.68603f, 0.68711f, 0.68819f, 0.68927f,
    0.69035f, 0.69142f, 0.69249f, 0.69355f, 0.69461f, 0.69567f, 0.69673f, 0.69778f,
    0.69883f, 0.69988f, 0.70092f, 0.70196f, 0.70300f, 0.70403f, 0.70506f, 0.70609f,
    0.70711f, 0.70813f, 0.70915f, 0.71017f, 0.71118f, 0.71219f, 0.71319f, 0.71420f,
    0.71520f, 0.71620f, 0.71719f, 0.71818f, 0.71917f, 0.72016f, 0.72114f, 0.72212f,
    0.72309f, 0.72407f, 0.72504f, 0.72601f, 0.72697f, 0.72794f, 0.72890f, 0.72985f,
    0.73081f, 0.73176f, 0.73271f, 0.73365f, 0.73460f, 0.73554f, 0.73647f, 0.73741f,
    0.73834f, 0.73927f, 0.74020f, 0.74112f, 0.74204f, 0.74296f, 0.74388f, 0.74479f,
    0.74570f, 0.74661f, 0.74751f, 0.74842f, 0.74932f, 0.75021f, 0.75111f, 0.75200f,
    0.75289f, 0.75378f, 0.75466f, 0.75555f, 0.75643f, 0.75730f, 0.75818f, 0.75905f,
    0.75992f, 0.76079f, 0.76165f, 0.76251f, 0.76337f, 0.76423f, 0.76508f, 0.76594f,
    0.76679f, 0.76763f, 0.76848f, 0.76932f, 0.77016f, 0.77100f, 0.77183f, 0.77267f,
    0.77350f, 0.77432f, 0.77515f, 0.77597f, 0.77680f, 0.77761f, 0.77843f, 0.77924f,
    0.78006f, 0.78087f, 0.78167f, 0.78248f, 0.78328f, 0.78408f, 0.78488f, 0.78568f,
    0.78647f, 0.78726f, 0.78805f, 0.78884f, 0.78962f, 0.79040f, 0.79118f, 0.79196f,
    0.79274f, 0.79351f, 0.79428f, 0.79505f, 0.79582f, 0.79658f, 0.79735f, 0.79811f,
    0.79887f, 0.79962f, 0.80038f, 0.80113f, 0.80188f, 0.80263f, 0.80337f, 0.80412f,
    0.80486f, 0.80560f, 0.80634f, 0.80707f, 0.80780f, 0.80854f, 0.80926f, 0.80999f,
    0.81072f, 0.81144f, 0.81216f, 0.81288f, 0.81360f, 0.81431f, 0.81503f, 0.81574f,
    0.81645f, 0.81715f, 0.81786f, 0.81856f, 0.81926f, 0.81996f, 0.82066f, 0.82135f,
    0.82205f, 0.82274f, 0.82343f, 0.82412f, 0.82480f, 0.82549f, 0.82617f, 0.82685f,
    0.82753f, 0.82820f, 0.82888f, 0.82955f, 0.83022f, 0.83089f, 0.83155f, 0.83222f,
    0.83288f, 0.83354f, 0.83420f, 0.83486f, 0.83552f, 0.83617f, 0.83682f, 0.83747f,
    0.83812f, 0.83877f, 0.83941f, 0.84005f, 0.84069f, 0.84133f, 0.84197f, 0.84261f,
    0.84324f, 0.84387f, 0.84450f, 0.84513f, 0.84576f, 0.84639f, 0.84701f, 0.84763f,
    0.84825f, 0.84887f, 0.84949f, 0.85010f, 0.85071f, 0.85132f, 0.85193f, 0.85254f,
    0.85315f, 0.85375f, 0.85436f, 0.85496f, 0.85556f, 0.85615f, 0.85675f, 0.85735f,
    0.85794f, 0.85853f, 0.85912f, 0.85971f, 0.86029f, 0.86088f, 0.86146f, 0.86204f,
    0.86262f, 0.86320f, 0.86378f, 0.86435f, 0.86493f, 0.86550f, 0.86607f, 0.86664f,
    0.86720f, 0.86777f, 0.86833f, 0.86889f, 0.86945f, 0.87001f, 0.87057f, 0.87113f,
    0.87168f, 0.87223f, 0.87278f, 0.87333f, 0.87388f, 0.87443f, 0.87497f, 0.87552f,
    0.87606f, 0.87660f, 0.87714f, 0.87768f, 0.87821f, 0.87875f, 0.87928f, 0.87981f,
    0.88034f, 0.88087f, 0.88140f, 0.88192f, 0.88244f, 0.88297f, 0.88349f, 0.88401f,
    0.88453f, 0.88504f, 0.88556f, 0.88607f, 0.88658f, 0.88709f, 0.88760f, 0.88811f,
    0.88862f, 0.88912f, 0.88963f, 0.89013f, 0.89063f, 0.89113f, 0.89163f, 0.89212f,
    0.89262f, 0.89311f, 0.89360f, 0.89409f, 0.89458f, 0.89507f, 0.89556f, 0.89604f,
    0.89653f, 0.89701f, 0.89749f, 0.89797f, 0.89845f, 0.89892f, 0.89940f, 0.89987f,
    0.90035f, 0.90082f, 0.90129f, 0.90176f, 0.90222f, 0.90269f, 0.90316f, 0.90362f,
    0.90408f, 0.90454f, 0.90500f, 0.90546f, 0.90592f, 0.90637f, 0.90683f, 0.90728f,
    0.90773f, 0.90818f, 0.90863f, 0.90908f, 0.90952f, 0.90997f, 0.91041f, 0.91085f,
    0.91130f, 0.91173f, 0.91217f, 0.91261f, 0.91305f, 0.91348f, 0.91392f, 0.91435f,
    0.91478f, 0.91521f, 0.91564f, 0.91606f, 0.91649f, 0.91691f, 0.91734f, 0.91776f,
    0.91818f, 0.91860f, 0.91902f, 0.91944f, 0.91985f, 0.92027f, 0.92068f, 0.92109f,
    0.92150f, 0.92191f, 0.92232f, 0.92273f, 0.92314f, 0.92354f, 0.92395f, 0.92435f,
    0.92475f, 0.92515f, 0.92555f, 0.92595f, 0.92634f, 0.92674f, 0.92713f, 0.92753f,
    0.92792f, 0.92831f, 0.92870f, 0.92909f, 0.92947f, 0.92986f, 0.93025f, 0.93063f,
    0.93101f, 0.93139f, 0.93177f, 0.93215f, 0.93253f, 0.93291f, 0.93328f, 0.93366f,
    0.93403f, 0.93440f, 0.93478f, 0.93515f, 0.93551f, 0.93588f, 0.93625f, 0.93661f,
    0.93698f, 0.93734f, 0.93770f, 0.93807f, 0.93843f, 0.93878f, 0.93914f, 0.93950f,
    0.93986f, 0.94021f, 0.94056f, 0.94092f, 0.94127f, 0.94162f, 0.94197f, 0.94231f,
    0.94266f, 0.94301f, 0.94335f, 0.94369f, 0.94404f, 0.94438f, 0.94472f, 0.94506f,
    0.94540f, 0.94573f, 0.94607f, 0.94641f, 0.94674f, 0.94707f, 0.94740f, 0.94774f,
    0.94807f, 0.94839f, 0.94872f, 0.94905f, 0.94937f, 0.94970f, 0.95002f, 0.95035f,
    0.95067f, 0.95099f, 0.95131f, 0.95163f, 0.95194f, 0.95226f, 0.95257f, 0.95289f,
    0.95320f, 0.95351f, 0.95383f, 0.95414f, 0.95445f, 0.95475f, 0.95506f, 0.95537f,
    0.95567f, 0.95598f, 0.95628f, 0.95658f, 0.95688f, 0.95718f, 0.95748f, 0.95778f,
    0.95808f, 0.95838f, 0.95867f, 0.95897f, 0.95926f, 0.95955f, 0.95984f, 0.96013f,
    0.96042f, 0.96071f, 0.96100f, 0.96129f, 0.96157f, 0.96186f, 0.96214f, 0.96242f,
    0.96271f, 0.96299f, 0.96327f, 0.96355f, 0.96382f, 0.96410f, 0.96438f, 0.96465f,
    0.96493f, 0.96520f, 0.96547f, 0.96574f, 0.96602f, 0.96629f, 0.96655f, 0.96682f,
    0.96709f, 0.96735f, 0.96762f, 0.96788f, 0.96815f, 0.96841f, 0.96867f, 0.96893f,
    0.96919f, 0.96945f, 0.96971f, 0.96996f, 0.97022f, 0.97047f, 0.97073f, 0.97098f,
    0.97123f, 0.97149f, 0.97174f, 0.97199f, 0.97223f, 0.97248f, 0.97273f, 0.97297f,
    0.97322f, 0.97346f, 0.97371f, 0.97395f, 0.97419f, 0.97443f, 0.97467f, 0.97491f,
    0.97515f, 0.97539f, 0.97562f, 0.97586f, 0.97609f, 0.97633f, 0.97656f, 0.97679f,
    0.97702f, 0.97725f, 0.97748f, 0.97771f, 0.97794f, 0.97817f, 0.97839f, 0.97862f,
    0.97884f, 0.97907f, 0.97929f, 0.97951f, 0.97973f, 0.97995f, 0.98017f, 0.98039f,
    0.98061f, 0.98082f, 0.98104f, 0.98125f, 0.98147f, 0.98168f, 0.98189f, 0.98211f,
    0.98232f, 0.98253f, 0.98274f, 0.98295f, 0.98315f, 0.98336f, 0.98357f, 0.98377f,
    0.98398f, 0.98418f, 0.98438f, 0.98458f, 0.98478f, 0.98498f, 0.98518f, 0.98538f,
    0.98558f, 0.98578f, 0.98597f, 0.98617f, 0.98636f, 0.98656f, 0.98675f, 0.98694f,
    0.98714f, 0.98733f, 0.98752f, 0.98771f, 0.98789f, 0.98808f, 0.98827f, 0.98845f,
    0.98864f, 0.98882f, 0.98901f, 0.98919f, 0.98937f, 0.98955f, 0.98973f, 0.98991f,
    0.99009f, 0.99027f, 0.99045f, 0.99063f, 0.99080f, 0.99098f, 0.99115f, 0.99133f,
    0.99150f, 0.99167f, 0.99184f, 0.99201f, 0.99218f, 0.99235f, 0.99252f, 0.99269f,
    0.99285f, 0.99302f, 0.99319f, 0.99335f, 0.99351f, 0.99368f, 0.99384f, 0.99400f,
    0.99416f, 0.99432f, 0.99448f, 0.99464f, 0.99480f, 0.99495f, 0.99511f, 0.99527f,
    0.99542f, 0.99558f, 0.99573f, 0.99588f, 0.99603f, 0.99619f, 0.99634f, 0.99649f,
    0.99664f, 0.99678f, 0.99693f, 0.99708f, 0.99722f, 0.99737f, 0.99751f, 0.99766f,
    0.99780f, 0.99794f, 0.99809f, 0.99823f, 0.99837f, 0.99851f, 0.99865f, 0.99879f,
    0.99892f, 0.99906f, 0.99920f, 0.99933f, 0.99947f, 0.99960f, 0.99974f, 0.99987f,
    1.00000f,
    };
    look.tone.assign(kAcr3, kAcr3 + 1025);
    look.tone_slope = (kAcr3[1024] - kAcr3[1023]) * 1024.f;
    look.tone_active = true;
}

inline bool build_tone_from_pairs(const std::vector<float>& pairs, CameraLook& look) {
    if (pairs.size() < 4 || (pairs.size() % 2) != 0 || pairs.size() > 8192u * 2u) return false;
    int n = static_cast<int>(pairs.size() / 2);
    std::vector<double> x(n), y(n);
    for (int i = 0; i < n; ++i) {
        x[i] = pairs[i * 2];
        y[i] = pairs[i * 2 + 1];
        if (!std::isfinite(x[i]) || !std::isfinite(y[i])) return false;
        if (x[i] < -0.001 || x[i] > 1.001 || y[i] < -0.02 || y[i] > 1.02) return false;
        if (i > 0 && !(x[i] > x[i - 1] + 1e-6)) return false;
    }
    if (std::fabs(x[0]) > 1e-3 || std::fabs(y[0]) > 1e-3 || std::fabs(x[n - 1] - 1.0) > 1e-3 ||
        std::fabs(y[n - 1] - 1.0) > 1e-3) {
        return false;
    }
    bool identity = true;
    for (int i = 0; i < n; ++i) {
        if (std::fabs(y[i] - x[i]) > 1e-4) identity = false;
    }
    if (identity) {
        look.tone.clear();
        look.tone_active = false;
        look.tone_slope = 1.f;
        return true;
    }
    std::vector<double> h(n - 1), alpha(n), l(n), mu(n), z(n), c(n), b(n - 1), d(n - 1);
    for (int i = 0; i < n - 1; ++i) h[i] = x[i + 1] - x[i];
    for (int i = 1; i < n - 1; ++i) {
        alpha[i] = (3.0 / h[i]) * (y[i + 1] - y[i]) - (3.0 / h[i - 1]) * (y[i] - y[i - 1]);
    }
    l[0] = 1;
    mu[0] = 0;
    z[0] = 0;
    for (int i = 1; i < n - 1; ++i) {
        l[i] = 2.0 * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1];
        if (std::fabs(l[i]) < 1e-12) return false;
        mu[i] = h[i] / l[i];
        z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i];
    }
    l[n - 1] = 1;
    z[n - 1] = 0;
    c[n - 1] = 0;
    for (int j = n - 2; j >= 0; --j) {
        c[j] = z[j] - mu[j] * c[j + 1];
        b[j] = (y[j + 1] - y[j]) / h[j] - h[j] * (c[j + 1] + 2.0 * c[j]) / 3.0;
        d[j] = (c[j + 1] - c[j]) / (3.0 * h[j]);
    }
    look.tone.assign(4096, 0.f);
    int seg = 0;
    for (int i = 0; i < 4096; ++i) {
        double xv = static_cast<double>(i) / 4095.0;
        while (seg < n - 2 && xv > x[seg + 1]) ++seg;
        double dx = xv - x[seg];
        double yv = y[seg] + b[seg] * dx + c[seg] * dx * dx + d[seg] * dx * dx * dx;
        if (yv < 0.0) yv = 0.0;
        if (yv > 1.0) yv = 1.0;
        look.tone[i] = static_cast<float>(yv);
    }
    look.tone_slope = (look.tone[4095] - look.tone[4094]) * 4095.f;
    look.tone_active = true;
    return true;
}

inline bool map_sane(const HueMap& map) {
    if (!map.valid()) return false;
    for (size_t i = 0; i < map.data.size(); i += 3) {
        float hue = map.data[i];
        float sat = map.data[i + 1];
        float val = map.data[i + 2];
        if (!std::isfinite(hue) || !std::isfinite(sat) || !std::isfinite(val)) return false;
        if (hue < -180.f || hue > 180.f) return false;
        if (sat < 0.f || sat > 8.f || val < 0.f || val > 8.f) return false;
    }
    return true;
}

inline bool adopt_map(std::vector<float> raw, int hue, int sat, int val, int encoding, HueMap& map) {
    if (hue < 1 || hue > 180 || sat < 2 || sat > 64 || val < 1 || val > 32) return false;
    size_t full = static_cast<size_t>(hue) * static_cast<size_t>(sat) * static_cast<size_t>(val) * 3u;
    size_t skipped = static_cast<size_t>(hue) * static_cast<size_t>(sat - 1) * static_cast<size_t>(val) * 3u;
    HueMap out;
    out.hue = hue;
    out.sat = sat;
    out.val = val;
    out.encoding = encoding == 1 ? 1 : 0;
    if (raw.size() == full) {
        out.data = std::move(raw);
    } else if (raw.size() == skipped) {
        out.data.assign(full, 0.f);
        size_t src = 0;
        for (int v = 0; v < val; ++v) {
            for (int h = 0; h < hue; ++h) {
                size_t base = ((static_cast<size_t>(v) * static_cast<size_t>(hue) + static_cast<size_t>(h)) *
                                   static_cast<size_t>(sat)) *
                              3u;
                out.data[base] = 0.f;
                out.data[base + 1] = 1.f;
                out.data[base + 2] = 1.f;
                for (int s = 1; s < sat; ++s) {
                    size_t dst = base + static_cast<size_t>(s) * 3u;
                    if (src + 3 > raw.size()) return false;
                    out.data[dst] = raw[src];
                    out.data[dst + 1] = raw[src + 1];
                    out.data[dst + 2] = raw[src + 2];
                    src += 3;
                }
            }
        }
    } else {
        return false;
    }
    if (!map_sane(out)) return false;
    map = std::move(out);
    return true;
}

inline void lerp_maps(const HueMap& a, const HueMap& b, float w, HueMap& out) {
    if (!a.valid()) {
        out = b;
        return;
    }
    if (!b.valid() || w >= 0.999f || a.hue != b.hue || a.sat != b.sat || a.val != b.val || a.data.size() != b.data.size()) {
        out = a;
        return;
    }
    if (w <= 0.001f) {
        out = b;
        return;
    }
    out = a;
    float wb = 1.f - w;
    for (size_t i = 0; i < out.data.size(); ++i) out.data[i] = a.data[i] * w + b.data[i] * wb;
}

inline float illuminant_kelvin(int id) {
    switch (id) {
        case 1: return 5500.f;
        case 2: return 3800.f;
        case 3: return 2856.f;
        case 10: return 5500.f;
        case 17: return 2856.f;
        case 18: return 4874.f;
        case 19: return 6774.f;
        case 20: return 5503.f;
        case 21: return 6504.f;
        case 22: return 7504.f;
        case 23: return 5003.f;
        default: return 0.f;
    }
}

inline float illuminant_weight(int illum1, int illum2, float temp) {
    float t1 = illuminant_kelvin(illum1);
    float t2 = illuminant_kelvin(illum2);
    if (!(t1 > 0.f) || !(t2 > 0.f) || !(temp > 0.f) || std::fabs(t1 - t2) < 1.f) return 1.f;
    float lo = std::min(t1, t2);
    float hi = std::max(t1, t2);
    if (temp <= lo || temp >= hi) return std::fabs(temp - t1) <= std::fabs(temp - t2) ? 1.f : 0.f;
    float i1 = 1.f / t1;
    float i2 = 1.f / t2;
    float it = 1.f / temp;
    float den = i2 - i1;
    if (std::fabs(den) < 1e-12f) return 1.f;
    float w = (i2 - it) / den;
    if (w < 0.f) w = 0.f;
    if (w > 1.f) w = 1.f;
    return w;
}

inline bool load_f32(const uint8_t* data, size_t len, bool le, uint32_t count, uint32_t field, std::vector<float>& out) {
    if (count == 0 || count > 2000000u) return false;
    size_t nbytes = static_cast<size_t>(count) * 4u;
    uint8_t inline_bytes[4];
    const uint8_t* p = nullptr;
    if (nbytes <= 4) {
        if (le) {
            inline_bytes[0] = static_cast<uint8_t>(field);
            inline_bytes[1] = static_cast<uint8_t>(field >> 8);
            inline_bytes[2] = static_cast<uint8_t>(field >> 16);
            inline_bytes[3] = static_cast<uint8_t>(field >> 24);
        } else {
            inline_bytes[0] = static_cast<uint8_t>(field >> 24);
            inline_bytes[1] = static_cast<uint8_t>(field >> 16);
            inline_bytes[2] = static_cast<uint8_t>(field >> 8);
            inline_bytes[3] = static_cast<uint8_t>(field);
        }
        p = inline_bytes;
    } else {
        if (static_cast<size_t>(field) > len || nbytes > len - field) return false;
        p = data + field;
    }
    out.resize(count);
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t bits = u32_at(p + static_cast<size_t>(i) * 4u, le);
        float v = 0.f;
        std::memcpy(&v, &bits, sizeof(v));
        if (!std::isfinite(v)) return false;
        out[i] = v;
    }
    return true;
}

inline int read_longs(const uint8_t* data, size_t len, bool le, uint32_t count, uint32_t field, int* dst, int n) {
    if (count < 2 || count > 3) return 0;
    size_t nbytes = static_cast<size_t>(count) * 4u;
    const uint8_t* p = nullptr;
    if (nbytes <= 4) return 0;
    if (static_cast<size_t>(field) > len || nbytes > len - field) return 0;
    p = data + field;
    int got = static_cast<int>(count);
    if (got > n) got = n;
    for (int i = 0; i < got; ++i) dst[i] = static_cast<int>(u32_at(p + static_cast<size_t>(i) * 4u, le));
    if (got == 2 && n >= 3) dst[2] = 1;
    return got == 2 ? 3 : got;
}

// Fills the look from DNG profile tags in IFD 0. Missing tags keep the ACR3 curve and no maps.
inline void prepare_camera_look(const uint8_t* data, size_t len, float temp_k, int space, CameraLook& look) {
    look = CameraLook();
    look.space = space == 1 || space == 2 ? space : 0;
    look.overrange = space == 2;
    set_acr3(look);
    if (!data || len < 8) return;
    bool le = data[0] == 'I' && data[1] == 'I';
    bool be = data[0] == 'M' && data[1] == 'M';
    if (!le && !be) return;
    if (u16_at(data + 2, le) != 42) return;
    uint32_t ifd = u32_at(data + 4, le);
    if (ifd > len - 2) return;
    uint32_t n = u16_at(data + ifd, le);
    if (n > 512) return;
    if (static_cast<size_t>(ifd) + 2 + static_cast<size_t>(n) * 12u + 4u > len) return;

    int hue_div[3] = {0, 0, 1};
    int look_div[3] = {0, 0, 1};
    bool have_hue_div = false;
    bool have_look_div = false;
    int hue_enc = 0;
    int look_enc = 0;
    int illum1 = 0;
    int illum2 = 0;
    std::vector<float> tone;
    std::vector<float> hue1_raw, hue2_raw, look_raw;
    bool have_hue1 = false, have_hue2 = false, have_look = false, have_tone = false;

    for (uint32_t i = 0; i < n; ++i) {
        const uint8_t* e = data + ifd + 2 + i * 12;
        uint32_t tag = u16_at(e, le);
        uint32_t type = u16_at(e + 2, le);
        uint32_t cnt = u32_at(e + 4, le);
        uint32_t field = u32_at(e + 8, le);
        if ((tag == 0xC65A || tag == 0xC65B) && cnt >= 1 && (type == 3 || type == 4)) {
            uint32_t v = 0;
            if (type == 3) {
                v = (cnt * 2u <= 4u) ? (le ? (field & 0xffffu) : (field >> 16)) : 0;
                if (cnt * 2u > 4u && field + 2u <= len) v = u16_at(data + field, le);
            } else if (cnt == 1) {
                v = field;
            }
            if (tag == 0xC65A) illum1 = static_cast<int>(v);
            else illum2 = static_cast<int>(v);
        } else if (tag == 0xC6F9 && type == 4) {
            have_hue_div = read_longs(data, len, le, cnt, field, hue_div, 3) == 3;
        } else if (tag == 0xC725 && type == 4) {
            have_look_div = read_longs(data, len, le, cnt, field, look_div, 3) == 3;
        } else if (tag == 0xC7A3 && cnt >= 1 && type == 4) {
            hue_enc = static_cast<int>(field);
        } else if (tag == 0xC7A4 && cnt >= 1 && type == 4) {
            look_enc = static_cast<int>(field);
        } else if (tag == 0xC6FC && type == 11) {
            have_tone = load_f32(data, len, le, cnt, field, tone);
        } else if (tag == 0xC6FA && type == 11) {
            have_hue1 = load_f32(data, len, le, cnt, field, hue1_raw);
        } else if (tag == 0xC6FB && type == 11) {
            have_hue2 = load_f32(data, len, le, cnt, field, hue2_raw);
        } else if (tag == 0xC726 && type == 11) {
            have_look = load_f32(data, len, le, cnt, field, look_raw);
        }
    }

    if (have_tone) {
        CameraLook curved;
        if (build_tone_from_pairs(tone, curved)) {
            look.tone = std::move(curved.tone);
            look.tone_slope = curved.tone_slope;
            look.tone_active = curved.tone_active;
        }
    }
    HueMap map1, map2, looked;
    bool ok1 = have_hue_div && have_hue1 && adopt_map(std::move(hue1_raw), hue_div[0], hue_div[1], hue_div[2], hue_enc, map1);
    bool ok2 = have_hue_div && have_hue2 && adopt_map(std::move(hue2_raw), hue_div[0], hue_div[1], hue_div[2], hue_enc, map2);
    if (ok1 && ok2) {
        lerp_maps(map1, map2, illuminant_weight(illum1, illum2, temp_k), look.hue);
        look.has_hue = look.hue.valid();
    } else if (ok1) {
        look.hue = std::move(map1);
        look.has_hue = true;
    } else if (ok2) {
        look.hue = std::move(map2);
        look.has_hue = true;
    }
    if (have_look_div && have_look && adopt_map(std::move(look_raw), look_div[0], look_div[1], look_div[2], look_enc, looked)) {
        look.look = std::move(looked);
        look.has_look = true;
    }
}

}  // namespace rawlook

#endif
