#include <pebble.h>

#include "code128.h"
#include "qr.h"

// Pickup code (Ozon: Code 128; Wildberries: a QR, one per day). The phone companion pushes the
// code; the watch keeps it in persistent storage so it can be shown at the pickup point without a
// phone link. Day codes come with the next days too, and the watch moves on at midnight by itself.
//
// Buttons: SELECT - cycle barcode / QR code (test) / rotated barcode (more quiet zone),
// long SELECT - setup screen with a QR link to the phone app (BACK closes it), UP / DOWN - previous /
// next account (several accounts; the phone re-fetches stale codes by itself). The backlight is left to the system. The app closes itself after a timeout
// without button presses (set in the phone companion), except on setup screens.
//
// Without a stored code the app explains why: phone not connected, no answer from the phone
// (companion not installed -> QR to download it), login needed, or the code is being fetched.

#define PERSIST_CODE 1
#define PERSIST_VISIBLE 2
#define PERSIST_UPDATED 3
#define PERSIST_MODE 5
#define PERSIST_TIMEOUT 6

#define DEFAULT_TIMEOUT_S 120  // until the phone sends the user's choice; 0 = never

#define REQUEST_CACHED 1   // send what you have, refresh if stale
#define REQUEST_REFRESH 2  // re-fetch from Ozon now

#define MARGIN 6

// Where to get the phone companion; shown as a QR (level L fits up to 106 bytes).
#define COMPANION_URL "https://github.com/RealGrapie/PickItUp"
// Status text the companion sends when Ozon login is needed (BarcodeSync.onWatchRequest).
#define STATUS_LOGIN_NEEDED "Log in on phone"
#define STATUS_UPDATING "Updating..."
#define STATUS_OFFLINE "Phone offline"
#define STATUS_NO_ANSWER "No answer from phone"
#define STATUS_TOO_LONG "Code too long"
#define REQUEST_RETRY_MS 300
#define REQUEST_RETRIES 10
// The companion answers a request at once; silence this long means it isn't installed.
#define PHONE_ANSWER_TIMEOUT_MS 5000
// After "Updating..." the result follows within the companion's 60 s fetch timeout.
#define PHONE_FETCH_TIMEOUT_MS 90000

static Window *s_window;
static Layer *s_canvas;

// Several accounts (family): each has its own code; one is shown at a time. The first one keeps
// the persist keys of the single-account versions, so an update needs no migration.
#define MAX_ACCOUNTS 4
#define PERSIST_COUNT 7
#define PERSIST_CURRENT 8
#define PERSIST_NAME0 9
#define FORMAT_CODE128 0
#define FORMAT_QR 1
#define DAYS_MAX_LEN 200  // "<from>|<code>|<text>" lines for the next days (persist values <= 256 bytes)
typedef struct {
  char code[CODE128_MAX_CHARS + 1];
  char visible[24];
  char name[20];    // header line from the phone, e.g. "OZON: Mama"
  char status[24];  // footer override: "Updating...", "Log in on phone"...
  time_t updated;
  int format;       // FORMAT_CODE128 (Ozon) or FORMAT_QR (Wildberries)
  char days[DAYS_MAX_LEN];  // codes for the days after this one (Wildberries)
} Account;
static Account s_acc[MAX_ACCOUNTS];
static int s_count = 1;
static int s_cur;
// The rest of the file works on the account shown.
#define s_code (s_acc[s_cur].code)
#define s_visible (s_acc[s_cur].visible)
#define s_status (s_acc[s_cur].status)
#define s_updated (s_acc[s_cur].updated)

enum { F_CODE, F_VISIBLE, F_UPDATED, F_NAME, F_FORMAT, F_DAYS, F_COUNT };
static uint32_t pkey(int i, int field) {
  if (field >= F_FORMAT) return 200 + i * 2 + (field - F_FORMAT);  // added in 1.6
  if (i == 0) {
    static const uint32_t first[] = { PERSIST_CODE, PERSIST_VISIBLE, PERSIST_UPDATED, PERSIST_NAME0 };
    return first[field];
  }
  return 100 + i * 4 + field;
}

static Window *s_menu_window;
static MenuLayer *s_menu;

typedef enum { MODE_BARS, MODE_VERTICAL, MODE_QR, MODE_COUNT } ViewMode;
static ViewMode s_mode;
static int s_timeout_s = DEFAULT_TIMEOUT_S;
static AppTimer *s_exit_timer;

static uint8_t s_widths[CODE128_MAX_WIDTHS];  // dense (sets B + C)
static int s_nwidths;
static int s_modules;
static uint8_t s_wide[CODE128_MAX_WIDTHS];    // scratch for a longer encoding (see draw_bars)
static QrCode s_qr;        // the pickup code (test view)
static QrCode s_setup_qr;  // COMPANION_URL

typedef enum { PHONE_WAITING, PHONE_ANSWERED, PHONE_SILENT } PhoneState;
static PhoneState s_phone = PHONE_WAITING;
static AppTimer *s_answer_timer;
static AppTimer *s_fetch_timer;  // armed while the phone says "Updating..."
static bool s_show_setup;  // setup screen opened with long SELECT

#define s_format (s_acc[s_cur].format)

static void encode(void) {
  s_nwidths = code128_encode(s_code, CODE128_DENSE, s_widths, &s_modules);
  qr_encode(s_code, QR_EC_M, &s_qr);
  // A QR code needs no bars; s_nwidths doubles as "there is a code to show".
  if (s_format == FORMAT_QR) s_nwidths = s_code[0] && s_qr.size ? 1 : 0;
}

