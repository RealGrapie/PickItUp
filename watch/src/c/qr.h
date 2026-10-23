#pragma once
#include <stdbool.h>
#include <stdint.h>

// Byte-mode QR. Level M: versions 1-2, up to 26 bytes (sturdier, for the pickup code test).
// Level L: versions 1-5, up to 106 bytes (for links scanned up close by a phone camera).
#define QR_MAX_SIZE 37

typedef enum { QR_EC_M, QR_EC_L } QrEcLevel;

typedef struct {
  int size;  // modules per side (21 + 4 per version above 1), 0 if encoding failed
  uint8_t modules[QR_MAX_SIZE][QR_MAX_SIZE];  // [row][col], 1 = dark
} QrCode;

// Returns false if the text is empty or too long for the level.
bool qr_encode(const char *text, QrEcLevel level, QrCode *qr);
