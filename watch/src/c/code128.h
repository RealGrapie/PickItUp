#pragma once
#include <stdint.h>

// Max module widths produced for a code of up to CODE128_MAX_CHARS characters.
#define CODE128_MAX_CHARS 32
#define CODE128_MAX_WIDTHS ((CODE128_MAX_CHARS * 2 + 3) * 6 + 7)

// Densest encoding: as many digit pairs in set C as useful.
#define CODE128_DENSE 255

// Encodes printable ASCII (32..126) as Code 128, switching between sets B and C so digit runs
// take half the width. At most [c_pairs] digit pairs go into set C (0 = all set B, one symbol per
// character): fewer pairs give a longer code with the same module, to fill a wide screen.
// Writes alternating bar/space widths (in modules, starting with a bar) into out. Returns the
// number of widths, or 0 if the text is empty, too long or contains unsupported characters.
int code128_encode(const char *text, int c_pairs, uint8_t *out, int *total_modules);