// Day codes: once a day in a->days has begun, it becomes the account's code and the lines up to
// it are dropped. Returns whether the code changed.
static bool next_day(Account *a, int i) {
  time_t now = time(NULL);
  bool changed = false;
  char *p = a->days;
  while (*p) {
    char *end = strchr(p, '\n');
    char *bar1 = strchr(p, '|');
    if (!bar1 || (end && bar1 > end)) break;
    time_t from = (time_t)atoi(p);
    if (from > now) break;
    char *bar2 = strchr(bar1 + 1, '|');
    if (!bar2 || (end && bar2 > end)) break;
    int code_len = bar2 - bar1 - 1;
    int text_len = (end ? end : p + strlen(p)) - bar2 - 1;
    if (code_len > CODE128_MAX_CHARS) code_len = CODE128_MAX_CHARS;
    if (text_len > (int)sizeof(a->visible) - 1) text_len = sizeof(a->visible) - 1;
    memcpy(a->code, bar1 + 1, code_len);
    a->code[code_len] = '\0';
    memcpy(a->visible, bar2 + 1, text_len);
    a->visible[text_len] = '\0';
    changed = true;
    p = end ? end + 1 : p + strlen(p);
  }
  if (changed) {
    memmove(a->days, p, strlen(p) + 1);
    persist_write_string(pkey(i, F_CODE), a->code);
    persist_write_string(pkey(i, F_VISIBLE), a->visible);
    persist_write_string(pkey(i, F_DAYS), a->days);
  }
  return changed;
}

static void next_day_all(void) {
  for (int i = 0; i < s_count; i++) {
    if (next_day(&s_acc[i], i) && i == s_cur) encode();
  }
}

static void load_state(void) {
  if (persist_exists(PERSIST_COUNT)) s_count = persist_read_int(PERSIST_COUNT);
  if (s_count < 1 || s_count > MAX_ACCOUNTS) s_count = 1;
  for (int i = 0; i < s_count; i++) {
    Account *a = &s_acc[i];
    persist_read_string(pkey(i, F_CODE), a->code, sizeof(a->code));
    persist_read_string(pkey(i, F_VISIBLE), a->visible, sizeof(a->visible));
    persist_read_string(pkey(i, F_NAME), a->name, sizeof(a->name));
    a->updated = persist_read_int(pkey(i, F_UPDATED));
    a->format = persist_read_int(pkey(i, F_FORMAT));  // 0 = Code 128 when not stored (before 1.6)
    persist_read_string(pkey(i, F_DAYS), a->days, sizeof(a->days));
    next_day(a, i);
  }
  s_cur = persist_read_int(PERSIST_CURRENT);
  if (s_cur < 0 || s_cur >= s_count) s_cur = 0;
  if (persist_exists(PERSIST_TIMEOUT)) s_timeout_s = persist_read_int(PERSIST_TIMEOUT);
  s_mode = persist_read_int(PERSIST_MODE) % MODE_COUNT;  // 0 (barcode) when not stored yet
#ifdef DEMO
  s_timeout_s = 0;  // emulator demos stay open
  if (!s_acc[0].code[0]) {
    s_count = 2;
    static const char *demo[2][3] = { { "12345678*9012", "1234 5678 * 9012", "OZON: Me" },
                                      { "123456789_123456_1", "123 456", "WB: Family" } };
    for (int i = 0; i < 2; i++) {
      strcpy(s_acc[i].code, demo[i][0]);
      strcpy(s_acc[i].visible, demo[i][1]);
      strcpy(s_acc[i].name, demo[i][2]);
      s_acc[i].updated = time(NULL) - (3 + 20 * i) * 3600;
    }
    s_acc[1].format = FORMAT_QR;
  }
#endif
  encode();
}

static void select_account(int i) {
  s_cur = i;
  persist_write_int(PERSIST_CURRENT, s_cur);
  encode();
}

// Header over the code: from the phone ("OZON: Mama", or "WB pickup code" for the only account).
// Phone apps before 1.6 sent a name only with several accounts.
static const char *header_text(void) {
  return s_acc[s_cur].name[0] ? s_acc[s_cur].name : "OZON pickup code";
}

