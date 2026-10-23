#include "qr.h"

#include <string.h>

// Minimal QR encoder, byte mode, mask chosen by the standard penalty score. Only the
// version/level combinations with a single Reed-Solomon block are supported (no block
// interleaving): level M up to version 2, level L up to version 5. Versions 2-5 have exactly
// one alignment pattern and none needs version info, which keeps the layout simple.

#define MAX_CODEWORDS 134
#define MAX_VERSION_M 2
#define MAX_VERSION_L 5

typedef struct {
  int data;  // data codewords
  int ec;    // error correction codewords
} VersionInfo;

static const VersionInfo VERSIONS_M[MAX_VERSION_M + 1] = {{0, 0}, {16, 10}, {28, 16}};
static const VersionInfo VERSIONS_L[MAX_VERSION_L + 1] = {
  {0, 0}, {19, 7}, {34, 10}, {55, 15}, {80, 20}, {108, 26},
};

static uint8_t gf_mul(uint8_t x, uint8_t y) {
  int z = 0;
  for (int i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >> 7) * 0x11D);
    z ^= ((y >> i) & 1) * x;
  }
  return z;
}

static void reed_solomon(const uint8_t *data, int len, int degree, uint8_t *out) {
  uint8_t divisor[32];
  memset(divisor, 0, degree);
  divisor[degree - 1] = 1;
  uint8_t root = 1;
  for (int i = 0; i < degree; i++) {
    for (int j = 0; j < degree; j++) {
      divisor[j] = gf_mul(divisor[j], root);
      if (j + 1 < degree) divisor[j] ^= divisor[j + 1];
    }
    root = gf_mul(root, 2);
  }
  memset(out, 0, degree);
  for (int i = 0; i < len; i++) {
    uint8_t factor = data[i] ^ out[0];
    memmove(out, out + 1, degree - 1);
    out[degree - 1] = 0;
    for (int j = 0; j < degree; j++) out[j] ^= gf_mul(divisor[j], factor);
  }
}

typedef struct {
  int size;
  uint8_t *m;         // modules, row-major
  uint8_t *function;  // 1 = function pattern (not data)
  int ecl_bits;       // format-info bits of the EC level: L = 01, M = 00
} Grid;

static void set_fn(Grid *g, int x, int y, bool dark) {
  if (x < 0 || y < 0 || x >= g->size || y >= g->size) return;
  g->m[y * g->size + x] = dark;
  g->function[y * g->size + x] = 1;
}

static void draw_format(Grid *g, int mask) {
  int data = (g->ecl_bits << 3) | mask;
  int rem = data;
  for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
  int bits = ((data << 10) | rem) ^ 0x5412;
  int s = g->size;
#define BIT(i) ((bits >> (i)) & 1)
  for (int i = 0; i <= 5; i++) set_fn(g, 8, i, BIT(i));
  set_fn(g, 8, 7, BIT(6));
  set_fn(g, 8, 8, BIT(7));
  set_fn(g, 7, 8, BIT(8));
  for (int i = 9; i < 15; i++) set_fn(g, 14 - i, 8, BIT(i));
  for (int i = 0; i < 8; i++) set_fn(g, s - 1 - i, 8, BIT(i));
  for (int i = 8; i < 15; i++) set_fn(g, 8, s - 15 + i, BIT(i));
#undef BIT
  set_fn(g, 8, s - 8, true);  // dark module
}

static void draw_function_patterns(Grid *g, int version) {
  int s = g->size;
  for (int i = 0; i < s; i++) {
    set_fn(g, 6, i, i % 2 == 0);
    set_fn(g, i, 6, i % 2 == 0);
  }
  const int centers[3][2] = {{3, 3}, {s - 4, 3}, {3, s - 4}};
  for (int c = 0; c < 3; c++) {
    for (int dy = -4; dy <= 4; dy++) {
      for (int dx = -4; dx <= 4; dx++) {
        int dist = dx < 0 ? -dx : dx;
        int ady = dy < 0 ? -dy : dy;
        if (ady > dist) dist = ady;
        set_fn(g, centers[c][0] + dx, centers[c][1] + dy, dist != 2 && dist != 4);
      }
    }
  }
  if (version >= 2) {
    int a = s - 7;  // the only alignment pattern for versions 2-6
    for (int dy = -2; dy <= 2; dy++) {
      for (int dx = -2; dx <= 2; dx++) {
        int dist = dx < 0 ? -dx : dx;
        int ady = dy < 0 ? -dy : dy;
        if (ady > dist) dist = ady;
        set_fn(g, a + dx, a + dy, dist != 1);
      }
    }
  }
  draw_format(g, 0);  // reserves the format areas; real bits drawn after masking
}

static void draw_codewords(Grid *g, const uint8_t *data, int len) {
  int s = g->size;
  int i = 0;
  for (int right = s - 1; right >= 1; right -= 2) {
    if (right == 6) right = 5;
    for (int vert = 0; vert < s; vert++) {
      for (int j = 0; j < 2; j++) {
        int x = right - j;
        bool upward = ((right + 1) & 2) == 0;
        int y = upward ? s - 1 - vert : vert;
        if (!g->function[y * s + x] && i < len * 8) {
          g->m[y * s + x] = (data[i >> 3] >> (7 - (i & 7))) & 1;
          i++;
        }
      }
    }
  }
}

