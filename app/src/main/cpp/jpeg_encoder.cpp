#include "jpeg_encoder.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

namespace {

// ---------------------------------------------------------------------------
// ITU T.81 Annex K tables
// ---------------------------------------------------------------------------

const uint8_t kZigzag[64] = {
    0,  1,  8,  16, 9,  2,  3,  10, 17, 24, 32, 25, 18, 11, 4,  5,
    12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6,  7,  14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
};

const uint16_t kQuantLuma[64] = {
    16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57,
    69, 56, 14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55,
    64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100,
    103, 99,
};

const uint16_t kQuantChroma[64] = {
    17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99,
    99, 99, 47, 66, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
};

const uint8_t kDcLumaBits[17] = {0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
const uint8_t kDcLumaVals[12] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
const uint8_t kDcChromaBits[17] = {0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
const uint8_t kDcChromaVals[12] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

const uint8_t kAcLumaBits[17] = {0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
const uint8_t kAcLumaVals[162] = {
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61,
    0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52,
    0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25,
    0x26, 0x27, 0x28, 0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45,
    0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64,
    0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83,
    0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99,
    0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6,
    0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3,
    0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8,
    0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa,
};

const uint8_t kAcChromaBits[17] = {0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
const uint8_t kAcChromaVals[162] = {
    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61,
    0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33,
    0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18,
    0x19, 0x1a, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44,
    0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63,
    0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a,
    0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97,
    0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4,
    0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca,
    0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7,
    0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa,
};

// ---------------------------------------------------------------------------
// Growable output buffer + JPEG bit writer
// ---------------------------------------------------------------------------

struct Sink {
    uint8_t* data = nullptr;
    size_t size = 0;
    size_t cap = 0;
    bool ok = true;
};

bool sinkEnsure(Sink& s, size_t extra) {
    if (s.size + extra <= s.cap) return true;
    size_t want = s.cap ? s.cap * 2 : 65536;
    while (want < s.size + extra) want *= 2;
    uint8_t* p = static_cast<uint8_t*>(realloc(s.data, want));
    if (!p) {
        s.ok = false;
        return false;
    }
    s.data = p;
    s.cap = want;
    return true;
}

void sinkByte(Sink& s, uint8_t b) {
    if (sinkEnsure(s, 1)) {
        s.data[s.size++] = b;
    }
}

void sinkBytes(Sink& s, const uint8_t* p, size_t n) {
    if (sinkEnsure(s, n)) {
        memcpy(s.data + s.size, p, n);
        s.size += n;
    }
}

void sinkU16(Sink& s, uint16_t v) {
    sinkByte(s, static_cast<uint8_t>(v >> 8));
    sinkByte(s, static_cast<uint8_t>(v));
}

// EXIF payloads are TIFF structures, i.e. little-endian when prefixed with "II".
void sinkU16Le(Sink& s, uint16_t v) {
    sinkByte(s, static_cast<uint8_t>(v));
    sinkByte(s, static_cast<uint8_t>(v >> 8));
}

void sinkU32Le(Sink& s, uint32_t v) {
    sinkU16Le(s, static_cast<uint16_t>(v));
    sinkU16Le(s, static_cast<uint16_t>(v >> 16));
}

struct BitWriter {
    Sink* sink;
    uint32_t bits = 0;
    int count = 0;

    void put(uint32_t code, int n) {
        if (n <= 0) return;
        bits = (bits << n) | (code & ((1u << n) - 1u));
        count += n;
        while (count >= 8) {
            count -= 8;
            uint8_t b = static_cast<uint8_t>((bits >> count) & 0xFF);
            sinkByte(*sink, b);
            if (b == 0xFF) sinkByte(*sink, 0x00);  // byte stuffing
        }
    }

    // Pad the final partial byte with 1 bits, as the spec requires.
    void flush() {
        if (count > 0) {
            put(static_cast<uint32_t>((1u << (8 - count)) - 1u), 8 - count);
        }
        bits = 0;
        count = 0;
    }
};

// ---------------------------------------------------------------------------
// Huffman tables
// ---------------------------------------------------------------------------

struct HuffTable {
    uint16_t code[256];
    uint8_t size[256];
};

void buildHuffTable(HuffTable& t, const uint8_t bits[17], const uint8_t* vals, int numVals) {
    memset(&t, 0, sizeof(t));
    uint32_t code = 0;
    int k = 0;
    for (int len = 1; len <= 16; ++len) {
        for (int i = 0; i < bits[len]; ++i) {
            if (k >= numVals) break;
            t.code[vals[k]] = static_cast<uint16_t>(code);
            t.size[vals[k]] = static_cast<uint8_t>(len);
            ++code;
            ++k;
        }
        code <<= 1;
    }
}

// ---------------------------------------------------------------------------
// Float AAN forward DCT (same math as libjpeg's jfdctflt) + quantization
// ---------------------------------------------------------------------------

struct Dct {
    HuffTable dcLuma, acLuma, dcChroma, acChroma;
    uint16_t qLuma[64];    // zigzag-ordered
    uint16_t qChroma[64];  // zigzag-ordered
};

void forwardDct8x8(const float in[64], float out[64]) {
    float tmp[64];
    constexpr float kC1 = 0.707106781f;   // cos(pi/4)
    constexpr float kC2 = 0.382683433f;   // cos(3pi/8)
    constexpr float kC3 = 0.541196100f;   // (cos(2pi/8)-cos(6pi/8))/... (jfdctflt constant)
    constexpr float kC4 = 1.306562965f;   // (cos(2pi/8)+cos(6pi/8))

    // rows
    for (int y = 0; y < 8; ++y) {
        const float* p = in + y * 8;
        float t0 = p[0] + p[7], t7 = p[0] - p[7];
        float t1 = p[1] + p[6], t6 = p[1] - p[6];
        float t2 = p[2] + p[5], t5 = p[2] - p[5];
        float t3 = p[3] + p[4], t4 = p[3] - p[4];

        float t10 = t0 + t3, t13 = t0 - t3;
        float t11 = t1 + t2, t12 = t1 - t2;

        float o0 = t10 + t11;
        float o4 = t10 - t11;
        float z1 = (t12 + t13) * kC1;
        float o2 = t13 + z1;
        float o6 = t13 - z1;

        float u10 = t4 + t5, u11 = t5 + t6, u12 = t6 + t7;
        float z5 = (u10 - u12) * kC2;
        float z2 = kC3 * u10 + z5;
        float z4 = kC4 * u12 + z5;
        float z3 = kC1 * u11;
        float z11 = t7 + z3, z13 = t7 - z3;

        float o5 = z13 + z2, o3 = z13 - z2;
        float o1 = z11 + z4, o7 = z11 - z4;

        float* q = tmp + y * 8;
        q[0] = o0; q[1] = o1; q[2] = o2; q[3] = o3;
        q[4] = o4; q[5] = o5; q[6] = o6; q[7] = o7;
    }

    // columns
    for (int x = 0; x < 8; ++x) {
        float t0 = tmp[x] + tmp[56 + x], t7 = tmp[x] - tmp[56 + x];
        float t1 = tmp[8 + x] + tmp[48 + x], t6 = tmp[8 + x] - tmp[48 + x];
        float t2 = tmp[16 + x] + tmp[40 + x], t5 = tmp[16 + x] - tmp[40 + x];
        float t3 = tmp[24 + x] + tmp[32 + x], t4 = tmp[24 + x] - tmp[32 + x];

        float t10 = t0 + t3, t13 = t0 - t3;
        float t11 = t1 + t2, t12 = t1 - t2;

        float o0 = t10 + t11;
        float o4 = t10 - t11;
        float z1 = (t12 + t13) * kC1;
        float o2 = t13 + z1;
        float o6 = t13 - z1;

        float u10 = t4 + t5, u11 = t5 + t6, u12 = t6 + t7;
        float z5 = (u10 - u12) * kC2;
        float z2 = kC3 * u10 + z5;
        float z4 = kC4 * u12 + z5;
        float z3 = kC1 * u11;
        float z11 = t7 + z3, z13 = t7 - z3;

        float o5 = z13 + z2, o3 = z13 - z2;
        float o1 = z11 + z4, o7 = z11 - z4;

        out[x] = o0; out[8 + x] = o1; out[16 + x] = o2; out[24 + x] = o3;
        out[32 + x] = o4; out[40 + x] = o5; out[48 + x] = o6; out[56 + x] = o7;
    }
}

// `q` is in natural (row-major) order so it lines up with the DCT output.
// The float DCT below (like libjpeg's jfdctflt) produces coefficients scaled
// by 8, hence the /8 here. Coefficients are clamped to the categories the
// baseline Huffman tables define (DC |v| <= 2047, AC |v| <= 1023); real image
// content never gets close, but a clamp keeps malformed input encodable.
void quantize(const float in[64], const uint16_t q[64], int16_t out[64]) {
    for (int i = 0; i < 64; ++i) {
        float v = in[i] / (8.0f * static_cast<float>(q[i]));
        int r = static_cast<int>(v < 0 ? v - 0.5f : v + 0.5f);
        int limit = (i == 0) ? 2047 : 1023;
        if (r > limit) r = limit;
        if (r < -limit) r = -limit;
        out[i] = static_cast<int16_t>(r);
    }
}

void toZigzag(const int16_t in[64], int16_t out[64]) {
    for (int k = 0; k < 64; ++k) out[k] = in[kZigzag[k]];
}

int magnitudeCategory(int v) {
    int a = v < 0 ? -v : v;
    int n = 0;
    while (a) {
        ++n;
        a >>= 1;
    }
    return n;
}

void emitBlock(BitWriter& bw, const int16_t block[64], const HuffTable& dc, const HuffTable& ac,
               int& prevDc) {
    // Baseline DC categories only go up to 11; clamp the differential (tracking
    // the decoder's reconstructed DC so encoder and decoder stay in sync).
    int diff = block[0] - prevDc;
    if (diff > 2047) diff = 2047;
    if (diff < -2047) diff = -2047;
    prevDc += diff;
    int cat = magnitudeCategory(diff);
    bw.put(dc.code[cat], dc.size[cat]);
    if (cat) {
        int v = diff < 0 ? diff - 1 : diff;  // one's complement for negatives
        bw.put(static_cast<uint32_t>(v) & ((1u << cat) - 1u), cat);
    }

    int run = 0;
    for (int k = 1; k < 64; ++k) {
        int v = block[k];
        if (v == 0) {
            ++run;
            continue;
        }
        while (run > 15) {
            bw.put(ac.code[0xF0], ac.size[0xF0]);  // ZRL
            run -= 16;
        }
        int cat2 = magnitudeCategory(v);
        int sym = (run << 4) | cat2;
        bw.put(ac.code[sym], ac.size[sym]);
        int val = v < 0 ? v - 1 : v;
        bw.put(static_cast<uint32_t>(val) & ((1u << cat2) - 1u), cat2);
        run = 0;
    }
    if (run > 0) {
        bw.put(ac.code[0x00], ac.size[0x00]);  // EOB
    }
}

// Load an 8x8 luma block, replicating edge pixels for partial blocks.
void loadLuma(const uint8_t* plane, int w, int h, int x0, int y0, float out[64]) {
    for (int y = 0; y < 8; ++y) {
        int sy = y0 + y;
        if (sy >= h) sy = h - 1;
        const uint8_t* row = plane + static_cast<size_t>(sy) * w;
        for (int x = 0; x < 8; ++x) {
            int sx = x0 + x;
            if (sx >= w) sx = w - 1;
            out[y * 8 + x] = static_cast<float>(row[sx]) - 128.0f;
        }
    }
}

// Load an 8x8 chroma block from the interleaved VU plane (one byte per chroma
// sample: V at even offsets, U at odd offsets).
void loadChroma(const uint8_t* uv, int w, int h, int cx0, int cy0, int component,
                float out[64]) {
    int cw = (w + 1) / 2;
    int ch = (h + 1) / 2;
    for (int y = 0; y < 8; ++y) {
        int sy = cy0 + y;
        if (sy >= ch) sy = ch - 1;
        const uint8_t* row = uv + static_cast<size_t>(sy) * w;
        for (int x = 0; x < 8; ++x) {
            int sx = cx0 + x;
            if (sx >= cw) sx = cw - 1;
            int index = sx * 2 + component;
            // Odd widths: the trailing U byte of the interleaved pair does not
            // exist; clamp instead of reading past the plane.
            if (index >= w) index = w - 1;
            out[y * 8 + x] = static_cast<float>(row[index]) - 128.0f;
        }
    }
}

// ---------------------------------------------------------------------------
// Markers
// ---------------------------------------------------------------------------

void writeExifApp1(Sink& s, int orientation, int width, int height) {
    constexpr int kEntries = 7;
    const int ifdOffset = 8;
    const int dataOffset = ifdOffset + 2 + kEntries * 12 + 4;  // TIFF header relative
    const int tiffSize = dataOffset + 16;                      // two RATIONALs
    const int payload = 6 + tiffSize;                          // "Exif\0\0" + TIFF

    sinkByte(s, 0xFF);
    sinkByte(s, 0xE1);
    sinkU16(s, static_cast<uint16_t>(payload + 2));
    sinkBytes(s, reinterpret_cast<const uint8_t*>("Exif\0\0"), 6);

    // TIFF header (little endian)
    sinkByte(s, 'I');
    sinkByte(s, 'I');
    sinkU16Le(s, 0x002A);
    sinkU32Le(s, static_cast<uint32_t>(ifdOffset));

    // IFD0: entries must be sorted by tag
    sinkU16Le(s, kEntries);
    auto entry = [&](uint16_t tag, uint16_t type, uint32_t count, uint32_t value) {
        sinkU16Le(s, tag);
        sinkU16Le(s, type);
        sinkU32Le(s, count);
        sinkU32Le(s, value);
    };
    entry(0x0112, 3, 1, static_cast<uint32_t>(orientation));  // Orientation
    entry(0x011A, 5, 1, static_cast<uint32_t>(dataOffset));   // XResolution
    entry(0x011B, 5, 1, static_cast<uint32_t>(dataOffset + 8));  // YResolution
    entry(0x0128, 3, 1, 2);                                      // ResolutionUnit = inch
    entry(0x0213, 3, 1, 1);                                      // YCbCrPositioning = centered
    entry(0xA002, 4, 1, static_cast<uint32_t>(width));           // PixelXDimension
    entry(0xA003, 4, 1, static_cast<uint32_t>(height));          // PixelYDimension
    sinkU32Le(s, 0);  // no next IFD

    sinkU32Le(s, 72);  // XResolution numerator
    sinkU32Le(s, 1);   // XResolution denominator
    sinkU32Le(s, 72);  // YResolution numerator
    sinkU32Le(s, 1);   // YResolution denominator
}

void writeDqt(Sink& s, const Dct& d) {
    sinkByte(s, 0xFF);
    sinkByte(s, 0xDB);
    sinkU16(s, static_cast<uint16_t>(2 + 2 * (1 + 64)));
    for (int table = 0; table < 2; ++table) {
        sinkByte(s, static_cast<uint8_t>(table));  // Pq = 0 (8 bit), Tq = table
        const uint16_t* q = table == 0 ? d.qLuma : d.qChroma;
        for (int i = 0; i < 64; ++i) sinkByte(s, static_cast<uint8_t>(q[i]));
    }
}

void writeSof0(Sink& s, int width, int height) {
    sinkByte(s, 0xFF);
    sinkByte(s, 0xC0);
    sinkU16(s, 8 + 3 * 3);
    sinkByte(s, 8);  // precision
    sinkU16(s, static_cast<uint16_t>(height));
    sinkU16(s, static_cast<uint16_t>(width));
    sinkByte(s, 3);
    sinkByte(s, 1);
    sinkByte(s, 0x22);  // h = 2, v = 2 (4:2:0)
    sinkByte(s, 0);     // quant table 0
    sinkByte(s, 2);
    sinkByte(s, 0x11);
    sinkByte(s, 1);
    sinkByte(s, 3);
    sinkByte(s, 0x11);
    sinkByte(s, 1);
}

void writeDht(Sink& s) {
    struct TableDesc {
        uint8_t id;
        const uint8_t* bits;
        const uint8_t* vals;
        int nvals;
    };
    const TableDesc tables[4] = {
        {0x00, kDcLumaBits, kDcLumaVals, 12},
        {0x10, kAcLumaBits, kAcLumaVals, 162},
        {0x01, kDcChromaBits, kDcChromaVals, 12},
        {0x11, kAcChromaBits, kAcChromaVals, 162},
    };
    for (const TableDesc& t : tables) {
        sinkByte(s, 0xFF);
        sinkByte(s, 0xC4);
        sinkU16(s, static_cast<uint16_t>(2 + 1 + 16 + t.nvals));
        sinkByte(s, t.id);
        for (int i = 1; i <= 16; ++i) sinkByte(s, t.bits[i]);
        sinkBytes(s, t.vals, static_cast<size_t>(t.nvals));
    }
}

void writeSos(Sink& s) {
    sinkByte(s, 0xFF);
    sinkByte(s, 0xDA);
    sinkU16(s, 6 + 2 * 3);
    sinkByte(s, 3);
    sinkByte(s, 1);
    sinkByte(s, 0x00);
    sinkByte(s, 2);
    sinkByte(s, 0x11);
    sinkByte(s, 3);
    sinkByte(s, 0x11);
    sinkByte(s, 0);
    sinkByte(s, 63);
    sinkByte(s, 0);
}

void scaleQuant(const uint16_t base[64], uint16_t out[64], int quality) {
    if (quality < 1) quality = 1;
    if (quality > 100) quality = 100;
    int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
    for (int i = 0; i < 64; ++i) {
        int q = (static_cast<int>(base[i]) * scale + 50) / 100;
        if (q < 1) q = 1;
        if (q > 255) q = 255;
        out[i] = static_cast<uint16_t>(q);
    }
}

}  // namespace

uint8_t* jpeg_encode_nv21(const uint8_t* nv21, int width, int height, int orientation,
                          int quality, size_t* outSize) {
    if (!nv21 || width <= 0 || height <= 0 || !outSize) return nullptr;
    if (width > 65535 || height > 65535) return nullptr;
    if (orientation != 3 && orientation != 6 && orientation != 8) orientation = 1;

    Dct dct;
    buildHuffTable(dct.dcLuma, kDcLumaBits, kDcLumaVals, 12);
    buildHuffTable(dct.acLuma, kAcLumaBits, kAcLumaVals, 162);
    buildHuffTable(dct.dcChroma, kDcChromaBits, kDcChromaVals, 12);
    buildHuffTable(dct.acChroma, kAcChromaBits, kAcChromaVals, 162);
    scaleQuant(kQuantLuma, dct.qLuma, quality);
    scaleQuant(kQuantChroma, dct.qChroma, quality);

    Sink sink;
    sinkByte(sink, 0xFF);
    sinkByte(sink, 0xD8);  // SOI

    // JFIF APP0
    sinkByte(sink, 0xFF);
    sinkByte(sink, 0xE0);
    sinkU16(sink, 16);
    sinkBytes(sink, reinterpret_cast<const uint8_t*>("JFIF\0"), 5);
    sinkByte(sink, 1);
    sinkByte(sink, 1);
    sinkByte(sink, 0);
    sinkU16(sink, 1);
    sinkU16(sink, 1);
    sinkByte(sink, 0);
    sinkByte(sink, 0);

    if (orientation != 1) {
        writeExifApp1(sink, orientation, width, height);
    }
    writeDqt(sink, dct);
    writeSof0(sink, width, height);
    writeDht(sink);
    writeSos(sink);

    const uint8_t* yPlane = nv21;
    const uint8_t* uvPlane = nv21 + static_cast<size_t>(width) * height;

    BitWriter bw{&sink};
    int prevDcY = 0, prevDcCb = 0, prevDcCr = 0;
    float samples[64];
    float freq[64];
    int16_t coef[64];
    int16_t zz[64];

    const int mcuCols = (width + 15) / 16;
    const int mcuRows = (height + 15) / 16;
    for (int my = 0; my < mcuRows; ++my) {
        for (int mx = 0; mx < mcuCols; ++mx) {
            const int bx = mx * 16;
            const int by = my * 16;

            for (int sub = 0; sub < 4; ++sub) {
                const int dx = (sub & 1) * 8;
                const int dy = (sub >> 1) * 8;
                loadLuma(yPlane, width, height, bx + dx, by + dy, samples);
                forwardDct8x8(samples, freq);
                quantize(freq, dct.qLuma, coef);
                toZigzag(coef, zz);
                emitBlock(bw, zz, dct.dcLuma, dct.acLuma, prevDcY);
            }

            const int cx = mx * 8;
            const int cy = my * 8;
            loadChroma(uvPlane, width, height, cx, cy, 1, samples);  // U
            forwardDct8x8(samples, freq);
            quantize(freq, dct.qChroma, coef);
            toZigzag(coef, zz);
            emitBlock(bw, zz, dct.dcChroma, dct.acChroma, prevDcCb);

            loadChroma(uvPlane, width, height, cx, cy, 0, samples);  // V
            forwardDct8x8(samples, freq);
            quantize(freq, dct.qChroma, coef);
            toZigzag(coef, zz);
            emitBlock(bw, zz, dct.dcChroma, dct.acChroma, prevDcCr);
        }
    }

    bw.flush();
    sinkByte(sink, 0xFF);
    sinkByte(sink, 0xD9);  // EOI

    if (!sink.ok) {
        free(sink.data);
        return nullptr;
    }
    *outSize = sink.size;
    return sink.data;
}