static void draw_text(GContext *ctx, const char *text, const char *font, GRect box) {
  graphics_draw_text(ctx, text, fonts_get_system_font(font), box,
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
}

__attribute__((unused)) static int isqrt(int v) {
  int r = 0;
  while ((r + 1) * (r + 1) <= v) r++;
  return r;
}

// Room for the code along its direction. Round screens: the chord at the band edge farthest from
// the centre, so the code and its quiet zones stay inside the circle on every bar line.
static int bars_length(GRect area, bool vertical, GRect screen) {
  int length = vertical ? area.size.h : area.size.w;
#ifdef PBL_ROUND
  int r = screen.size.w / 2;
  int from = vertical ? area.origin.x : area.origin.y;
  int to = from + (vertical ? area.size.w : area.size.h);
  int d = from - r < 0 ? r - from : from - r;
  if (to - r > d) d = to - r;
  if (r - to > d) d = r - to;
  int chord = d < r ? 2 * isqrt(r * r - d * d) : 0;
  if (chord < length) length = chord;
#endif
  return length;
}

// Modules are always a whole number of pixels: tested with zxing on 303 Ozon-like codes,
// 1.2-1.4 px/module (what stretching to 200px would give) decodes 0%, 1 and 2+ px 100%.
// 2px needs ~300px, more than any Pebble has, so the code is made longer instead (as Member Card
// does): the fewest digit pairs in set C whose code still fits, i.e. as wide as the room allows.
// Time 2 and Round 2 get a wider code; 144px screens and Time Round keep the dense one.
static void draw_bars(GContext *ctx, GRect area, bool vertical, GRect screen) {
  int room = bars_length(area, vertical, screen) - 2 * MARGIN;
  const uint8_t *widths = s_widths;
  int n = s_nwidths, modules = s_modules;
  if (s_modules < room) {
    for (int pairs = 0; pairs <= CODE128_MAX_CHARS / 2; pairs++) {
      int m = 0;
      int nw = code128_encode(s_code, pairs, s_wide, &m);
      if (nw && m <= room) {
        if (m > modules) { widths = s_wide; n = nw; modules = m; }
        break;
      }
    }
  }
  int length = vertical ? area.size.h : area.size.w;
  int scale = room / modules;
  if (scale < 1) scale = 1;
  int pos = (length - modules * scale) / 2;
  for (int i = 0; i < n; i++) {
    int w = widths[i] * scale;
    if (i % 2 == 0) {
      GRect bar = vertical
          ? GRect(area.origin.x, area.origin.y + pos, area.size.w, w)
          : GRect(area.origin.x + pos, area.origin.y, w, area.size.h);
      graphics_fill_rect(ctx, bar, 0, GCornerNone);
    }
    pos += w;
  }
}

// QR modules are whole pixels too, centred in the area with the white screen as quiet zone;
// [quiet] = modules of white kept on each side within the area (the screen around adds more).
static void draw_qr(GContext *ctx, const QrCode *qr, GRect area, int quiet) {
  if (!qr->size) return;
  int side = area.size.w < area.size.h ? area.size.w : area.size.h;
  int scale = side / (qr->size + 2 * quiet);
  if (scale < 1) scale = 1;
  int x0 = area.origin.x + (area.size.w - qr->size * scale) / 2;
  int y0 = area.origin.y + (area.size.h - qr->size * scale) / 2;
  for (int y = 0; y < qr->size; y++) {
    for (int x = 0; x < qr->size; x++) {
      if (qr->modules[y][x]) {
        graphics_fill_rect(ctx, GRect(x0 + x * scale, y0 + y * scale, scale, scale), 0, GCornerNone);
      }
    }
  }
}

// Round screens (Time Round, Round 2) clip the corners, so content goes inside the circle:
// horizontal bands are given as percentages of the height, QRs use the inscribed square.
__attribute__((unused)) static GRect band(GRect b, int from_pct, int to_pct) {
  return GRect(0, b.size.h * from_pct / 100, b.size.w, b.size.h * (to_pct - from_pct) / 100);
}

__attribute__((unused)) static GRect centered_square(GRect b, int side_pct, int center_y_pct) {
  int side = b.size.w * side_pct / 100;
  return GRect((b.size.w - side) / 2, b.size.h * center_y_pct / 100 - side / 2, side, side);
}

// Screens shown instead of the barcode: why there is no code yet, or the setup QR.
static void draw_setup(GContext *ctx, GRect b) {
  bool big = b.size.w >= 200;
  const char *title_font = big ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD;
  const char *text_font = big ? FONT_KEY_GOTHIC_18 : FONT_KEY_GOTHIC_14;

  if (!s_show_setup) {
    const char *title = NULL, *text = "";
    bool login = strcmp(s_status, STATUS_LOGIN_NEEDED) == 0;
    if (!connection_service_peek_pebble_app_connection()) {
      title = "Phone not connected";
      text = "Open the Pebble app on your phone";
    } else if (s_phone == PHONE_WAITING) {
      title = "Checking phone...";
    } else if (s_phone == PHONE_ANSWERED) {
      bool fetching = strcmp(s_status, STATUS_UPDATING) == 0;
      title = login ? "Log in on phone" : fetching ? "Getting code..." : "No code yet";
      text = login ? "Open PickItUp! on your phone and log in" : fetching ? "" : s_status;
    }
    if (title) {
      int mx = PBL_IF_ROUND_ELSE(b.size.w / 8, 6);
      // Several accounts: whose screen this is.
      if (s_count > 1) draw_text(ctx, header_text(), title_font, PBL_IF_ROUND_ELSE(band(b, 8, 20), GRect(0, 0, b.size.w, 24)));
      draw_text(ctx, title, big ? FONT_KEY_GOTHIC_28_BOLD : FONT_KEY_GOTHIC_24_BOLD,
                GRect(mx, b.size.h / 2 - 50, b.size.w - 2 * mx, 64));
      draw_text(ctx, text, big ? FONT_KEY_GOTHIC_24 : FONT_KEY_GOTHIC_18,
                GRect(mx, b.size.h / 2 + 10, b.size.w - 2 * mx, 70));
      return;
    }
  }

  // No answer from the phone or opened on purpose: link to the app. The Pebble app refuses the
  // request the same way when PickItUp! is missing and when its permission is denied / undecided.
  const char *foot = s_show_setup ? "BACK: close" : "Or allow in Pebble app";
#ifdef PBL_ROUND
  draw_text(ctx, "Get the phone app", title_font, band(b, 7, 20));
  draw_qr(ctx, &s_setup_qr, centered_square(b, 64, 50), 2);
  draw_text(ctx, foot, text_font, band(b, 80, 92));
#else
  int title_h = big ? 30 : 24;
  int foot_h = big ? 26 : 20;
  draw_text(ctx, "Get the phone app", title_font, GRect(0, -2, b.size.w, title_h));
  draw_qr(ctx, &s_setup_qr, GRect(0, title_h, b.size.w, b.size.h - title_h - foot_h), 2);
  draw_text(ctx, foot, text_font, GRect(0, b.size.h - foot_h - 2, b.size.w, foot_h));
#endif
}

// Where things go on the barcode screens. All three views share it: the plain barcode and the QR
// use it as is; the rotated barcode lays it out on a landscape frame and turns it by 90 degrees.
typedef struct {
  GRect header, title, content, code, footer;
} Layout;

static Layout layout_for(GRect b) {
  Layout l;
#ifdef PBL_ROUND
  // The middle band is wide enough for the code plus quiet zones even at its top and bottom.
  l.header = band(b, 0, 21);
  l.title = band(b, 7, 21);
  l.content = band(b, 24, 69);
  l.code = band(b, 70, 83);
  l.footer = band(b, 83, 93);
#else
  // Big screens (Pebble Time 2, 200x228) get larger type and a taller header band.
  bool big = b.size.w >= 200 || b.size.h >= 200;
  int top = big ? 30 : 22;
  int text_h = big ? 30 : 24;
  int foot_h = big ? 26 : 22;
  l.header = GRect(0, 0, b.size.w, top);
  l.title = GRect(0, big ? -1 : -2, b.size.w, top);
  l.content = GRect(0, top + 4, b.size.w, b.size.h - top - 4 - text_h - foot_h);
  l.code = GRect(0, l.content.origin.y + l.content.size.h, b.size.w, text_h);
  l.footer = GRect(0, b.size.h - foot_h + 2, b.size.w, foot_h);
#endif
  return l;
}

// "upd today 14:05" while the code is from today, "outdated" from yesterday on.
static void account_footer(const Account *a, char *out, size_t n) {
  if (a->status[0]) {
    snprintf(out, n, "%s", a->status);
    return;
  }
  if (!a->updated) {
    out[0] = '\0';
    return;
  }
  // Offline the code still works (it's stored on the watch); just say it may not be fresh.
  const char *offline = connection_service_peek_pebble_app_connection() ? "" : "offline, ";
  time_t now = time(NULL);
  struct tm upd = *localtime(&a->updated);
  struct tm today = *localtime(&now);
  if (upd.tm_year == today.tm_year && upd.tm_yday == today.tm_yday) {
    char when[8];
    strftime(when, sizeof(when), "%H:%M", &upd);
    snprintf(out, n, "%supd today %s", offline, when);
  } else if (a->format == FORMAT_QR) {
    // A day code is today's as long as it was handed out for today (next_day moved on to it).
    snprintf(out, n, "%scode for today", offline);
  } else {
    snprintf(out, n, "%soutdated", offline);
  }
}

static void footer_text(char *out, size_t n) { account_footer(&s_acc[s_cur], out, n); }

// Ozon blue, Wildberries purple (the QR accounts are Wildberries).
static GColor header_bg(void) { return PBL_IF_COLOR_ELSE(s_format == FORMAT_QR ? GColorPurple : GColorBlueMoon, GColorWhite); }
static GColor header_fg(void) { return PBL_IF_COLOR_ELSE(GColorWhite, GColorBlack); }

static const char *title_font(bool big) { return big ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD; }
static const char *code_font(bool big) { return big ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD; }
static const char *foot_font(bool big) { return big ? FONT_KEY_GOTHIC_18 : FONT_KEY_GOTHIC_14; }

// Header, code and footer around the content (barcode or QR), upright.
static void draw_frame_texts(GContext *ctx, const Layout *l, bool big) {
  graphics_context_set_fill_color(ctx, header_bg());
  graphics_fill_rect(ctx, l->header, 0, GCornerNone);
  graphics_context_set_text_color(ctx, header_fg());
  draw_text(ctx, header_text(), title_font(big), l->title);
  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_context_set_text_color(ctx, GColorBlack);
  draw_text(ctx, s_visible[0] ? s_visible : s_code, code_font(big), l->code);
  char foot[48];
  footer_text(foot, sizeof(foot));
  draw_text(ctx, foot, foot_font(big), l->footer);
}

// ---- Rotated view. Pebble has no rotated text, so each line is drawn upright in a scratch strip
// of the frame buffer, copied out as 1 bit per pixel ("is text"), and written back turned by
// 90 degrees once the rest is drawn. Landscape (x, y) -> screen (W - 1 - y, x): the landscape top
// is at the screen's right edge (the button side), read with the watch turned counter-clockwise.

typedef struct {
  uint8_t *bits;  // w*h bits, 1 = text pixel
  int w, h;
  GRect dst;      // where the strip goes, in landscape coordinates
  GColor fg;
} TextStrip;

static GRect rotate_rect(GRect r, GRect screen) {
  return GRect(screen.size.w - r.origin.y - r.size.h, r.origin.x, r.size.h, r.size.w);
}

static bool fb_get(GBitmap *fb, int x, int y, uint8_t bg8) {
  if (y < 0 || y >= gbitmap_get_bounds(fb).size.h) return false;
  GBitmapDataRowInfo row = gbitmap_get_data_row_info(fb, y);
  if (x < row.min_x || x > row.max_x) return false;
  if (gbitmap_get_format(fb) == GBitmapFormat1Bit) return !((row.data[x / 8] >> (x % 8)) & 1);  // black text
  return row.data[x] != bg8;
}

static void fb_set(GBitmap *fb, int x, int y, GColor c) {
  GRect bounds = gbitmap_get_bounds(fb);
  if (y < 0 || y >= bounds.size.h) return;
  GBitmapDataRowInfo row = gbitmap_get_data_row_info(fb, y);
  if (x < row.min_x || x > row.max_x) return;
  if (gbitmap_get_format(fb) == GBitmapFormat1Bit) {
    uint8_t mask = 1 << (x % 8);
    if (gcolor_equal(c, GColorBlack)) row.data[x / 8] &= ~mask; else row.data[x / 8] |= mask;
  } else {
    row.data[x] = c.argb;
  }
}

// Renders [text] upright into a scratch strip in the middle of the screen (full-width rows even
// on round screens) and keeps its pixels. The strip is as wide as the screen allows.
// [off] = where the layer is on the screen (it slides while the account changes): the frame
// buffer is the whole screen, the drawing is in layer coordinates.
static void render_strip(GContext *ctx, GRect screen, GPoint off, TextStrip *s, const char *text, const char *font,
                         GRect dst, GColor bg, GColor fg) {
  s->w = dst.size.w < screen.size.w ? dst.size.w : screen.size.w;
  s->h = dst.size.h;
  s->fg = fg;
  // Centre the strip in its landscape box.
  s->dst = GRect(dst.origin.x + (dst.size.w - s->w) / 2, dst.origin.y, s->w, s->h);
  s->bits = calloc((s->w * s->h + 7) / 8, 1);
  if (!s->bits) return;
  // In the middle of the part of the layer that is on the screen.
  int top = off.y < 0 ? -off.y : 0, bottom = off.y > 0 ? screen.size.h - off.y : screen.size.h;
  GRect scratch = GRect((screen.size.w - s->w) / 2, (top + bottom - s->h) / 2, s->w, s->h);
  graphics_context_set_fill_color(ctx, bg);
  graphics_fill_rect(ctx, scratch, 0, GCornerNone);
  graphics_context_set_text_color(ctx, fg);
  draw_text(ctx, text, font, scratch);
  GBitmap *fb = graphics_capture_frame_buffer(ctx);
  if (!fb) return;
  uint8_t bg8 = bg.argb;
  for (int y = 0; y < s->h; y++) {
    for (int x = 0; x < s->w; x++) {
      if (fb_get(fb, off.x + scratch.origin.x + x, off.y + scratch.origin.y + y, bg8)) {
        int i = y * s->w + x;
        s->bits[i / 8] |= 1 << (i % 8);
      }
    }
  }
  graphics_release_frame_buffer(ctx, fb);
}

static void blit_strip(GBitmap *fb, GRect screen, GPoint off, TextStrip *s) {
  if (!s->bits) return;
  for (int y = 0; y < s->h; y++) {
    for (int x = 0; x < s->w; x++) {
      int i = y * s->w + x;
      if (s->bits[i / 8] & (1 << (i % 8))) {
        int lx = s->dst.origin.x + x, ly = s->dst.origin.y + y;
        fb_set(fb, off.x + screen.size.w - 1 - ly, off.y + lx, s->fg);
      }
    }
  }
  free(s->bits);
  s->bits = NULL;
}

static void draw_rotated(GContext *ctx, GRect b, GPoint off) {
  GRect land = GRect(0, 0, b.size.h, b.size.w);
  Layout l = layout_for(land);
  bool big = land.size.w >= 200 || land.size.h >= 200;
  char foot[48];
  footer_text(foot, sizeof(foot));

  TextStrip strips[3];
  render_strip(ctx, b, off, &strips[0], header_text(), title_font(big), l.title, header_bg(), header_fg());
  render_strip(ctx, b, off, &strips[1], s_visible[0] ? s_visible : s_code, code_font(big), l.code, GColorWhite, GColorBlack);
  render_strip(ctx, b, off, &strips[2], foot, foot_font(big), l.footer, GColorWhite, GColorBlack);

  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  graphics_context_set_fill_color(ctx, header_bg());
  graphics_fill_rect(ctx, rotate_rect(l.header, b), 0, GCornerNone);
  graphics_context_set_fill_color(ctx, GColorBlack);
  draw_bars(ctx, rotate_rect(l.content, b), true, b);

  GBitmap *fb = graphics_capture_frame_buffer(ctx);
  for (int i = 0; i < 3; i++) {
    if (fb) blit_strip(fb, b, off, &strips[i]);
    else if (strips[i].bits) { free(strips[i].bits); strips[i].bits = NULL; }
  }
  if (fb) graphics_release_frame_buffer(ctx, fb);
}

static void canvas_update(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_context_set_text_color(ctx, GColorBlack);

  if (s_show_setup || !s_nwidths) {
    draw_setup(ctx, b);
    return;
  }
  // Wildberries scans a QR: the barcode views would only show a code nobody reads.
  ViewMode mode = s_format == FORMAT_QR ? MODE_QR : s_mode;
  if (mode == MODE_VERTICAL) {
    draw_rotated(ctx, b, layer_get_frame(layer).origin);
    return;
  }

  // The module width stays 1px everywhere: 2px would need ~300px for a typical code.
  bool big = b.size.w >= 200;
  Layout l = layout_for(b);
  draw_frame_texts(ctx, &l, big);
  if (mode == MODE_QR) {
#ifdef PBL_ROUND
    draw_qr(ctx, &s_qr, centered_square(b, 46, (l.content.origin.y + l.content.size.h / 2) * 100 / b.size.h), 1);
#else
    draw_qr(ctx, &s_qr, l.content, 1);  // as big as the band allows: 4px modules on 144x168
#endif
  } else {
    draw_bars(ctx, l.content, false, b);
  }
}


static void menu_color_for(MenuLayer *menu, int row);

// The barcode window and the accounts menu, whichever exist right now.
static void redraw(void) {
  if (s_canvas) layer_mark_dirty(s_canvas);
  if (s_menu) {
    // A row may have just changed marketplace (the phone reordered its accounts).
    menu_color_for(s_menu, menu_layer_get_selected_index(s_menu).row);
    menu_layer_reload_data(s_menu);
  }
}

// Replaces a pending "Updating..." so it never hangs when the phone is gone.
static void clear_updating(const char *reason) {
  for (int i = 0; i < MAX_ACCOUNTS; i++) {
    char *st = s_acc[i].status;
    if (strcmp(st, STATUS_UPDATING) == 0) snprintf(st, sizeof(s_acc[i].status), "%s", reason);
  }
}

static void answer_timeout(void *data) {
  s_answer_timer = NULL;
  if (s_phone == PHONE_WAITING) {
    s_phone = PHONE_SILENT;
    clear_updating(connection_service_peek_pebble_app_connection() ? STATUS_NO_ANSWER : STATUS_OFFLINE);
    redraw();
  }
}

// The phone said "Updating..." and then went silent (killed, lost the watch): don't hang.
static void fetch_timeout(void *data) {
  s_fetch_timer = NULL;
  clear_updating(connection_service_peek_pebble_app_connection() ? STATUS_NO_ANSWER : STATUS_OFFLINE);
  redraw();
}

static int s_request_kind;
static int s_request_tries;

// The outbox may still be busy with the previous message: try again shortly instead of dropping
// the request (5 s later the silence would read as "companion not installed").
static void send_request(void *data) {
  DictionaryIterator *it;
  if (app_message_outbox_begin(&it) != APP_MSG_OK) {
    if (++s_request_tries < REQUEST_RETRIES) app_timer_register(REQUEST_RETRY_MS, send_request, NULL);
    return;
  }
  dict_write_int32(it, MESSAGE_KEY_Request, s_request_kind);
  dict_write_int32(it, MESSAGE_KEY_Index, s_cur);  // the code shown (for a REFRESH request)
  app_message_outbox_send();
}

static void request_from_phone(int kind) {
  s_phone = PHONE_WAITING;
  if (s_answer_timer) app_timer_cancel(s_answer_timer);
  s_answer_timer = app_timer_register(PHONE_ANSWER_TIMEOUT_MS, answer_timeout, NULL);
  s_request_kind = kind;
  s_request_tries = 0;
  send_request(NULL);
}

static void outbox_failed(DictionaryIterator *it, AppMessageResult reason, void *context) {
  answer_timeout(NULL);  // nobody took the request: same as no answer
}

static void initial_request(void *data) {
  request_from_phone(REQUEST_CACHED);
}

static void exit_app(void *data) {
  s_exit_timer = NULL;
  window_stack_pop_all(false);
}

// (Re)starts the inactivity countdown; called on open, on every button press and when the
// phone changes the timeout.
static void restart_exit_timer(void) {
  if (s_exit_timer) app_timer_cancel(s_exit_timer);
  // No auto-exit while setting up: installing the app and logging in takes a while.
  bool setup = s_show_setup || (!s_nwidths && s_canvas);  // s_canvas: the code window is up
  s_exit_timer = s_timeout_s > 0 && !setup ? app_timer_register(s_timeout_s * 1000, exit_app, NULL) : NULL;
}

static void copy_string(char *dst, size_t n, const Tuple *t) {
  strncpy(dst, t->value->cstring, n - 1);
  dst[n - 1] = '\0';
}

// One message = one account (Index; none from phone apps before multi-account = the first one).
// A status without Index is for all of them (e.g. "Off in phone app").
static void inbox_received(DictionaryIterator *it, void *context) {
  Tuple *t;
  bool too_long = false;
  s_phone = PHONE_ANSWERED;
  int i = 0;
  bool indexed = false;
  if ((t = dict_find(it, MESSAGE_KEY_Index))) {
    i = t->value->int32;
    indexed = true;
    if (i < 0 || i >= MAX_ACCOUNTS) return;  // more accounts than the watch keeps
  }
  if ((t = dict_find(it, MESSAGE_KEY_Count))) {
    int count = t->value->int32;
    if (count > MAX_ACCOUNTS) count = MAX_ACCOUNTS;
    if (count >= 1 && count != s_count) {
      // Accounts removed on the phone: forget their codes.
      for (int k = count; k < s_count; k++) {
        for (int f = F_CODE; f < F_COUNT; f++) persist_delete(pkey(k, f));
        memset(&s_acc[k], 0, sizeof(s_acc[k]));
      }
      s_count = count;
      persist_write_int(PERSIST_COUNT, s_count);
      if (s_cur >= s_count) select_account(0);
    }
  }
  Account *a = &s_acc[i];
  bool shown = i == s_cur;
  if ((t = dict_find(it, MESSAGE_KEY_Name)) && t->type == TUPLE_CSTRING) {
    copy_string(a->name, sizeof(a->name), t);
    persist_write_string(pkey(i, F_NAME), a->name);
  }
  // Before the code: it decides how the code gets encoded.
  if ((t = dict_find(it, MESSAGE_KEY_Format))) {
    a->format = t->value->int32;
    persist_write_int(pkey(i, F_FORMAT), a->format);
    if (shown) encode();
  }
  if ((t = dict_find(it, MESSAGE_KEY_Code)) && t->type == TUPLE_CSTRING && t->length <= 1) {
    // Empty code = logged out on the phone: forget the stored one, back to the setup screens.
    a->code[0] = a->visible[0] = '\0';
    a->updated = 0;
    persist_delete(pkey(i, F_CODE));
    persist_delete(pkey(i, F_VISIBLE));
    persist_delete(pkey(i, F_UPDATED));
    a->days[0] = 0;
    persist_delete(pkey(i, F_DAYS));
    if (shown) {
      s_nwidths = 0;
      restart_exit_timer();
    }
  } else if (t && t->type == TUPLE_CSTRING && t->length - 1 > CODE128_MAX_CHARS) {
    // Cut short it would still scan, as a wrong code: keep the stored one and say so.
    too_long = true;
  } else if (t && t->type == TUPLE_CSTRING) {
    copy_string(a->code, sizeof(a->code), t);
    persist_write_string(pkey(i, F_CODE), a->code);
    if (shown) {
      bool first = !s_nwidths;
      encode();
      if (first) restart_exit_timer();  // leaving the setup screens arms the auto-exit
    }
  }
  if (!too_long && (t = dict_find(it, MESSAGE_KEY_Visible)) && t->type == TUPLE_CSTRING) {
    copy_string(a->visible, sizeof(a->visible), t);
    persist_write_string(pkey(i, F_VISIBLE), a->visible);
  }
  if (!too_long && (t = dict_find(it, MESSAGE_KEY_Updated))) {
    a->updated = (time_t)t->value->int32;
    persist_write_int(pkey(i, F_UPDATED), (int32_t)a->updated);
  }
  if (!too_long && (t = dict_find(it, MESSAGE_KEY_Days)) && t->type == TUPLE_CSTRING) {
    copy_string(a->days, sizeof(a->days), t);
    persist_write_string(pkey(i, F_DAYS), a->days);
    if (next_day(a, i) && shown) encode();
  }
  if ((t = dict_find(it, MESSAGE_KEY_Status)) && t->type == TUPLE_CSTRING) {
    for (int k = 0; k < MAX_ACCOUNTS; k++) {
      if (indexed ? k == i : true) copy_string(s_acc[k].status, sizeof(s_acc[k].status), t);
    }
    bool updating = false;
    for (int k = 0; k < s_count; k++) updating |= strcmp(s_acc[k].status, STATUS_UPDATING) == 0;
    if (s_fetch_timer) app_timer_cancel(s_fetch_timer);
    s_fetch_timer = updating ? app_timer_register(PHONE_FETCH_TIMEOUT_MS, fetch_timeout, NULL) : NULL;
  }
  if (too_long) snprintf(a->status, sizeof(a->status), STATUS_TOO_LONG);
  if ((t = dict_find(it, MESSAGE_KEY_Timeout))) {
    int timeout = t->value->int32;
    if (timeout != s_timeout_s) {
      s_timeout_s = timeout;
      persist_write_int(PERSIST_TIMEOUT, s_timeout_s);
      restart_exit_timer();
    }
  }
  redraw();
}

static void select_click(ClickRecognizerRef recognizer, void *context) {
  if (s_show_setup) {
    // The companion QR is left with BACK only.
  } else if (s_nwidths && s_format != FORMAT_QR) {
    // Order on screen: barcode -> QR -> rotated (enum values stay as persisted before).
    static const ViewMode next[MODE_COUNT] = { [MODE_BARS] = MODE_QR, [MODE_QR] = MODE_VERTICAL,
                                               [MODE_VERTICAL] = MODE_BARS };
    s_mode = next[s_mode];
    persist_write_int(PERSIST_MODE, s_mode);
  }
  restart_exit_timer();
  redraw();
}

static void select_long_click(ClickRecognizerRef recognizer, void *context) {
  s_show_setup = true;
  restart_exit_timer();
  redraw();
}

// ---- UP / DOWN: the previous / next account's code, scrolled like a list: the code leaves
// upwards (DOWN) or downwards (UP) and the other one comes in from the opposite edge.

#define SLIDE_MS 260
static Animation *s_slide;
static int s_slide_to = -1;  // account shown once the old code has left
static int s_slide_dir;      // +1 = next (content goes up), -1 = previous

static void slide_offset(int y) {
  if (!s_canvas) return;
  GRect f = layer_get_frame(s_canvas);
  f.origin.y = y;
  layer_set_frame(s_canvas, f);
}

static void show_account(int i) {
  select_account(i);
  if (s_menu) menu_layer_set_selected_index(s_menu, MenuIndex(0, s_cur), MenuRowAlignCenter, false);
  redraw();  // also recolours the menu's highlight for the new row
}

static void slide_update(Animation *anim, const AnimationProgress progress) {
  if (!s_canvas) return;
  int h = layer_get_bounds(s_canvas).size.h;
  int half = ANIMATION_NORMALIZED_MAX / 2;
  if (progress < half) {
    slide_offset(-s_slide_dir * h * progress / half);
  } else {
    if (s_slide_to >= 0) { show_account(s_slide_to); s_slide_to = -1; }
    slide_offset(s_slide_dir * h * (ANIMATION_NORMALIZED_MAX - progress) / half);
  }
}

static void slide_stopped(Animation *anim, bool finished, void *context) {
  if (anim != s_slide) return;  // replaced by a newer press
  if (s_slide_to >= 0) { show_account(s_slide_to); s_slide_to = -1; }
  slide_offset(0);
  s_slide = NULL;  // freed by the system (SDK 3+)
}

static const AnimationImplementation s_slide_impl = { .update = slide_update };

static void step_account(int dir) {
  restart_exit_timer();
  if (s_count < 2 || s_show_setup) return;  // one account: the press only keeps the app open
  // A press during a slide: from where that one was going.
  if (s_slide) {
    Animation *old = s_slide;
    s_slide = NULL;
    if (s_slide_to >= 0) { show_account(s_slide_to); s_slide_to = -1; }
    animation_unschedule(old);
  }
  int to = (s_cur + dir + s_count) % s_count;
  s_slide_to = to;
  s_slide_dir = dir;
  s_slide = animation_create();
  if (!s_slide) { show_account(to); s_slide_to = -1; return; }
  animation_set_duration(s_slide, SLIDE_MS);
  animation_set_curve(s_slide, AnimationCurveEaseInOut);
  animation_set_implementation(s_slide, &s_slide_impl);
  animation_set_handlers(s_slide, (AnimationHandlers){ .stopped = slide_stopped }, NULL);
  animation_schedule(s_slide);
}

static void up_click(ClickRecognizerRef recognizer, void *context) { step_account(-1); }
static void down_click(ClickRecognizerRef recognizer, void *context) { step_account(1); }

// BACK closes the companion QR opened with long SELECT; otherwise back to the accounts menu, or
// out of the app.
static void back_click(ClickRecognizerRef recognizer, void *context) {
  if (s_show_setup) {
    s_show_setup = false;
    restart_exit_timer();
    redraw();
  } else {
    window_stack_pop(true);
  }
}

static void click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click);
  window_long_click_subscribe(BUTTON_ID_SELECT, 600, select_long_click, NULL);
  window_single_click_subscribe(BUTTON_ID_UP, up_click);
  window_single_click_subscribe(BUTTON_ID_DOWN, down_click);
  window_single_click_subscribe(BUTTON_ID_BACK, back_click);
}

