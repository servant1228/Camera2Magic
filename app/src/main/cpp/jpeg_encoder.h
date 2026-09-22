// Self-contained baseline JPEG encoder (4:2:0) for NV21 buffers.
//
// The original closed-source libcamera3 linked the full libjpeg-turbo (which is
// most of its 1.1 MB). Camera1's takePicture replacement only ever needs a
// one-off NV21 -> baseline JPEG encode, so we ship a small in-tree encoder
// instead: no third-party binary, no platform-private libs, no libc++ runtime.

#pragma once

#include <stddef.h>
#include <stdint.h>

// Encode `nv21` (Y plane width*height, then interleaved V/U at half resolution)
// as a baseline JPEG. `orientation` is the EXIF Orientation value to embed
// (1 = normal, 3 = 180, 6 = 90 CW, 8 = 270 CW; anything else is treated as 1).
// `quality` is 1..100 (IJG scaling of the Annex K quantization tables).
//
// Returns a malloc'ed buffer (caller frees) or null on failure; *outSize
// receives the byte length.
uint8_t* jpeg_encode_nv21(const uint8_t* nv21, int width, int height, int orientation,
                          int quality, size_t* outSize);