static bool mask_bit(int mask, int x, int y) {
  switch (mask) {
    case 0: return (x + y) % 2 == 0;
    case 1: return y % 2 == 0;
    case 2: return x % 3 == 0;
    case 3: return (x + y) % 3 == 0;
    case 4: return (x / 3 + y / 2) % 2 == 0;
    case 5: return x * y % 2 + x * y % 3 == 0;
    case 6: return (x * y % 2 + x * y % 3) % 2 == 0;
    default: return ((x + y) % 2 + x * y % 3) % 2 == 0;
  }
}

static void apply_mask(Grid *g, int mask) {
  for (int y = 0; y < g->size; y++) {
    for (int x = 0; x < g->size; x++) {
      if (!g->function[y * g->size + x] && mask_bit(mask, x, y)) g->m[y * g->size + x] ^= 1;
    }
  }
}

static int line_penalty(const uint8_t *line, int step, int s) {
  int p = 0, run = 1;
  for (int i = 1; i <= s; i++) {
    if (i < s && line[i * step] == line[(i - 1) * step]) {
      run++;
    } else {
      if (run >= 5) p += 3 + (run - 5);
      run = 1;
    }
  }
  // Finder-like 1:1:3:1:1 with 4 light modules on one side (outside counts as light).
  static const uint8_t pat[7] = {1, 0, 1, 1, 1, 0, 1};
  for (int i = -4; i + 7 <= s + 4; i++) {
    bool match = true;
    for (int k = 0; k < 7 && match; k++) {
      int pos = i + k;
      uint8_t v = (pos >= 0 && pos < s) ? line[pos * step] : 0;
      match = v == pat[k];
    }
    if (!match) continue;
    bool before = true, after = true;
    for (int k = 1; k <= 4; k++) {
      int b = i - k, a = i + 6 + k;
      if (b >= 0 && b < s && line[b * step]) before = false;
      if (a >= 0 && a < s && line[a * step]) after = false;
    }
    if (before || after) p += 40;
  }
  return p;
}

static int penalty(Grid *g) {
  int s = g->size, p = 0, dark = 0;
  for (int i = 0; i < s; i++) {
    p += line_penalty(g->m + i * s, 1, s);  // row
    p += line_penalty(g->m + i, s, s);      // column
  }
  for (int y = 0; y < s; y++) {
    for (int x = 0; x < s; x++) {
      uint8_t c = g->m[y * s + x];
      dark += c;
      if (x + 1 < s && y + 1 < s && c == g->m[y * s + x + 1] && c == g->m[(y + 1) * s + x] &&
          c == g->m[(y + 1) * s + x + 1]) {
        p += 3;
      }
    }
  }
  int total = s * s;
  int diff = dark * 20 - total * 10;
  if (diff < 0) diff = -diff;
  int k = (diff + total - 1) / total - 1;
  if (k > 0) p += k * 10;
  return p;
}

bool qr_encode(const char *text, QrEcLevel level, QrCode *qr) {
  qr->size = 0;
  int len = strlen(text);
  int version = 0;
  const VersionInfo *versions = level == QR_EC_L ? VERSIONS_L : VERSIONS_M;
  int max_version = level == QR_EC_L ? MAX_VERSION_L : MAX_VERSION_M;
  for (int v = 1; v <= max_version; v++) {
    if (4 + 8 + len * 8 <= versions[v].data * 8) {
      version = v;
      break;
    }
  }
  if (len == 0 || version == 0) return false;
  int data_len = versions[version].data, ec_len = versions[version].ec;

  // Segment: mode 0100 (byte), 8-bit count, data, terminator, pad bytes.
  uint8_t cw[MAX_CODEWORDS];
  memset(cw, 0, sizeof(cw));
  int bit = 0;
#define PUT(val, n) \
  for (int b_ = (n) - 1; b_ >= 0; b_--, bit++) cw[bit >> 3] |= (((val) >> b_) & 1) << (7 - (bit & 7))
  PUT(4, 4);
  PUT(len, 8);
  for (int i = 0; i < len; i++) PUT((uint8_t)text[i], 8);
#undef PUT
  bit += 4;  // terminator (fits: capacity checked above with room to spare or truncated)
  int used = (bit + 7) / 8;
  if (used > data_len) used = data_len;
  for (int i = used, pad = 0xEC; i < data_len; i++, pad ^= 0xEC ^ 0x11) cw[i] = pad;
  reed_solomon(cw, data_len, ec_len, cw + data_len);

  int s = 17 + 4 * version;
  // Static: Pebble app stacks are small.
  static uint8_t m[QR_MAX_SIZE * QR_MAX_SIZE], fn[QR_MAX_SIZE * QR_MAX_SIZE];
  Grid g = {s, m, fn, level == QR_EC_L ? 1 : 0};
  memset(m, 0, sizeof(m));
  memset(fn, 0, sizeof(fn));
  draw_function_patterns(&g, version);
  draw_codewords(&g, cw, data_len + ec_len);

  int best = 0, best_penalty = -1;
  for (int mask = 0; mask < 8; mask++) {
    apply_mask(&g, mask);
    draw_format(&g, mask);
    int p = penalty(&g);
    if (best_penalty < 0 || p < best_penalty) {
      best = mask;
      best_penalty = p;
    }
    apply_mask(&g, mask);  // XOR again to undo
  }
  apply_mask(&g, best);
  draw_format(&g, best);

  qr->size = s;
  for (int y = 0; y < s; y++) {
    for (int x = 0; x < s; x++) qr->modules[y][x] = m[y * s + x];
  }
  return true;
}