static void phone_connection(bool connected) {
  if (connected) {
    for (int i = 0; i < MAX_ACCOUNTS; i++) {
      if (strcmp(s_acc[i].status, STATUS_OFFLINE) == 0) s_acc[i].status[0] = '\0';
    }
    request_from_phone(REQUEST_CACHED);  // ask again once the phone is back
  } else {
    clear_updating(STATUS_OFFLINE);
  }
  redraw();
}

// Midnight: day codes move on to the new day's (no phone needed).
static void day_changed(struct tm *tick_time, TimeUnits units_changed) {
  next_day_all();
  redraw();
}

static void window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_canvas = layer_create(layer_get_bounds(root));
  layer_set_update_proc(s_canvas, canvas_update);
  layer_add_child(root, s_canvas);
  restart_exit_timer();
}

static void window_unload(Window *window) {
  layer_destroy(s_canvas);
  s_canvas = NULL;
}

// ---- Accounts menu: shown first when there are several; a row opens that account's code.

static uint16_t menu_rows(MenuLayer *menu, uint16_t section, void *data) { return s_count; }

static void menu_draw_row(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  const Account *a = &s_acc[idx->row];
  char title[24], sub[32];
  if (a->name[0]) snprintf(title, sizeof(title), "%s", a->name);
  else snprintf(title, sizeof(title), "Account %d", idx->row + 1);
  if (a->code[0]) account_footer(a, sub, sizeof(sub));
  else snprintf(sub, sizeof(sub), "%s", a->status[0] ? a->status : "No code yet");
  menu_cell_basic_draw(ctx, cell, title, sub, NULL);
}

