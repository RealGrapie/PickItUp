#pragma once
#include <stdint.h>

// Max module widths produced for a code of up to CODE128_MAX_CHARS characters.
#define CODE128_MAX_CHARS 32
#define CODE128_MAX_WIDTHS ((CODE128_MAX_CHARS * 2 + 3) * 6 + 7)

// Encodes printable ASCII (32..126) as Code 128, switching between sets B and C
// so digit runs take half the width. Writes alternating bar/space widths
// (in modules, starting with a bar) into out. Returns the number of widths,
// or 0 if the text is empty, too long or contains unsupported characters.
int code128_encode(const char *text, uint8_t *out, int *total_modules);
