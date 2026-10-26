#include "code128.h"

#include <stdbool.h>
#include <string.h>

// Symbol values 0..105: six bar/space widths (1..4), packed 2 bits each as (w - 1),
// first width in the high bits. Generated from python-barcode's Code 128 table.
static const uint16_t PATTERNS[106] = {
  0x455, 0x545, 0x554, 0x116, 0x125, 0x215, 0x152, 0x161,
  0x251, 0x512, 0x521, 0x611, 0x059, 0x149, 0x158, 0x095,
  0x185, 0x194, 0x590, 0x509, 0x518, 0x491, 0x581, 0x848,
  0x815, 0x905, 0x914, 0x851, 0x941, 0x950, 0x446, 0x464,
  0x644, 0x026, 0x206, 0x224, 0x062, 0x242, 0x260, 0x422,
  0x602, 0x620, 0x04a, 0x068, 0x248, 0x086, 0x0a4, 0x284,
  0x884, 0x428, 0x608, 0x482, 0x4a0, 0x488, 0x806, 0x824,
  0xa04, 0x842, 0x860, 0xa40, 0x8c0, 0x530, 0xe00, 0x017,
  0x035, 0x107, 0x134, 0x305, 0x314, 0x053, 0x071, 0x143,
  0x170, 0x341, 0x350, 0x710, 0x503, 0xc80, 0x701, 0x2c0,
  0x01d, 0x10d, 0x11c, 0x0d1, 0x1c1, 0x1d0, 0xc11, 0xd01,
  0xd10, 0x44c, 0x4c4, 0xc44, 0x00e, 0x02c, 0x20c, 0x0c2,
  0x0e0, 0xc02, 0xc20, 0x08c, 0x0c8, 0x80c, 0xc08, 0x431,
  0x413, 0x419,
};
static const uint8_t STOP[7] = {2, 3, 3, 1, 1, 1, 2};

enum { CODE_C = 99, CODE_B = 100, START_B = 104, START_C = 105 };

static bool is_digit(char c) { return c >= '0' && c <= '9'; }

static int digit_run(const char *s) {
  int n = 0;
  while (is_digit(s[n])) n++;
  return n;
}

int code128_encode(const char *text, int c_pairs, uint8_t *out, int *total_modules) {
  int len = strlen(text);
  if (len == 0 || len > CODE128_MAX_CHARS) return 0;
  for (int i = 0; i < len; i++) {
    if (text[i] < 32 || text[i] > 126) return 0;
  }

  uint8_t values[CODE128_MAX_CHARS * 2 + 3];
  int nv = 0;
  // Set C is worth it for runs of 4+ digits (or a code made only of 2+ digits).
  // Entering set C costs a symbol, so it needs at least 2 pairs of budget to save anything.
  bool set_c = c_pairs >= 2 && (digit_run(text) >= 4 || (digit_run(text) == len && len >= 2 && len % 2 == 0));
  values[nv++] = set_c ? START_C : START_B;

  for (int i = 0; i < len;) {
    if (set_c) {
      if (digit_run(text + i) >= 2 && c_pairs > 0) {
        values[nv++] = (text[i] - '0') * 10 + (text[i + 1] - '0');
        i += 2;
        c_pairs--;
        continue;
      }
      values[nv++] = CODE_B;
      set_c = false;
    }
    int run = digit_run(text + i);
    // Switch to C only for an even-length tail of the run, so no digit is left over.
    if (c_pairs >= 2 && run >= 4 && run % 2 == 0) {
      values[nv++] = CODE_C;
      set_c = true;
      continue;
    }
    values[nv++] = text[i] - 32;
    i++;
  }

  int checksum = values[0];
  for (int i = 1; i < nv; i++) checksum += i * values[i];
  values[nv++] = checksum % 103;

  int nw = 0, modules = 0;
  for (int i = 0; i < nv; i++) {
    uint16_t p = PATTERNS[values[i]];
    for (int k = 0; k < 6; k++) {
      out[nw] = ((p >> (2 * (5 - k))) & 3) + 1;
      modules += out[nw++];
    }
  }
  for (int k = 0; k < 7; k++) {
    out[nw++] = STOP[k];
    modules += STOP[k];
  }
  if (total_modules) *total_modules = modules;
  return nw;
}