static void menu_select(MenuLayer *menu, MenuIndex *idx, void *data) {
  select_account(idx->row);
  restart_exit_timer();
  window_stack_push(s_window, true);
}

// The highlight takes the marketplace colour of the row under it (Ozon blue, Wildberries purple).
static void menu_color_for(MenuLayer *menu, int row) {
#ifdef PBL_COLOR
  if (row < 0 || row >= s_count) return;
  GColor bg = s_acc[row].format == FORMAT_QR ? GColorPurple : GColorBlueMoon;
  menu_layer_set_highlight_colors(menu, bg, GColorWhite);
#endif
}

static void menu_changed(MenuLayer *menu, MenuIndex new_index, MenuIndex old_index, void *data) {
  menu_color_for(menu, new_index.row);
  restart_exit_timer();
}

static void menu_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks){
    .get_num_rows = menu_rows,
    .draw_row = menu_draw_row,
    .select_click = menu_select,
    .selection_changed = menu_changed,
  });
  menu_color_for(s_menu, s_cur);
  menu_layer_set_click_config_onto_window(s_menu, window);
  menu_layer_set_selected_index(s_menu, MenuIndex(0, s_cur), MenuRowAlignCenter, false);
  layer_add_child(root, menu_layer_get_layer(s_menu));
  restart_exit_timer();
}

