#include <pebble.h>

#include "code128.h"
#include "qr.h"

// Ozon pickup barcode. The phone companion pushes the code; the watch keeps it in
// persistent storage so it can be shown at the pickup point without a phone link.
//
// Buttons: SELECT - cycle barcode / QR code (test) / rotated barcode (more quiet zone),
// long SELECT - setup screen with a QR link to the phone app (BACK closes it), UP - ask the phone to re-fetch
// from Ozon. The backlight is left to the system. The app closes itself after a timeout
// without button presses (set in the phone companion), except on setup screens.
//
// Without a stored code the app explains why: phone not connected, no answer from the phone
// (companion not installed -> QR to download it), login needed, or the code is being fetched.

#define PERSIST_CODE 1
#define PERSIST_VISIBLE 2
#define PERSIST_UPDATED 3
#define PERSIST_MODE 5
#define PERSIST_TIMEOUT 6

#define DEFAULT_TIMEOUT_S 60  // until the phone sends the user's choice; 0 = never

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
// The companion answers a request at once; silence this long means it isn't installed.
#define PHONE_ANSWER_TIMEOUT_MS 5000

static Window *s_window;
static Layer *s_canvas;

static char s_code[CODE128_MAX_CHARS + 1];
static char s_visible[48];
static char s_status[48];
static time_t s_updated;
typedef enum { MODE_BARS, MODE_VERTICAL, MODE_QR, MODE_COUNT } ViewMode;
static ViewMode s_mode;
static int s_timeout_s = DEFAULT_TIMEOUT_S;
static AppTimer *s_exit_timer;

static uint8_t s_widths[CODE128_MAX_WIDTHS];
static int s_nwidths;
static int s_modules;
static QrCode s_qr;        // the pickup code (test view)
static QrCode s_setup_qr;  // COMPANION_URL

typedef enum { PHONE_WAITING, PHONE_ANSWERED, PHONE_SILENT } PhoneState;
static PhoneState s_phone = PHONE_WAITING;
static AppTimer *s_answer_timer;
static bool s_show_setup;  // setup screen opened with long SELECT

static void encode(void) {
  s_nwidths = code128_encode(s_code, s_widths, &s_modules);
  qr_encode(s_code, QR_EC_M, &s_qr);
}

static void load_state(void) {
  persist_read_string(PERSIST_CODE, s_code, sizeof(s_code));
  persist_read_string(PERSIST_VISIBLE, s_visible, sizeof(s_visible));
  s_updated = persist_read_int(PERSIST_UPDATED);
  if (persist_exists(PERSIST_TIMEOUT)) s_timeout_s = persist_read_int(PERSIST_TIMEOUT);
  s_mode = persist_read_int(PERSIST_MODE) % MODE_COUNT;  // 0 (barcode) when not stored yet
#ifdef DEMO
  s_timeout_s = 0;  // emulator demos stay open
  if (!s_code[0]) {
    strcpy(s_code, "12345678*9012");
    strcpy(s_visible, "1234 5678 * 9012");
    s_updated = time(NULL) - 3 * 3600;
  }
#endif
  encode();
}