static void menu_unload(Window *window) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
}

static void init(void) {
  load_state();
  qr_encode(COMPANION_URL, QR_EC_L, &s_setup_qr);
  s_window = window_create();
  window_set_background_color(s_window, GColorWhite);
  window_set_click_config_provider(s_window, click_config);
  window_set_window_handlers(s_window, (WindowHandlers){
    .load = window_load,
    .unload = window_unload,
  });
  if (s_count > 1) {
    // Several accounts: pick one first; BACK from its code comes back here.
    s_menu_window = window_create();
    window_set_window_handlers(s_menu_window, (WindowHandlers){
      .load = menu_load,
      .unload = menu_unload,
    });
    window_stack_push(s_menu_window, true);
  } else {
    window_stack_push(s_window, true);
  }

  app_message_register_inbox_received(inbox_received);
  app_message_register_outbox_failed(outbox_failed);
  connection_service_subscribe((ConnectionHandlers){.pebble_app_connection_handler = phone_connection});
  tick_timer_service_subscribe(DAY_UNIT, day_changed);
  // Day codes add a few lines of the next days to a message.
  app_message_open(512, 64);
  app_timer_register(300, initial_request, NULL);
}

static void deinit(void) {
  window_destroy(s_window);
  if (s_menu_window) window_destroy(s_menu_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