static void draw_text(GContext *ctx, const char *text, const char *font, GRect box) {
  graphics_draw_text(ctx, text, fonts_get_system_font(font), box,
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
}

// Modules are always a whole number of pixels: tested with zxing on 303 Ozon-like codes,
// 1.2-1.4 px/module (what stretching to 200px would give) decodes 0%, 1 and 2+ px 100%.
static void draw_bars(GContext *ctx, GRect area, bool vertical) {
  int length = vertical ? area.size.h : area.size.w;
  int scale = (length - 2 * MARGIN) / s_modules;
  if (scale < 1) scale = 1;
  int pos = (length - s_modules * scale) / 2;
  for (int i = 0; i < s_nwidths; i++) {
    int w = s_widths[i] * scale;
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
      title = login ? "Log in to Ozon" : fetching ? "Getting barcode..." : "No barcode yet";
      text = login ? "Open PickItUp! on your phone and log in" : fetching ? "" : s_status;
    }
    if (title) {
      int mx = PBL_IF_ROUND_ELSE(b.size.w / 8, 6);
      draw_text(ctx, title, big ? FONT_KEY_GOTHIC_28_BOLD : FONT_KEY_GOTHIC_24_BOLD,
                GRect(mx, b.size.h / 2 - 50, b.size.w - 2 * mx, 64));
      draw_text(ctx, text, big ? FONT_KEY_GOTHIC_24 : FONT_KEY_GOTHIC_18,
                GRect(mx, b.size.h / 2 + 10, b.size.w - 2 * mx, 70));
      return;
    }
  }

  // No answer from the phone (companion missing) or opened on purpose: link to the app.
  const char *foot = s_show_setup ? "BACK: close" : "Scan with your phone";
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
static void footer_text(char *out, size_t n) {
  if (s_status[0]) {
    snprintf(out, n, "%s", s_status);
    return;
  }
  if (!s_updated) {
    out[0] = '\0';
    return;
  }
  // Offline the code still works (it's stored on the watch); just say it may not be fresh.
  const char *offline = connection_service_peek_pebble_app_connection() ? "" : "offline, ";
  time_t now = time(NULL);
  struct tm upd = *localtime(&s_updated);
  struct tm today = *localtime(&now);
  if (upd.tm_year == today.tm_year && upd.tm_yday == today.tm_yday) {
    char when[8];
    strftime(when, sizeof(when), "%H:%M", &upd);
    snprintf(out, n, "%supd today %s", offline, when);
  } else {
    snprintf(out, n, "%soutdated", offline);
  }
}

static GColor header_bg(void) { return PBL_IF_COLOR_ELSE(GColorBlueMoon, GColorWhite); }
static GColor header_fg(void) { return PBL_IF_COLOR_ELSE(GColorWhite, GColorBlack); }

static const char *title_font(bool big) { return big ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD; }
static const char *code_font(bool big) { return big ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD; }
static const char *foot_font(bool big) { return big ? FONT_KEY_GOTHIC_18 : FONT_KEY_GOTHIC_14; }

// Header, code and footer around the content (barcode or QR), upright.
static void draw_frame_texts(GContext *ctx, const Layout *l, bool big) {
  graphics_context_set_fill_color(ctx, header_bg());
  graphics_fill_rect(ctx, l->header, 0, GCornerNone);
  graphics_context_set_text_color(ctx, header_fg());
  draw_text(ctx, "OZON pickup code", title_font(big), l->title);
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
static void render_strip(GContext *ctx, GRect screen, TextStrip *s, const char *text, const char *font,
                         GRect dst, GColor bg, GColor fg) {
  s->w = dst.size.w < screen.size.w ? dst.size.w : screen.size.w;
  s->h = dst.size.h;
  s->fg = fg;
  // Centre the strip in its landscape box.
  s->dst = GRect(dst.origin.x + (dst.size.w - s->w) / 2, dst.origin.y, s->w, s->h);
  s->bits = calloc((s->w * s->h + 7) / 8, 1);
  if (!s->bits) return;
  GRect scratch = GRect((screen.size.w - s->w) / 2, (screen.size.h - s->h) / 2, s->w, s->h);
  graphics_context_set_fill_color(ctx, bg);
  graphics_fill_rect(ctx, scratch, 0, GCornerNone);
  graphics_context_set_text_color(ctx, fg);
  draw_text(ctx, text, font, scratch);
  GBitmap *fb = graphics_capture_frame_buffer(ctx);
  if (!fb) return;
  uint8_t bg8 = bg.argb;
  for (int y = 0; y < s->h; y++) {
    for (int x = 0; x < s->w; x++) {
      if (fb_get(fb, scratch.origin.x + x, scratch.origin.y + y, bg8)) {
        int i = y * s->w + x;
        s->bits[i / 8] |= 1 << (i % 8);
      }
    }
  }
  graphics_release_frame_buffer(ctx, fb);
}

static void blit_strip(GBitmap *fb, GRect screen, TextStrip *s) {
  if (!s->bits) return;
  for (int y = 0; y < s->h; y++) {
    for (int x = 0; x < s->w; x++) {
      int i = y * s->w + x;
      if (s->bits[i / 8] & (1 << (i % 8))) {
        int lx = s->dst.origin.x + x, ly = s->dst.origin.y + y;
        fb_set(fb, screen.size.w - 1 - ly, lx, s->fg);
      }
    }
  }
  free(s->bits);
  s->bits = NULL;
}

static void draw_rotated(GContext *ctx, GRect b) {
  GRect land = GRect(0, 0, b.size.h, b.size.w);
  Layout l = layout_for(land);
  bool big = land.size.w >= 200 || land.size.h >= 200;
  char foot[48];
  footer_text(foot, sizeof(foot));

  TextStrip strips[3];
  render_strip(ctx, b, &strips[0], "OZON pickup code", title_font(big), l.title, header_bg(), header_fg());
  render_strip(ctx, b, &strips[1], s_visible[0] ? s_visible : s_code, code_font(big), l.code, GColorWhite, GColorBlack);
  render_strip(ctx, b, &strips[2], foot, foot_font(big), l.footer, GColorWhite, GColorBlack);

  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  graphics_context_set_fill_color(ctx, header_bg());
  graphics_fill_rect(ctx, rotate_rect(l.header, b), 0, GCornerNone);
  graphics_context_set_fill_color(ctx, GColorBlack);
  draw_bars(ctx, rotate_rect(l.content, b), true);

  GBitmap *fb = graphics_capture_frame_buffer(ctx);
  for (int i = 0; i < 3; i++) {
    if (fb) blit_strip(fb, b, &strips[i]);
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
  if (s_mode == MODE_VERTICAL) {
    draw_rotated(ctx, b);
    return;
  }

  // The module width stays 1px everywhere: 2px would need 268px for a typical 134-module code.
  bool big = b.size.w >= 200;
  Layout l = layout_for(b);
  draw_frame_texts(ctx, &l, big);
  if (s_mode == MODE_QR) {
#ifdef PBL_ROUND
    draw_qr(ctx, &s_qr, centered_square(b, 46, (l.content.origin.y + l.content.size.h / 2) * 100 / b.size.h), 1);
#else
    draw_qr(ctx, &s_qr, l.content, 1);  // as big as the band allows: 4px modules on 144x168
#endif
  } else {
    draw_bars(ctx, l.content, false);
  }
}

// Replaces a pending "Updating..." so it never hangs when the phone is gone.
static void clear_updating(const char *reason) {
  if (strcmp(s_status, STATUS_UPDATING) == 0) snprintf(s_status, sizeof(s_status), "%s", reason);
}

static void answer_timeout(void *data) {
  s_answer_timer = NULL;
  if (s_phone == PHONE_WAITING) {
    s_phone = PHONE_SILENT;
    clear_updating(connection_service_peek_pebble_app_connection() ? STATUS_NO_ANSWER : STATUS_OFFLINE);
    layer_mark_dirty(s_canvas);
  }
}

static void request_from_phone(int kind) {
  s_phone = PHONE_WAITING;
  if (s_answer_timer) app_timer_cancel(s_answer_timer);
  s_answer_timer = app_timer_register(PHONE_ANSWER_TIMEOUT_MS, answer_timeout, NULL);
  DictionaryIterator *it;
  if (app_message_outbox_begin(&it) != APP_MSG_OK) return;
  dict_write_int32(it, MESSAGE_KEY_Request, kind);
  app_message_outbox_send();
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
  bool setup = s_show_setup || !s_nwidths;
  s_exit_timer = s_timeout_s > 0 && !setup ? app_timer_register(s_timeout_s * 1000, exit_app, NULL) : NULL;
}

static void inbox_received(DictionaryIterator *it, void *context) {
  Tuple *t;
  s_phone = PHONE_ANSWERED;
  if ((t = dict_find(it, MESSAGE_KEY_Code)) && t->type == TUPLE_CSTRING) {
    bool first = !s_nwidths;
    strncpy(s_code, t->value->cstring, sizeof(s_code) - 1);
    s_code[sizeof(s_code) - 1] = '\0';
    persist_write_string(PERSIST_CODE, s_code);
    encode();
    if (first) restart_exit_timer();  // leaving the setup screens arms the auto-exit
  }
  if ((t = dict_find(it, MESSAGE_KEY_Visible)) && t->type == TUPLE_CSTRING) {
    strncpy(s_visible, t->value->cstring, sizeof(s_visible) - 1);
    s_visible[sizeof(s_visible) - 1] = '\0';
    persist_write_string(PERSIST_VISIBLE, s_visible);
  }
  if ((t = dict_find(it, MESSAGE_KEY_Updated))) {
    s_updated = (time_t)t->value->int32;
    persist_write_int(PERSIST_UPDATED, (int32_t)s_updated);
  }
  if ((t = dict_find(it, MESSAGE_KEY_Status)) && t->type == TUPLE_CSTRING) {
    strncpy(s_status, t->value->cstring, sizeof(s_status) - 1);
    s_status[sizeof(s_status) - 1] = '\0';
  }
  if ((t = dict_find(it, MESSAGE_KEY_Timeout))) {
    int timeout = t->value->int32;
    if (timeout != s_timeout_s) {
      s_timeout_s = timeout;
      persist_write_int(PERSIST_TIMEOUT, s_timeout_s);
      restart_exit_timer();
    }
  }
  layer_mark_dirty(s_canvas);
}

static void select_click(ClickRecognizerRef recognizer, void *context) {
  if (s_show_setup) {
    // The companion QR is left with BACK only.
  } else if (s_nwidths) {
    // Order on screen: barcode -> QR -> rotated (enum values stay as persisted before).
    static const ViewMode next[MODE_COUNT] = { [MODE_BARS] = MODE_QR, [MODE_QR] = MODE_VERTICAL,
                                               [MODE_VERTICAL] = MODE_BARS };
    s_mode = next[s_mode];
    persist_write_int(PERSIST_MODE, s_mode);
  }
  restart_exit_timer();
  layer_mark_dirty(s_canvas);
}

static void select_long_click(ClickRecognizerRef recognizer, void *context) {
  s_show_setup = true;
  restart_exit_timer();
  layer_mark_dirty(s_canvas);
}

static void up_click(ClickRecognizerRef recognizer, void *context) {
  restart_exit_timer();
  if (!connection_service_peek_pebble_app_connection()) {
    snprintf(s_status, sizeof(s_status), STATUS_OFFLINE);
  } else {
    snprintf(s_status, sizeof(s_status), STATUS_UPDATING);
    request_from_phone(REQUEST_REFRESH);
  }
  layer_mark_dirty(s_canvas);
}

static void down_click(ClickRecognizerRef recognizer, void *context) {
  restart_exit_timer();
}

// BACK closes the companion QR opened with long SELECT; otherwise it leaves the app as usual.
static void back_click(ClickRecognizerRef recognizer, void *context) {
  if (s_show_setup) {
    s_show_setup = false;
    restart_exit_timer();
    layer_mark_dirty(s_canvas);
  } else {
    window_stack_pop_all(true);
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
    if (strcmp(s_status, STATUS_OFFLINE) == 0) s_status[0] = '\0';
    request_from_phone(REQUEST_CACHED);  // ask again once the phone is back
  } else {
    clear_updating(STATUS_OFFLINE);
  }
  layer_mark_dirty(s_canvas);
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
  window_stack_push(s_window, true);

  app_message_register_inbox_received(inbox_received);
  app_message_register_outbox_failed(outbox_failed);
  connection_service_subscribe((ConnectionHandlers){.pebble_app_connection_handler = phone_connection});
  app_message_open(256, 64);
  app_timer_register(300, initial_request, NULL);
}

static void deinit(void) {
  window_destroy(s_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
