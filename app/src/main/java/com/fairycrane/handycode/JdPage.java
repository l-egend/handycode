package com.fairycrane.handycode;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.http.SslError;
import android.view.Gravity;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.internal.WebViewGlueCommunicator;
import org.chromium.support_lib_boundary.WebSettingsBoundaryInterface;
import org.chromium.support_lib_boundary.util.BoundaryInterfaceReflectionUtil;
import org.json.*;
import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Multi-platform pickup code aggregator + official login switcher (TB, PDD, JD, SF, Douyin). */
final class JdPage extends LinearLayout {
 static final int MODE_SUMMARY = -1;
 static final int PROVIDER_TB = 0, PROVIDER_PDD = 1, PROVIDER_JD = 2, PROVIDER_SF = 3, PROVIDER_DY = 4, PROVIDER_JDL = 6, PROVIDER_COUNT = 7;
 static final String[] PROVIDER_NAMES = {"淘宝/菜鸟", "拼多多", "京东", "顺丰", "抖音", "", "京东快递"};
 // Preserve the existing cache indices when removing the duplicate Cainiao entry.
 private static final int LEGACY_CN_SLOT = 5;
 private static final int[] PLATFORMS = {PROVIDER_TB, PROVIDER_PDD, PROVIDER_JD, PROVIDER_SF, PROVIDER_DY};
 private static final int[] BADGE_COLORS = {0xFFFF5000, 0xFFE02E24, 0xFFE1251B, 0xFF2B313D, 0xFF1F2332, 0xFF0066FF, 0xFFE1251B};
 private static final String PREF_CACHE = "hc_pickup_cache";
 private static final String PREF_MANUAL_JDL_CODES = "manual_jdl_pickup_codes";
 private static final String PREF_TAOBAO_ORDER_CODES = "taobao_order_pickup_codes";
 private static final int MAX_TAOBAO_ORDERS = 80;
 private static final String DY_ORDER_LOGIN_HINT = "抖音订单需要重新授权。点「刷新网页」回到官网验证。";
 private static final Set<String> JD_ORDER_ORIGINS = new HashSet<>(Arrays.asList(
  "https://trade.m.jd.com", "https://my.m.jd.com"));

 private final Consumer<Boolean> session;
 private final IntConsumer externalSwitch;
 private final Runnable onModeChanged;
 private final TextView status, pullBanner;
 private final LinearLayout tools, toolButtons, cards;
 private final View toolSpace;
 private final FrameLayout stage, webContainer;
 private final ScrollView results;
 private float pullDownX, pullDownY, pullStartY, pullOffset;
 private int pullDirection;
 private final Button backToSummaryBtn, refreshBtn, switchSoftwareBtn, providerOptionsBtn, dySmsBtn;
 private final WebView[] webs = new WebView[PROVIDER_COUNT];
 private final String[] scripts = new String[PROVIDER_COUNT];
 private final String[] lastSnapshots = new String[PROVIDER_COUNT];
 private final boolean[] providerSignedIn = new boolean[PROVIDER_COUNT];
 private final boolean[] pendingAutoSummary = new boolean[PROVIDER_COUNT];
 private final boolean[] inFlight = new boolean[PROVIDER_COUNT];
 private final int[] generations = new int[PROVIDER_COUNT];
 private final long[] lastDySsoRedirect = new long[1];
 private boolean jdLoginRedirected;
 private static final String DY_DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
 private String dyMobileUa = "";
 private final String[] providerErrors = new String[PROVIDER_COUNT];
 private final JSONArray[] providerOrders = new JSONArray[PROVIDER_COUNT];
 private final JSONArray manualJdlCodes = new JSONArray();
 private final LinkedHashMap<String, JSONArray> taobaoOrderCodes = new LinkedHashMap<>();
 private final List<String[]> dyApiHistory = new CopyOnWriteArrayList<>();

 private int mode = MODE_SUMMARY;
 private boolean dark, paused = false, activeTab = false, destroyed, polling;
 private boolean dyOrderNeedsAuthorization, resettingSfLogin, dySmsLoginOffered;
 private long dyLoginSuccessAt = 0L;
 private volatile int dyNavigationEpoch;
 private String lastSummarySignature = "";
 private final boolean[] pageCommitted = new boolean[PROVIDER_COUNT];
 private FrameLayout loadingOverlay;
 private TextView loadingStatusText;
 private ProgressBar loadingSpinner;
 private final Runnable tick = new Runnable() {
  @Override public void run() {
   polling = false;
   if (paused || !activeTab || destroyed) return;
   sampleAll();
   schedule();
  }
 };

 JdPage(Context context, Consumer<Boolean> session) {
  this(context, session, p -> {}, () -> {});
 }

 JdPage(Context context, Consumer<Boolean> session, IntConsumer externalSwitch, Runnable onModeChanged) {
  super(context);
  this.session = session;
  this.externalSwitch = externalSwitch;
  this.onModeChanged = onModeChanged;
  setOrientation(VERTICAL);
  Arrays.fill(lastSnapshots, "");
  Arrays.fill(providerErrors, "");
  for (int i = 0; i < PROVIDER_COUNT; i++) providerOrders[i] = new JSONArray();
  loadCachedState();

  tools = new LinearLayout(context);
  tools.setGravity(Gravity.CENTER_VERTICAL);
  tools.setPadding(dp(12), dp(6), dp(12), dp(6));

  status = text("自动拉取并汇总已登录软件的取件码；点击左上角可切换软件登录。", 12);
  status.setMaxLines(2);
  status.setEllipsize(android.text.TextUtils.TruncateAt.END);
  tools.addView(status);

  toolButtons = new LinearLayout(context);
  toolButtons.setGravity(Gravity.CENTER_VERTICAL);
  tools.addView(toolButtons);

  backToSummaryBtn = action("‹ 返回汇总", this::showSummary);
  LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-2, dp(34));
  backLp.setMarginEnd(dp(6));
  toolButtons.addView(backToSummaryBtn, backLp);

  switchSoftwareBtn = action("切换软件", () -> externalSwitch.accept(-1));
  LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(-2, dp(34));
  swLp.setMarginEnd(dp(6));
  toolButtons.addView(switchSoftwareBtn, swLp);

  toolSpace = new View(context);
  toolButtons.addView(toolSpace, new LinearLayout.LayoutParams(0, 0, 1f));

  dySmsBtn = action("验证码登录", this::openDySmsLogin);
  LinearLayout.LayoutParams smsLp = new LinearLayout.LayoutParams(-2, dp(34));
  smsLp.setMarginEnd(dp(6));
  toolButtons.addView(dySmsBtn, smsLp);

  refreshBtn = action("刷新全部", this::reloadActiveOrAll);
  toolButtons.addView(refreshBtn, new LinearLayout.LayoutParams(-2, dp(34)));
  providerOptionsBtn = action("更多 ▾", this::showProviderOptions);
  LinearLayout.LayoutParams optionsLp = new LinearLayout.LayoutParams(-2, dp(34));
  optionsLp.setMarginStart(dp(6));
  toolButtons.addView(providerOptionsBtn, optionsLp);
  addView(tools);

  stage = new FrameLayout(context);
  addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

  webContainer = new FrameLayout(context);
  stage.addView(webContainer, new FrameLayout.LayoutParams(-1, -1));

  loadingOverlay = new FrameLayout(context);
  loadingOverlay.setBackgroundColor(AppTheme.bg(dark));
  loadingOverlay.setClickable(true);
  LinearLayout loadingBox = new LinearLayout(context);
  loadingBox.setOrientation(VERTICAL);
  loadingBox.setGravity(Gravity.CENTER);
  loadingSpinner = new ProgressBar(context);
  loadingBox.addView(loadingSpinner, new LinearLayout.LayoutParams(dp(38), dp(38)));
  loadingStatusText = text("\u6b63\u5728\u5b89\u5168\u52a0\u8f7d\u5b98\u65b9\u9875\u9762\u2026", 13);
  loadingStatusText.setGravity(Gravity.CENTER);
  loadingStatusText.setTextColor(AppTheme.muted(dark));
  LinearLayout.LayoutParams lsp = new LinearLayout.LayoutParams(-2, -2);
  lsp.topMargin = dp(12);
  loadingBox.addView(loadingStatusText, lsp);
  loadingOverlay.addView(loadingBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
  loadingOverlay.setVisibility(GONE);
  stage.addView(loadingOverlay, new FrameLayout.LayoutParams(-1, -1));

  cards = new LinearLayout(context);
  cards.setOrientation(VERTICAL);
  cards.setPadding(dp(12), dp(8), dp(12), dp(88));
  results = new ScrollView(context) {
   @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
    if (handlePullTouch(this, ev)) return true;
    return super.dispatchTouchEvent(ev);
   }
   private void cancelSuperTouch(android.view.MotionEvent ev) {
    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(ev);
    cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
    super.dispatchTouchEvent(cancel);
    cancel.recycle();
   }
   private boolean handlePullTouch(ScrollView self, android.view.MotionEvent ev) {
    if (mode != MODE_SUMMARY) return false;
    int action = ev.getActionMasked();
    if (action == android.view.MotionEvent.ACTION_DOWN) {
     pullDownX = ev.getRawX();
     pullDownY = ev.getRawY();
     pullStartY = pullDownY;
     pullDirection = 0;
     pullOffset = 0f;
     super.dispatchTouchEvent(ev);
     return true;
    }
    if (action == android.view.MotionEvent.ACTION_MOVE) {
     float rawX = ev.getRawX();
     float rawY = ev.getRawY();
     float dx = rawX - pullDownX;
     float dy = rawY - pullDownY;
     if (pullDirection == 0) {
      if (dy > 0 && self.canScrollVertically(-1)) {
       pullDownX = rawX;
       pullDownY = rawY;
       return false;
      }
      if (dy < 0) {
       pullDownX = rawX;
       pullDownY = rawY;
       return false;
      }
      if (dy > dp(8) && Math.abs(dy) > Math.abs(dx) * 1.15f && !self.canScrollVertically(-1)) {
       pullDirection = 1;
       pullStartY = rawY - dp(4);
       cancelSuperTouch(ev);
      }
     }
     if (pullDirection == 1) {
      float delta = rawY - pullStartY;
      if (delta >= 0) {
       float maxPull = dp(96);
       pullOffset = Math.min(maxPull, delta * 0.48f);
       cards.setTranslationY(pullOffset);
       boolean ready = pullOffset >= dp(36);
       FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pullBanner.getLayoutParams();
       lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
       lp.bottomMargin = 0;
       lp.topMargin = dp(10);
       pullBanner.setText(ready ? "↓ 松开立即刷新全部取件码" : "↓ 继续下拉刷新取件码");
       pullBanner.setLayoutParams(lp);
       pullBanner.setTextColor(ready ? Color.WHITE : AppTheme.ink(dark));
       pullBanner.setBackground(borderedBg(ready ? AppTheme.ACCENT : AppTheme.surface(dark),
        ready ? AppTheme.ACCENT : AppTheme.border(dark), 999));
       pullBanner.setVisibility(VISIBLE);
       pullBanner.bringToFront();
       if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
       return true;
      } else {
       pullDirection = 0;
       pullOffset = 0f;
       pullDownX = rawX;
       pullDownY = rawY;
       cards.setTranslationY(0f);
       pullBanner.setVisibility(GONE);
      }
     }
     return false;
    }
    if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
     if (pullDirection == 1) {
      boolean shouldTrigger = action == android.view.MotionEvent.ACTION_UP && pullOffset >= dp(36);
      pullDirection = 0;
      pullOffset = 0f;
      cards.animate().translationY(0f).setDuration(180).start();
      pullBanner.setVisibility(GONE);
      cancelSuperTouch(ev);
      if (shouldTrigger) {
       reloadActiveOrAll();
      }
      return true;
     }
    }
    return false;
   }
  };
  results.setFillViewport(true);
  results.setOverScrollMode(View.OVER_SCROLL_NEVER);
  results.addView(cards);
  stage.addView(results, new FrameLayout.LayoutParams(-1, -1));

  pullBanner = text("", 12);
  pullBanner.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
  pullBanner.setGravity(Gravity.CENTER);
  pullBanner.setPadding(dp(16), dp(8), dp(16), dp(8));
  pullBanner.setVisibility(GONE);
  FrameLayout.LayoutParams pullLp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
  pullLp.topMargin = dp(12);
  stage.addView(pullBanner, pullLp);

  postDelayed(this::syncCookiesOnStartup, 400);
  updateModeUi();
  renderSummary(true);
  setDark(false);
 }

 private String getScript(int provider) {
  if (provider < 0 || provider >= PROVIDER_COUNT) return "";
  if (scripts[provider] == null || scripts[provider].isEmpty()) {
   Context ctx = getContext();
   if (provider == PROVIDER_JD) scripts[provider] = loadAsset(ctx, "jd-orders.js");
   else if (provider == PROVIDER_SF) scripts[provider] = loadAsset(ctx, "sf-mobile-layout.js") + "\n" + loadAsset(ctx, "sf-orders.js");
   else if (provider == PROVIDER_DY) scripts[provider] = loadAsset(ctx, "dy-orders.js");
   else if (provider == PROVIDER_JDL) scripts[provider] = loadAsset(ctx, "jdl-orders.js");
  }
  return scripts[provider];
 }

 private void syncCookiesOnStartup() {
  if (destroyed) return;
  try {
   clearLegacyDyFakeCookie();
   getContext().getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE)
    .edit().remove("jd_all_cookies").apply();
   if (!hasDySessionCookie(buildDyCookieHeader())) restoreDySessionCookie();
   if (providerSignedIn[PROVIDER_JD] && CookieManager.getInstance().getCookie(JdUrlPolicy.ORDERS) == null) {
    providerSignedIn[PROVIDER_JD] = false;
    providerOrders[PROVIDER_JD] = new JSONArray();
    renderSummary(false);
   }
   if (providerSignedIn[PROVIDER_SF] && CookieManager.getInstance().getCookie(JdUrlPolicy.SF_ORDERS) == null) {
    providerSignedIn[PROVIDER_SF] = false;
    providerOrders[PROVIDER_SF] = new JSONArray();
    renderSummary(false);
   }
   if (providerSignedIn[PROVIDER_JDL] && CookieManager.getInstance().getCookie(JdUrlPolicy.JDL_ORDERS) == null) {
    providerSignedIn[PROVIDER_JDL] = false;
    providerOrders[PROVIDER_JDL] = new JSONArray();
    renderSummary(false);
   }
   if (providerSignedIn[PROVIDER_DY] && !hasDyWebSessionCookie() && !hasDySessionCookie(buildDyCookieHeader())) {
    providerSignedIn[PROVIDER_DY] = false;
    providerOrders[PROVIDER_DY] = new JSONArray();
    renderSummary(false);
   }
  } catch (Exception ignored) {}
 }

 private void loadCachedState() {
  try {
   clearLegacyDyFakeCookie();
   SharedPreferences prefs = getContext().getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE);
   if (prefs.contains("sf_login_user") || prefs.contains("sf_all_cookies")) {
    prefs.edit().remove("sf_login_user").remove("sf_all_cookies").apply();
   }
   if (prefs.contains("cn_ready") || prefs.contains("cn_barcode") || prefs.contains("signed_" + LEGACY_CN_SLOT)) {
    prefs.edit().remove("cn_phone").remove("cn_expiry").remove("cn_barcode").remove("cn_ready")
     .remove("signed_" + LEGACY_CN_SLOT).remove("orders_" + LEGACY_CN_SLOT).apply();
   }
   for (int p = 0; p < PROVIDER_COUNT; p++) {
    if (p == LEGACY_CN_SLOT) continue;
    providerSignedIn[p] = prefs.getBoolean("signed_" + p, false);
    String raw = prefs.getString("orders_" + p, "[]");
    try {
     providerOrders[p] = new JSONArray(raw);
    } catch (JSONException e) {
     providerOrders[p] = new JSONArray();
    }
   }
   JSONArray savedCodes = new JSONArray(prefs.getString(PREF_MANUAL_JDL_CODES, "[]"));
   for (int i = 0; i < savedCodes.length(); i++) {
    JSONObject row = savedCodes.optJSONObject(i);
    if (row != null && isPickupCode(row.optString("code"))) manualJdlCodes.put(row);
   }
   JSONArray savedTaobaoOrders = new JSONArray(prefs.getString(PREF_TAOBAO_ORDER_CODES, "[]"));
   for (int i = 0; i < savedTaobaoOrders.length() && i < MAX_TAOBAO_ORDERS; i++) {
    JSONObject entry = savedTaobaoOrders.optJSONObject(i);
    if (entry == null) continue;
    String orderKey = entry.optString("orderKey");
    JSONArray codes = validTaobaoOrderCodes(entry.optJSONArray("codes"));
    if (validTaobaoOrderKey(orderKey) && codes.length() > 0) taobaoOrderCodes.put(orderKey, codes);
   }
  } catch (Exception ignored) {}
 }

 private void clearLegacyDyFakeCookie() {
  try {
   CookieManager cm = CookieManager.getInstance();
   cm.setCookie("https://ec.snssdk.com", "hc_dy_logged=; Path=/; Max-Age=0; Secure; SameSite=None");
   cm.setCookie("https://is.snssdk.com", "hc_dy_logged=; Path=/; Max-Age=0; Secure; SameSite=None");
   cm.setCookie("https://is.snssdk.com", "hc_dy_logged=; Path=/; Domain=.snssdk.com; Max-Age=0; Secure; SameSite=None");
   cm.setCookie("https://haohuo.jinritemai.com", "hc_dy_logged=; Path=/; Max-Age=0; Secure; SameSite=None");
  } catch (Exception ignored) {}
 }

 private void clearAllDySessionCookies() {
  try {
   CookieManager cm = CookieManager.getInstance();
   String[] keys = {"sessionid", "sessionid_ss", "sid_tt", "uid_tt", "uid_tt_ss", "sid_guard", "sid_ucp_v1", "ssid_ucp_v1", "X-Xiaodian-Token"};
   for (String k : keys) {
    cm.setCookie("https://www.douyin.com", k + "=; Path=/; Max-Age=0; Secure; SameSite=None");
    cm.setCookie("https://www.douyin.com", k + "=; Path=/; Domain=.douyin.com; Max-Age=0; Secure; SameSite=None");
    cm.setCookie("https://sso.douyin.com", k + "=; Path=/; Max-Age=0; Secure; SameSite=None");
    cm.setCookie("https://ec.snssdk.com", k + "=; Path=/; Max-Age=0; Secure; SameSite=None");
    cm.setCookie("https://is.snssdk.com", k + "=; Path=/; Max-Age=0; Secure; SameSite=None");
    cm.setCookie("https://is.snssdk.com", k + "=; Path=/; Domain=.snssdk.com; Max-Age=0; Secure; SameSite=None");
    cm.setCookie("https://haohuo.jinritemai.com", k + "=; Path=/; Max-Age=0; Secure; SameSite=None");
    cm.setCookie("https://haohuo.jinritemai.com", k + "=; Path=/; Domain=.jinritemai.com; Max-Age=0; Secure; SameSite=None");
   }
  } catch (Exception ignored) {}
 }

 private static boolean samePagePath(String a, String b) {
  if (a == null || b == null) return false;
  if (a.equals(b)) return true;
  try {
   android.net.Uri ua = android.net.Uri.parse(a);
   android.net.Uri ub = android.net.Uri.parse(b);
   String sa = ua.getScheme(), sb = ub.getScheme();
   String ha = ua.getHost(), hb = ub.getHost();
   String pa = ua.getPath(), pb = ub.getPath();
   if (pa == null || pa.isEmpty()) pa = "/";
   if (pb == null || pb.isEmpty()) pb = "/";
   return sa != null && sa.equalsIgnoreCase(sb)
    && ha != null && ha.equalsIgnoreCase(hb)
    && pa.equals(pb);
  } catch (Exception e) {
   return false;
  }
 }

 private void clearStaleSfCookies() {
  try {
   CookieManager cm = CookieManager.getInstance();
   String url = "https://www.sf-express.com";
   String[] keys = {"loginUser", "remember-me", "SESSION", "sessionId", "token"};
   for (String k : keys) {
    cm.setCookie(url, k + "=; Path=/; Max-Age=0");
    cm.setCookie(url, k + "=; Path=/; Secure; Max-Age=0");
    cm.setCookie(url, k + "=; Path=/; Domain=www.sf-express.com; Max-Age=0");
    cm.setCookie(url, k + "=; Path=/; Domain=www.sf-express.com; Secure; Max-Age=0");
    cm.setCookie(url, k + "=; Path=/; Domain=.sf-express.com; Max-Age=0");
    cm.setCookie(url, k + "=; Path=/; Domain=.sf-express.com; Secure; Max-Age=0");
   }
   cm.flush();
  } catch (Exception ignored) {}
 }

 private void saveCachedState() {
  try {
   SharedPreferences.Editor ed = getContext().getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE).edit();
   for (int p = 0; p < PROVIDER_COUNT; p++) {
    if (p == LEGACY_CN_SLOT) continue;
    ed.putBoolean("signed_" + p, providerSignedIn[p]);
    ed.putString("orders_" + p, providerOrders[p] != null ? providerOrders[p].toString() : "[]");
   }
   ed.apply();
  } catch (Exception ignored) {}
   if (providerSignedIn[PROVIDER_DY]) {
    persistDySessionCookie();
   } else {
    clearDySavedCookies();
   }
 }

 private String loadAsset(Context context, String name) {
  try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
   byte[] b = new byte[4096];
   int n;
   while ((n = in.read(b)) != -1) out.write(b, 0, n);
   return new String(out.toByteArray(), StandardCharsets.UTF_8);
  } catch (IOException e) {
   return "";
  }
 }

 private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }

 private TextView text(String s, int size) {
  TextView t = new TextView(getContext());
  t.setText(s);
  t.setTextSize(size);
  return t;
 }

 private Button action(String s, Runnable run) {
  Button b = new Button(getContext());
  b.setText(s);
  b.setAllCaps(false);
  b.setTextSize(12);
  b.setMinWidth(0);
  b.setMinimumWidth(0);
  b.setMinHeight(0);
  b.setMinimumHeight(0);
  b.setPadding(dp(10), 0, dp(10), 0);
  b.setOnClickListener(v -> run.run());
  return b;
 }

 private GradientDrawable roundBg(int color, int radiusDp) {
  GradientDrawable d = new GradientDrawable();
  d.setColor(color);
  d.setCornerRadius(dp(radiusDp));
  return d;
 }

 private GradientDrawable borderedBg(int fill, int stroke, int radiusDp) {
  GradientDrawable d = new GradientDrawable();
  d.setColor(fill);
  d.setCornerRadius(dp(radiusDp));
  d.setStroke(dp(1), stroke);
  return d;
 }

 void setDark(boolean value) {
  dark = value;
  setBackgroundColor(AppTheme.bg(dark));
  tools.setBackgroundColor(AppTheme.surface(dark));
  status.setTextColor(AppTheme.muted(dark));
  for (Button b : new Button[]{backToSummaryBtn, switchSoftwareBtn, refreshBtn, providerOptionsBtn, dySmsBtn}) {
   b.setTextColor(b == backToSummaryBtn ? Color.WHITE : AppTheme.ink(dark));
   b.setBackground(roundBg(b == backToSummaryBtn ? AppTheme.ACCENT : AppTheme.subtleFill(dark), 10));
  }
  results.setBackgroundColor(AppTheme.bg(dark));
  for (WebView w : webs) if (w != null) w.setBackgroundColor(AppTheme.bg(dark));
  if (loadingOverlay != null) {
   loadingOverlay.setBackgroundColor(AppTheme.bg(dark));
   if (loadingStatusText != null) loadingStatusText.setTextColor(AppTheme.muted(dark));
  }
  renderSummary(true);
 }

 private static boolean isWebProvider(int provider) {
  return provider >= PROVIDER_JD && provider < PROVIDER_COUNT && provider != LEGACY_CN_SLOT;
 }

 int getMode() { return mode; }

 boolean isSignedIn(int provider) {
  return provider >= 0 && provider < PROVIDER_COUNT && providerSignedIn[provider];
 }

 boolean isPlatformSignedIn(int provider) {
  return isSignedIn(provider) || (provider == PROVIDER_JD && isSignedIn(PROVIDER_JDL));
 }

 int countForPlatform(int provider) {
  if (provider == PROVIDER_TB) return taobaoPickupRows().size();
  return countFor(provider) + (provider == PROVIDER_JD ? countFor(PROVIDER_JDL) : 0);
 }

 boolean isAnyManagedSignedIn() {
  if (mode >= PROVIDER_JD && mode < PROVIDER_COUNT) return providerSignedIn[mode];
  for (int provider = PROVIDER_JD; provider < PROVIDER_COUNT; provider++) {
   if (providerSignedIn[provider]) return true;
  }
  return false;
 }

 int countFor(int provider) {
  if (provider < 0 || provider >= PROVIDER_COUNT || !providerSignedIn[provider]) return 0;
  JSONArray arr = providerOrders[provider];
  if (arr == null) return 0;
  int count = 0;
  for (int i = 0; i < arr.length(); i++) {
   JSONObject o = arr.optJSONObject(i);
   if (o != null && !o.optString("code").isEmpty()) count++;
  }
  return count;
 }

 void updateExternalParcels(int provider, boolean signedIn, JSONArray orders) {
  if (provider != PROVIDER_TB && provider != PROVIDER_PDD) return;
  boolean changed = providerSignedIn[provider] != signedIn;
  providerSignedIn[provider] = signedIn;
  if (!signedIn || orders != null) {
   JSONArray next = signedIn ? orders : new JSONArray();
   String prevStr = providerOrders[provider] != null ? providerOrders[provider].toString() : "[]";
   if (!prevStr.equals(next.toString())) {
    providerOrders[provider] = next;
    changed = true;
   }
  }
  if (changed) {
   saveCachedState();
   notifySessionState();
   renderSummary(false);
  }
 }

 private void notifySessionState() {
  session.accept(isAnyManagedSignedIn());
 }

 void warmBackground() {
  if (destroyed) return;
  paused = false;
  warmBackgroundProgressive();
 }

 void warmBackgroundProgressive() {
  if (destroyed || paused) return;
  postDelayed(() -> {
   if (destroyed || paused) return;
   if (providerSignedIn[PROVIDER_JD]) ensureWeb(PROVIDER_JD);
  }, 200);
  postDelayed(() -> {
   if (destroyed || paused) return;
   if (providerSignedIn[PROVIDER_SF]) ensureWeb(PROVIDER_SF);
  }, 600);
  postDelayed(() -> {
   if (destroyed || paused) return;
   if (providerSignedIn[PROVIDER_DY]) ensureWeb(PROVIDER_DY);
  }, 1000);
  postDelayed(() -> {
   if (destroyed || paused) return;
   if (providerSignedIn[PROVIDER_JDL]) ensureWeb(PROVIDER_JDL);
  }, 1250);
 }

 void showPage() {
  if (destroyed) return;
  paused = false;
  activeTab = true;
  for (int p = PROVIDER_JD; p < PROVIDER_COUNT; p++) {
   if (webs[p] != null) webs[p].onResume();
  }
  updateModeUi();
  if (mode != MODE_SUMMARY) {
   ensureWeb(mode);
  } else {
   warmBackgroundProgressive();
  }
  sampleAll();
  schedule();
 }

 void openProvider(int provider) {
  if (destroyed) return;
  if (provider == PROVIDER_TB || provider == PROVIDER_PDD) {
   externalSwitch.accept(provider);
   return;
  }
  if (!isWebProvider(provider)) {
   showSummary();
   return;
  }
  mode = provider;
  paused = false;
  activeTab = true;
  if (provider == PROVIDER_JD) jdLoginRedirected = false;
  pendingAutoSummary[provider] = provider != PROVIDER_SF && provider != PROVIDER_JDL && !providerSignedIn[provider];
  ensureWeb(provider);
  if (webs[provider] != null) {
   webs[provider].onResume();
   String curUrl = webs[provider].getUrl();
   if (provider == PROVIDER_JD) {
    if (!providerErrors[PROVIDER_JD].isEmpty()) reloadProvider(PROVIDER_JD);
   } else if (provider == PROVIDER_DY) {
    if (!providerSignedIn[PROVIDER_DY] && !dyOrderNeedsAuthorization && !hasDyAccountSession()) {
     if (!JdUrlPolicy.isDyLoginPage(curUrl)) {
      loadDyUrl(webs[PROVIDER_DY], JdUrlPolicy.DY_LOGIN);
     }
    } else if (curUrl == null || curUrl.isEmpty() || "about:blank".equals(curUrl)) {
     loadDyUrl(webs[PROVIDER_DY], JdUrlPolicy.DY_ORDERS);
    }
   }
   webs[provider].requestFocus();
  }
  updateModeUi();
  onModeChanged.run();
  sampleProvider(provider);
 }

 void showSummary() {
  mode = MODE_SUMMARY;
  updateModeUi();
  renderSummary(false);
  onModeChanged.run();
  sampleAll();
 }

 private void updateModeUi() {
  boolean summary = mode == MODE_SUMMARY;
  tools.setOrientation(summary ? HORIZONTAL : VERTICAL);
  LinearLayout.LayoutParams statusLp = summary ? new LinearLayout.LayoutParams(0, -2, 1f)
   : new LinearLayout.LayoutParams(-1, -2);
  if (summary) statusLp.setMarginEnd(dp(8));
  else statusLp.bottomMargin = dp(6);
  status.setLayoutParams(statusLp);
  toolButtons.setLayoutParams(new LinearLayout.LayoutParams(summary ? -2 : -1, -2));
  toolSpace.setVisibility(summary ? GONE : VISIBLE);
  updateDySmsAction();
  providerOptionsBtn.setVisibility(mode == PROVIDER_SF || mode == PROVIDER_JDL ? VISIBLE : GONE);
  refreshBtn.setEnabled(mode != PROVIDER_SF || !resettingSfLogin);
  if (mode == MODE_SUMMARY) {
   results.setVisibility(VISIBLE);
   backToSummaryBtn.setVisibility(GONE);
   switchSoftwareBtn.setVisibility(VISIBLE);
   refreshBtn.setText("刷新全部");
   for (int p = PROVIDER_JD; p < PROVIDER_COUNT; p++) {
    if (webs[p] != null) webs[p].setVisibility(INVISIBLE);
   }
   if (loadingOverlay != null) loadingOverlay.setVisibility(GONE);
   updateSummaryStatusText();
  } else {
   results.setVisibility(GONE);
   backToSummaryBtn.setVisibility(VISIBLE);
   switchSoftwareBtn.setVisibility(GONE);
   refreshBtn.setText("刷新网页");
   for (int p = PROVIDER_JD; p < PROVIDER_COUNT; p++) {
    if (webs[p] != null) webs[p].setVisibility(p == mode ? VISIBLE : INVISIBLE);
   }
   if (loadingOverlay != null) {
    if (!pageCommitted[mode]) {
     loadingOverlay.setAlpha(1f);
     loadingOverlay.setVisibility(VISIBLE);
     if (loadingStatusText != null) {
      loadingStatusText.setText("\u6b63\u5728\u52a0\u8f7d " + PROVIDER_NAMES[mode] + " \u5b98\u65b9\u5b89\u5168\u9875\u9762\u2026");
     }
    } else {
     loadingOverlay.setVisibility(GONE);
    }
   }
   if (!providerErrors[mode].isEmpty()) {
    status.setText(providerErrors[mode]);
   } else if (providerSignedIn[mode]) {
    status.setText(signedInStatus(mode));
   } else {
    status.setText(loginHint(mode));
   }
  }
  notifySessionState();
 }

 private String signedInStatus(int provider) {
  return "已登录" + PROVIDER_NAMES[provider] + " · " + countFor(provider) + " 个取件码";
 }

 private String loginHint(int provider) {
  if (provider == PROVIDER_JDL) return "登录后可在「我收的」查件，也可从「更多」打开浏览器。";
  if (provider == PROVIDER_SF) return resettingSfLogin ? "正在清理顺丰的登录数据…"
   : "请在顺丰官网登录。验证码报错时，可从「更多」重置登录。";
  if (provider == PROVIDER_DY) return dySmsLoginOffered ? "可使用抖音官网的验证码登录。"
   : "请在抖音官网选择登录方式。";
  return "请在" + PROVIDER_NAMES[provider] + "官方页面完成登录，登录完后将自动拉取并汇总。";
 }

 private void updateDySmsAction() {
  dySmsBtn.setVisibility(mode == PROVIDER_DY && dySmsLoginOffered ? VISIBLE : GONE);
 }

 private void openDySmsLogin() {
  WebView web = webs[PROVIDER_DY];
  if (mode != PROVIDER_DY || !dySmsLoginOffered || web == null || !JdUrlPolicy.isDyLoginPage(web.getUrl())) return;
  web.evaluateJavascript("window.HandyDyOrders && HandyDyOrders.openSmsLogin()", null);
 }

 private String entryUrl(int provider) {
  if (provider == PROVIDER_JD) return JdUrlPolicy.ORDERS;
  if (provider == PROVIDER_SF) return JdUrlPolicy.SF_ORDERS;
  if (provider == PROVIDER_JDL) return JdUrlPolicy.JDL_ORDERS;
  return JdUrlPolicy.DY_ORDERS;
 }

 private void openProviderInBrowser() {
  if (mode != PROVIDER_SF && mode != PROVIDER_JDL) return;
  try {
   android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW,
    android.net.Uri.parse(entryUrl(mode)));
   intent.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
   getContext().startActivity(intent);
  } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
   Toast.makeText(getContext(), "未找到可打开官网的浏览器", Toast.LENGTH_SHORT).show();
  }
 }

 private void showProviderOptions() {
  if (mode != PROVIDER_SF && mode != PROVIDER_JDL) return;
  PopupMenu popup = new PopupMenu(getContext(), providerOptionsBtn);
  popup.getMenu().add(0, 1, 0, "在浏览器打开");
  if (mode == PROVIDER_SF) popup.getMenu().add(0, 2, 1, "重置顺丰登录").setEnabled(!resettingSfLogin);
  popup.setOnMenuItemClickListener(item -> {
   if (item.getItemId() == 1) openProviderInBrowser();
   if (item.getItemId() == 2) showSfLoginReset();
   return true;
  });
  popup.show();
 }

 private void showSfLoginReset() {
  if (destroyed || mode != PROVIDER_SF || resettingSfLogin || SiteDataClearer.isBusy()) return;
  new AlertDialog.Builder(getContext())
   .setTitle("重新登录顺丰")
   .setMessage("清理 HandyCode 内的顺丰登录数据，然后重新打开顺丰官网。")
   .setNegativeButton("取消", null)
   .setPositiveButton("清理并打开", (dialog, which) -> resetSfLogin())
   .show();
 }

 private void resetSfLogin() {
  if (destroyed || resettingSfLogin || SiteDataClearer.isBusy()) return;
  resettingSfLogin = true;
  if (mode == PROVIDER_SF) refreshBtn.setEnabled(false);
  resetProviderSession(PROVIDER_SF);
  if (mode == PROVIDER_SF) status.setText(loginHint(PROVIDER_SF));
  if (!SiteDataClearer.isSupported()) {
   finishSfLoginReset(false);
   return;
  }
  SiteDataClearer.clearSites(LogoutSites.forPickupProvider(PROVIDER_SF),
   () -> finishSfLoginReset(true), error -> finishSfLoginReset(false));
 }

 private void finishSfLoginReset(boolean clearedSite) {
  if (destroyed) return;
  resettingSfLogin = false;
  refreshBtn.setEnabled(true);
  if (mode == PROVIDER_SF && activeTab && !paused) openProvider(PROVIDER_SF);
  if (!clearedSite) Toast.makeText(getContext(),
   "未能完整清理顺丰的网页登录数据。可更新系统 WebView 后重试。", Toast.LENGTH_LONG).show();
 }

 private void loadDyUrl(WebView web, String url) {
  if (web == null || url == null) return;
  boolean loginPage = JdUrlPolicy.isDyLoginPage(url);
  String targetUa = loginPage ? DY_DESKTOP_UA : (dyMobileUa != null && !dyMobileUa.isEmpty() ? dyMobileUa : DY_DESKTOP_UA);
  WebSettings ws = web.getSettings();
  ws.setUseWideViewPort(!loginPage);
  ws.setLoadWithOverviewMode(!loginPage);
  if (!targetUa.equals(ws.getUserAgentString())) {
   ws.setUserAgentString(targetUa);
  }
  web.loadUrl(url);
 }

 private void loadProviderEntry(int provider, WebView web) {
  if (web == null) return;
  if (provider == PROVIDER_DY) {
   String cur = web.getUrl();
   boolean onDyLogin = JdUrlPolicy.isDyLoginPage(cur);
   boolean signed = providerSignedIn[PROVIDER_DY] || hasDySessionCookie(buildDyCookieHeader());
   if (onDyLogin) {
    web.reload();
   } else if (!signed && mode == PROVIDER_DY) {
    loadDyUrl(web, JdUrlPolicy.DY_LOGIN);
   } else {
    loadDyUrl(web, JdUrlPolicy.DY_ORDERS);
   }
  } else {
   web.loadUrl(entryUrl(provider));
  }
 }

 @SuppressWarnings({"rawtypes", "unchecked"})
 private void configureModernWebSettings(WebSettings s) {
  try {
   InvocationHandler handler = WebViewGlueCommunicator.getFactory()
    .getWebkitToCompatConverter().convertSettings(s);
   WebSettingsBoundaryInterface boundary =
    (WebSettingsBoundaryInterface) BoundaryInterfaceReflectionUtil.castToSuppLibClass(
     WebSettingsBoundaryInterface.class, handler);
   if (boundary == null) return;
   if (WebViewFeature.isFeatureSupported("REQUESTED_WITH_HEADER_ALLOW_LIST")) {
    boundary.setRequestedWithHeaderOriginAllowList(Collections.emptySet());
   }
   if (WebViewFeature.isFeatureSupported("USER_AGENT_METADATA")) {
    Map cur = boundary.getUserAgentMetadataMap();
    if (cur != null) {
     Map<String, Object> patched = new HashMap<>();
     for (Object k : cur.keySet()) {
      if (k instanceof String) patched.put((String) k, cur.get(k));
     }
     Object bvl = patched.get("BRAND_VERSION_LIST");
     if (bvl instanceof String[][]) {
      String[][] orig = (String[][]) bvl;
      String[][] copy = new String[orig.length][];
      for (int i = 0; i < orig.length; i++) {
       if (orig[i] != null && orig[i].length >= 1 && "Android WebView".equalsIgnoreCase(orig[i][0])) {
        copy[i] = orig[i].clone();
        copy[i][0] = "Google Chrome";
       } else {
        copy[i] = orig[i];
       }
      }
      patched.put("BRAND_VERSION_LIST", copy);
      boundary.setUserAgentMetadataFromMap(patched);
     }
    }
   }
  } catch (Throwable ignored) {}
 }

 private boolean allowedUrl(int provider, String url) {
  if (provider == PROVIDER_JD) return JdUrlPolicy.allowed(url);
  if (provider == PROVIDER_SF) return JdUrlPolicy.allowedSf(url);
  if (provider == PROVIDER_JDL) return JdUrlPolicy.allowedJdlOrders(url);
  return JdUrlPolicy.allowedDy(url);
 }

 private void ensureWeb(final int provider) {
  String script = getScript(provider);
  if (destroyed || webs[provider] != null || script == null || script.isEmpty()) return;
  if (provider == PROVIDER_SF && resettingSfLogin) return;
  WebView web = new WebView(getContext());
  webs[provider] = web;
  web.setBackgroundColor(AppTheme.bg(dark));
  WebSettings s = web.getSettings();
  s.setJavaScriptEnabled(true);
  s.setDomStorageEnabled(true);
  s.setAllowFileAccess(false);
  s.setAllowContentAccess(false);
  s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
  s.setSafeBrowsingEnabled(true);
  s.setSupportZoom(true);
  s.setBuiltInZoomControls(true);
  s.setDisplayZoomControls(false);
  s.setUseWideViewPort(true);
  s.setLoadWithOverviewMode(true);
  s.setJavaScriptCanOpenWindowsAutomatically(false);
  s.setSupportMultipleWindows(false);
  if (provider == PROVIDER_JD) s.setTextZoom(100);
  if (provider == PROVIDER_DY) {
   String defaultUa = s.getUserAgentString();
   if (defaultUa != null && !defaultUa.isEmpty()) {
    String cleanUa = defaultUa.replace("; wv)", ")").replace("; wv;", ";").replace("Version/4.0 ", "");
    s.setUserAgentString(cleanUa);
    if (provider == PROVIDER_DY) dyMobileUa = cleanUa;
   }
   configureModernWebSettings(s);
  }
  CookieManager.getInstance().setAcceptCookie(true);
  CookieManager.getInstance().setAcceptThirdPartyCookies(web, provider != PROVIDER_JD);
  boolean installJdEarly = false;
  if (provider == PROVIDER_JD && WebViewFeature.isFeatureSupported("DOCUMENT_START_SCRIPT")) {
   WebViewCompat.addDocumentStartJavaScript(web, script, JD_ORDER_ORIGINS);
   installJdEarly = true;
  }
  final boolean jdDocumentStart = installJdEarly;
  web.setWebChromeClient(new WebChromeClient() {
   @Override public void onPermissionRequest(PermissionRequest r) { r.deny(); }
   @Override public boolean onConsoleMessage(ConsoleMessage cm) {
    if (provider == PROVIDER_DY && cm != null && "__HANDY_DY_LOGIN_SUCCESS__".equals(cm.message())) {
     onDySsoLoginSuccess();
     return true;
    }
    return super.onConsoleMessage(cm);
   }
  });
  web.setWebViewClient(new WebViewClient() {
   @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
    if (r == null || r.getUrl() == null) return true;
    String u = r.getUrl().toString();
    if (!r.isForMainFrame()) {
     return !(u.startsWith("https://") || u.startsWith("about:"));
    }
    if (provider == PROVIDER_DY && (u.startsWith("snssdk") || u.startsWith("aweme")
      || u.contains("douyin.com/home") || u.contains("douyin.com/downloadpage"))) {
     redirectToDyLoginIfNeeded(v);
     return true;
    }
    return !allowedUrl(provider, u);
   }
   @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
    if (provider == PROVIDER_DY && url != null && (url.startsWith("snssdk") || url.startsWith("aweme")
      || url.contains("douyin.com/home") || url.contains("douyin.com/downloadpage"))) {
     redirectToDyLoginIfNeeded(v);
     return true;
    }
    return !allowedUrl(provider, url);
   }
   @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
    if (destroyed || webs[provider] != v) return;
    generations[provider]++;
    inFlight[provider] = false;
    pageCommitted[provider] = false;
    if (mode == provider && loadingOverlay != null) {
     loadingOverlay.setAlpha(1f);
     loadingOverlay.setVisibility(VISIBLE);
     if (loadingStatusText != null) {
      loadingStatusText.setText("\u6b63\u5728\u52a0\u8f7d " + PROVIDER_NAMES[provider] + " \u5b98\u65b9\u5b89\u5168\u9875\u9762\u2026");
     }
    }
    if (provider == PROVIDER_DY) {
     invalidateDyApiHistory();
     dySmsLoginOffered = false;
     updateDySmsAction();
    }
    if (!allowedUrl(provider, url)) {
     v.stopLoading();
     if (mode == provider) status.setText("已拦截非官方安全链接。");
     return;
    }
    if (provider == PROVIDER_JD && !jdDocumentStart) v.evaluateJavascript(script, null);
   }
   @Override public void onPageCommitVisible(WebView v, String url) {
    if (destroyed || webs[provider] != v) return;
    pageCommitted[provider] = true;
    if (mode == provider && loadingOverlay != null && loadingOverlay.getVisibility() == VISIBLE) {
     loadingOverlay.animate().alpha(0f).setDuration(160).withEndAction(() -> loadingOverlay.setVisibility(GONE)).start();
    }
    if (allowedUrl(provider, url)) {
     v.evaluateJavascript(scripts[provider], null);
    }
   }
   @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
    if (provider == PROVIDER_DY && r != null && !r.isForMainFrame() && r.getUrl() != null) {
     WebResourceResponse resp = interceptDyEcRequest(r);
     if (resp != null) return resp;
    }
    return super.shouldInterceptRequest(v, r);
   }
   @Override public void onPageFinished(WebView v, String url) {
    if (destroyed || webs[provider] != v) return;
    pageCommitted[provider] = true;
    if (mode == provider && loadingOverlay != null && loadingOverlay.getVisibility() == VISIBLE) {
     loadingOverlay.animate().alpha(0f).setDuration(160).withEndAction(() -> loadingOverlay.setVisibility(GONE)).start();
    }
    CookieManager.getInstance().flush();
    if (allowedUrl(provider, url)) {
     v.evaluateJavascript(buildProviderEvalScript(provider), null);
     sampleProvider(provider);
     postDelayed(() -> sampleProvider(provider), 900);
     postDelayed(() -> sampleProvider(provider), 2200);
    }
   }
   @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
    h.cancel();
    if (provider == PROVIDER_JD && webs[provider] == v && Objects.equals(v.getUrl(), e.getUrl())) {
     showJdOrderProblem("京东安全连接验证失败，请检查网络或手机日期。");
    }
   }
   @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
    if (provider == PROVIDER_JD && webs[provider] == v && request.isForMainFrame()) {
     showJdOrderProblem("京东订单页暂时无法连接，请稍后刷新。");
    }
   }
   @Override public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
    if (provider == PROVIDER_JD && webs[provider] == v && request.isForMainFrame()) {
     showJdOrderProblem(response.getStatusCode() == 429 ? "京东订单页当前繁忙，请稍后刷新。"
      : "京东订单页暂时无法加载，请稍后刷新。");
    }
   }
  });
  web.setVisibility(mode == provider ? VISIBLE : INVISIBLE);
  webContainer.addView(web, new FrameLayout.LayoutParams(-1, -1));
  loadProviderEntry(provider, web);
 }

 private void onDySsoLoginSuccess() {
  post(() -> {
   WebView web = webs[PROVIDER_DY];
   if (destroyed || web == null || !JdUrlPolicy.isDyLoginPage(web.getUrl())
       || !hasDySessionCookie(buildDyCookieHeader())) return;
   long now = android.os.SystemClock.elapsedRealtime();
   if (dyLoginSuccessAt > 0 && now - dyLoginSuccessAt < 3000) return;
   dyLoginSuccessAt = now;
   dyOrderNeedsAuthorization = false;
   invalidateDyApiHistory();
   syncDyCookiesAcrossDomains();
   CookieManager.getInstance().flush();
   providerErrors[PROVIDER_DY] = "";
   pendingAutoSummary[PROVIDER_DY] = true;
   if (mode == PROVIDER_DY) {
    status.setText("已完成抖音账号验证，正在拉取抖音订单确认登录状态…");
   }
   loadDyUrl(web, JdUrlPolicy.DY_ORDERS);
  });
 }

 private void onDySessionExpired() {
  if (destroyed) return;
  if (hasDyAccountSession()) {
   // The order endpoint does not establish whether the Douyin account itself signed out.
   dyOrderNeedsAuthorization = true;
   providerErrors[PROVIDER_DY] = DY_ORDER_LOGIN_HINT;
   if (mode == PROVIDER_DY) status.setText(DY_ORDER_LOGIN_HINT);
   return;
  }
  dyLoginSuccessAt = 0L;
  dyOrderNeedsAuthorization = false;
  invalidateDyApiHistory();
  clearDySavedCookies();
  boolean wasSigned = providerSignedIn[PROVIDER_DY];
  providerSignedIn[PROVIDER_DY] = false;
  providerErrors[PROVIDER_DY] = "";
  providerOrders[PROVIDER_DY] = new JSONArray();
  pendingAutoSummary[PROVIDER_DY] = true;
  if (wasSigned) {
   saveCachedState();
   notifySessionState();
   renderSummary(false);
  }
  if (mode == PROVIDER_DY && webs[PROVIDER_DY] != null) {
   status.setText("请在抖音官方页面完成登录，登录完后将自动拉取并汇总。");
   notifySessionState();
   redirectToDyLoginIfNeeded(webs[PROVIDER_DY]);
  }
 }

 private void syncDyCookiesAcrossDomains() {
  CookieManager cm = CookieManager.getInstance();
  String[] sources = {"https://www.douyin.com", "https://sso.douyin.com", "https://ec.snssdk.com"};
  String[] targets = {"https://ec.snssdk.com", "https://haohuo.jinritemai.com"};
  for (String src : sources) {
   String raw = cm.getCookie(src);
   if (raw == null || raw.isEmpty()) continue;
   for (String part : raw.split(";")) {
    String kv = part.trim();
    int eq = kv.indexOf('=');
    if (eq <= 0) continue;
    String k = kv.substring(0, eq).trim();
    String val = kv.substring(eq + 1).trim();
    if ("hc_dy_logged".equalsIgnoreCase(k) || val.isEmpty() || "deleted".equalsIgnoreCase(val) || "null".equalsIgnoreCase(val)) continue;
    for (String dst : targets) {
     cm.setCookie(dst, kv + "; Path=/; Secure; SameSite=None");
     cm.setCookie(dst, kv + "; Path=/; Domain=.jinritemai.com; Secure; SameSite=None");
     cm.setCookie(dst, kv + "; Path=/; Domain=.snssdk.com; Secure; SameSite=None");
    }
   }
  }
 }

 private void redirectToJdLoginIfNeeded(WebView v) {
  if (jdLoginRedirected || JdUrlPolicy.isMallLoginPage(v.getUrl())) return;
  jdLoginRedirected = true;
  pendingAutoSummary[PROVIDER_JD] = true;
  v.loadUrl(JdUrlPolicy.LOGIN);
 }

 private void redirectToDyLoginIfNeeded(WebView v) {
  if (v == null) return;
  String cur = v.getUrl();
  if (JdUrlPolicy.isDyLoginPage(cur)) return;
  long now = android.os.SystemClock.elapsedRealtime();
  if (now - lastDySsoRedirect[0] < 4000) return;
  lastDySsoRedirect[0] = now;
  invalidateDyApiHistory();
  pendingAutoSummary[PROVIDER_DY] = true;
  loadDyUrl(v, JdUrlPolicy.DY_LOGIN);
 }

 private String buildProviderEvalScript(int provider) {
  if (provider != PROVIDER_DY || dyApiHistory.isEmpty()) return scripts[provider];
  StringBuilder sb = new StringBuilder();
  sb.append("(function(){var r=").append(scripts[provider]).append(";");
  sb.append("if(window.HandyDyOrders&&window.HandyDyOrders.ingest){");
  for (String[] entry : dyApiHistory) {
   sb.append("window.HandyDyOrders.ingest(")
     .append(JSONObject.quote(entry[1])).append(",")
     .append(JSONObject.quote(entry[0])).append(");");
  }
  sb.append("return window.HandyDyOrders.snapshot();}");
  sb.append("return r;})()");
  return sb.toString();
 }

 private String buildDyCookieHeader() {
  CookieManager cm = CookieManager.getInstance();
  String[] domains = {
   "https://www.douyin.com",
   "https://sso.douyin.com",
   "https://ec.snssdk.com",
   "https://haohuo.jinritemai.com",
   "https://is.snssdk.com"
  };
  Map<String, String> map = new LinkedHashMap<>();
  for (String d : domains) {
   String raw = cm.getCookie(d);
   if (raw == null || raw.isEmpty()) continue;
   for (String part : raw.split(";")) {
    String kv = part.trim();
    int eq = kv.indexOf('=');
    if (eq > 0) {
     String k = kv.substring(0, eq).trim();
     String val = kv.substring(eq + 1).trim();
     if ("hc_dy_logged".equalsIgnoreCase(k) || val.isEmpty() || "deleted".equalsIgnoreCase(val) || "null".equalsIgnoreCase(val)) continue;
     if (!map.containsKey(k)) map.put(k, kv);
    }
   }
  }
  StringBuilder sb = new StringBuilder();
  for (String kv : map.values()) {
   if (sb.length() > 0) sb.append("; ");
   sb.append(kv);
  }
  return sb.toString();
 }

 private boolean hasNonEmptyCookieKey(String raw, String... keys) {
  if (raw == null || raw.isEmpty()) return false;
  for (String part : raw.split(";")) {
   String kv = part.trim();
   int eq = kv.indexOf('=');
   if (eq <= 0) continue;
   String k = kv.substring(0, eq).trim();
   String val = kv.substring(eq + 1).trim();
   if (val.isEmpty() || "null".equalsIgnoreCase(val) || "undefined".equalsIgnoreCase(val) || "deleted".equalsIgnoreCase(val) || "0".equals(val)) continue;
   for (String target : keys) {
    if (target.equals(k)) return true;
   }
  }
  return false;
 }

 private boolean hasDyWebSessionCookie() {
  CookieManager cm = CookieManager.getInstance();
  String c1 = cm.getCookie("https://www.douyin.com");
  String c2 = cm.getCookie("https://sso.douyin.com");
  return hasNonEmptyCookieKey(c1, "sessionid", "sessionid_ss", "sid_tt", "sid_guard", "sid_ucp_v1", "ssid_ucp_v1")
   || hasNonEmptyCookieKey(c2, "sessionid", "sessionid_ss", "sid_tt", "sid_guard", "sid_ucp_v1", "ssid_ucp_v1");
 }

 private boolean hasDySessionCookie(String cookieHeader) {
  return hasNonEmptyCookieKey(cookieHeader,
   "sessionid", "sessionid_ss", "sid_tt", "sid_guard", "sid_ucp_v1", "ssid_ucp_v1", "X-Xiaodian-Token");
 }

 private boolean hasDyAccountSession() {
  return hasDyWebSessionCookie() || (dyLoginSuccessAt > 0
   && android.os.SystemClock.elapsedRealtime() - dyLoginSuccessAt < 15000);
 }

 private void persistDySessionCookie() {
  try {
   CookieManager cm = CookieManager.getInstance();
   String[] domains = {"https://www.douyin.com", "https://sso.douyin.com", "https://ec.snssdk.com", "https://haohuo.jinritemai.com"};
   StringBuilder all = new StringBuilder();
   for (String d : domains) {
    String raw = cm.getCookie(d);
    if (raw != null && !raw.isEmpty()) {
     if (all.length() > 0) all.append(";;");
     all.append(d).append("::").append(raw);
    }
   }
   String cookieStr = all.toString();
   if (!cookieStr.isEmpty() && hasDyWebSessionCookie()) {
    getContext().getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE)
     .edit()
     .putString("dy_all_cookies", cookieStr)
     .apply();
    for (String d : domains) {
     String raw = cm.getCookie(d);
     if (raw == null) continue;
     for (String part : raw.split(";")) {
      String kv = part.trim();
      int eq = kv.indexOf('=');
      if (eq <= 0) continue;
      String k = kv.substring(0, eq).trim();
      String v = kv.substring(eq + 1).trim();
      if (k.isEmpty() || v.isEmpty() || "null".equals(v) || "deleted".equals(v)) continue;
      cm.setCookie(d, k + "=" + v + "; Path=/; Max-Age=2592000; Secure; SameSite=Lax");
     }
    }
    cm.flush();
   }
  } catch (Exception ignored) {}
 }

 private void restoreDySessionCookie() {
  try {
   SharedPreferences prefs = getContext().getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE);
   String saved = prefs.getString("dy_all_cookies", "");
   if (saved == null || saved.isEmpty()) return;
   CookieManager cm = CookieManager.getInstance();
   for (String dEntry : saved.split(";;")) {
    int sep = dEntry.indexOf("::");
    if (sep <= 0) continue;
    String d = dEntry.substring(0, sep).trim();
    String raw = dEntry.substring(sep + 2).trim();
    for (String part : raw.split(";")) {
     String kv = part.trim();
     int eq = kv.indexOf('=');
     if (eq <= 0) continue;
     String k = kv.substring(0, eq).trim();
     String v = kv.substring(eq + 1).trim();
     if (k.isEmpty() || v.isEmpty() || "null".equals(v) || "deleted".equals(v)) continue;
     cm.setCookie(d, k + "=" + v + "; Path=/; Max-Age=2592000; Secure; SameSite=Lax");
    }
   }
   cm.flush();
  } catch (Exception ignored) {}
 }

 private void clearDySavedCookies() {
  try {
   getContext().getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE)
    .edit()
    .remove("dy_all_cookies")
    .apply();
  } catch (Exception ignored) {}
 }

 private String rewriteDyEcUrl(String url, int bType, int aid) {
  String u = url.replaceAll("([?&])is_outside=1(&|$)", "$1")
                .replaceAll("\\?&", "?")
                .replaceAll("&&", "&")
                .replaceAll("[?&]$", "");
  if (u.contains("b_type_new=")) {
   u = u.replaceAll("([?&])b_type_new=\\d+", "$1b_type_new=" + bType);
  } else {
   u += (u.contains("?") ? "&" : "?") + "b_type_new=" + bType;
  }
  if (aid > 0) {
   if (u.contains("aid=")) {
    u = u.replaceAll("([?&])aid=\\d+", "$1aid=" + aid);
   } else {
    u += "&aid=" + aid;
   }
  }
  return u;
 }

 private String fetchDyEcJson(String urlStr, String cookieHeader, Map<String, String> reqHeaders, String customUa) {
  HttpURLConnection conn = null;
  try {
   conn = (HttpURLConnection) new URL(urlStr).openConnection();
   conn.setRequestMethod("GET");
   conn.setConnectTimeout(4500);
   conn.setReadTimeout(4500);
   conn.setInstanceFollowRedirects(true);
   conn.setRequestProperty("Accept", "application/json, text/plain, */*");
   conn.setRequestProperty("Origin", "https://haohuo.jinritemai.com");
   conn.setRequestProperty("Referer", "https://haohuo.jinritemai.com/views/neworder/center");
   String ua = customUa != null ? customUa : (reqHeaders != null ? reqHeaders.get("User-Agent") : null);
   if (ua != null && !ua.isEmpty()) conn.setRequestProperty("User-Agent", ua);
   if (reqHeaders != null) {
    String xdToken = reqHeaders.get("X-Xiaodian-Token");
    if (xdToken != null && !xdToken.isEmpty()) conn.setRequestProperty("X-Xiaodian-Token", xdToken);
   }
   if (cookieHeader != null && !cookieHeader.isEmpty()) {
    conn.setRequestProperty("Cookie", cookieHeader);
   }
   int code = conn.getResponseCode();
   List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
   if (setCookies != null) {
    CookieManager cm = CookieManager.getInstance();
    for (String sc : setCookies) {
     if (sc != null && !sc.isEmpty()) cm.setCookie("https://ec.snssdk.com", sc);
    }
   }
   InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
   if (in == null) return null;
   try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
    byte[] buf = new byte[4096];
    int n;
    while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
    return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
   }
  } catch (Exception e) {
   return null;
  } finally {
   if (conn != null) conn.disconnect();
  }
 }

 private boolean isDyOrderBodyOk(String b) {
  return b != null && (b.contains("\"st\":0") || b.contains("\"st\": 0")) && !b.contains("10001010A");
 }

 private void invalidateDyApiHistory() {
  dyNavigationEpoch++;
  dyApiHistory.clear();
 }

 private void recordDyApiReply(int epoch, String path, String url, String body) {
  if (!path.equals("/order/page") && !path.equals("/order/page/") && !path.startsWith("/logistics/")) return;
  post(() -> {
   if (destroyed || dyNavigationEpoch != epoch) return;
   if (dyApiHistory.size() >= 24) dyApiHistory.remove(0);
   dyApiHistory.add(new String[]{url, body});
   sampleProvider(PROVIDER_DY);
  });
 }

 private WebResourceResponse interceptDyEcRequest(WebResourceRequest r) {
  int epoch = dyNavigationEpoch;
  String scheme = r.getUrl().getScheme();
  String host = r.getUrl().getHost();
  if (host != null && host.startsWith("10.")) {
   return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
  }
  if (!"https".equalsIgnoreCase(scheme) || host == null) return null;
  boolean isEc = "ec.snssdk.com".equalsIgnoreCase(host);
  boolean isHotsoon = "hotsoon.snssdk.com".equalsIgnoreCase(host);
  if (!isEc && !isHotsoon) return null;
  String path = r.getUrl().getPath();
  if (path == null || !(isHotsoon ? path.equals("/hotsoon/find/banners")
      : path.startsWith("/order/") || path.startsWith("/logistics/")
          || path.equals("/index/getNewAbUiParams"))) return null;

  Map<String, String> reqHeaders = r.getRequestHeaders();
  String origin = reqHeaders != null ? reqHeaders.get("Origin") : null;
  if (origin == null || origin.isEmpty()) origin = "https://haohuo.jinritemai.com";
  Map<String, String> corsHeaders = new HashMap<>();
  corsHeaders.put("Access-Control-Allow-Origin", origin);
  corsHeaders.put("Access-Control-Allow-Credentials", "true");
  corsHeaders.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  corsHeaders.put("Access-Control-Allow-Headers", "Content-Type, X-Xiaodian-Token, X-Requested-With, Accept, Origin");
  corsHeaders.put("Cache-Control", "no-store");
  if ("OPTIONS".equalsIgnoreCase(r.getMethod())) {
   return new WebResourceResponse("text/plain", "utf-8", 200, "OK", corsHeaders, new ByteArrayInputStream(new byte[0]));
  }
  String origUrl = r.getUrl().toString();
  if (isHotsoon) {
   if (origUrl.contains("/hotsoon/find/banners")) {
    String emptyBanners = "{\"status_code\":0,\"st\":0,\"code\":0,\"msg\":\"\",\"data\":{\"banners\":[],\"total\":0}}";
    return new WebResourceResponse("application/json", "utf-8", 200, "OK", corsHeaders,
     new ByteArrayInputStream(emptyBanners.getBytes(StandardCharsets.UTF_8)));
   }
   return null;
  }
  if (!"GET".equalsIgnoreCase(r.getMethod())) return null;

  if (origUrl.contains("/index/getNewAbUiParams")) {
   String ab = "{\"st\":0,\"code\":0,\"msg\":\"\",\"data\":{},\"ecom_status_code\":0}";
   return new WebResourceResponse("application/json", "utf-8", 200, "OK", corsHeaders,
    new ByteArrayInputStream(ab.getBytes(StandardCharsets.UTF_8)));
  }

  String cookieHeader = buildDyCookieHeader();
  boolean hasSession = hasDySessionCookie(cookieHeader);
  String awemeUa = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36 aweme_280000 JsSdk/2.0 NetType/WIFI Channel/default AppName/aweme app_version/28.0.0";

  if (!hasSession) {
   if (origUrl.contains("/order/ordercount")) {
    String zeroCounts = "{\"st\":0,\"msg\":\"\",\"data\":{\"all\":0,\"not_pay\":0,\"stock_up\":0,\"delivery\":0,\"comment\":0},\"ecom_status_code\":0}";
    return new WebResourceResponse("application/json", "utf-8", 200, "OK", corsHeaders,
     new ByteArrayInputStream(zeroCounts.getBytes(StandardCharsets.UTF_8)));
   }
   String unauth = "{\"code\":\"10001010A\",\"st\":10001,\"msg\":\"请先登录抖音账号\",\"data\":null}";
   recordDyApiReply(epoch, path, origUrl, unauth);
   return new WebResourceResponse("application/json", "utf-8", 200, "OK", corsHeaders,
    new ByteArrayInputStream(unauth.getBytes(StandardCharsets.UTF_8)));
  }

  String body = null;
  if (origUrl.contains("/order/page")) {
   body = fetchDyEcJson(rewriteDyEcUrl(origUrl, 2, 1128), cookieHeader, reqHeaders, awemeUa);
   if (!isDyOrderBodyOk(body)) {
    String alt2 = fetchDyEcJson(rewriteDyEcUrl(origUrl, 2, 6383), cookieHeader, reqHeaders, awemeUa);
    if (isDyOrderBodyOk(alt2)) {
     body = alt2;
    } else {
     String alt3 = fetchDyEcJson(origUrl, cookieHeader, reqHeaders, null);
     if (isDyOrderBodyOk(alt3)) {
      body = alt3;
     } else if (body == null) {
      body = alt2 != null ? alt2 : alt3;
     }
    }
   }
   if (!isDyOrderBodyOk(body)) {
    boolean isUnauth = body != null && (body.contains("10001010A") || body.contains("用户未登录"));
    if (isUnauth) {
     body = "{\"code\":\"10001010A\",\"st\":10001,\"msg\":\"抖音订单需要重新授权\",\"data\":null}";
    } else {
     body = "{\"st\":10005,\"code\":\"10005\",\"msg\":\"抖音订单同步失败，请点击右上角「刷新网页」重试\",\"data\":null}";
    }
   }
  } else if (origUrl.contains("/order/ordercount")) {
   body = fetchDyEcJson(rewriteDyEcUrl(origUrl, 2, 1128), cookieHeader, reqHeaders, awemeUa);
   if (!isDyOrderBodyOk(body)) {
    body = "{\"st\":0,\"msg\":\"\",\"data\":{\"all\":0,\"not_pay\":0,\"stock_up\":0,\"delivery\":0,\"comment\":0},\"ecom_status_code\":0}";
   }
  } else if (origUrl.contains("/logistics/")) {
   body = fetchDyEcJson(rewriteDyEcUrl(origUrl, 2, 1128), cookieHeader, reqHeaders, awemeUa);
   if (body == null || (!body.contains("\"status_code\":0") && !body.contains("\"st\":0"))) {
    String altLog = fetchDyEcJson(rewriteDyEcUrl(origUrl, 2, 6383), cookieHeader, reqHeaders, awemeUa);
    if (altLog != null && (altLog.contains("\"status_code\":0") || altLog.contains("\"st\":0"))) {
     body = altLog;
    } else {
     String origLog = fetchDyEcJson(origUrl, cookieHeader, reqHeaders, null);
     if (origLog != null && (origLog.contains("\"status_code\":0") || origLog.contains("\"st\":0"))) {
      body = origLog;
     } else if (body == null) {
      body = "{\"status_code\":1,\"st\":1,\"msg\":\"\"}";
     }
    }
   }
  } else {
   body = fetchDyEcJson(origUrl, cookieHeader, reqHeaders, null);
   if (body == null || body.isEmpty()) {
    body = "{\"st\":0,\"code\":0,\"msg\":\"\",\"data\":{}}";
   }
  }

  recordDyApiReply(epoch, path, origUrl, body);
  return new WebResourceResponse("application/json", "utf-8", 200, "OK", corsHeaders,
   new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
 }

 private void schedule() {
  if (paused || !activeTab || destroyed || polling) return;
  polling = true;
  postDelayed(tick, 900);
 }

 void sampleAll() {
  for (int p = PROVIDER_JD; p < PROVIDER_COUNT; p++) sampleProvider(p);
 }

 private void showJdOrderProblem(String message) {
  if (mode == PROVIDER_JD) status.setText(message);
  if (message.equals(providerErrors[PROVIDER_JD])) return;
  providerErrors[PROVIDER_JD] = message;
  renderSummary(false);
 }

 private void sampleProvider(final int provider) {
  final WebView expected = webs[provider];
  final int token = generations[provider];
  if (expected == null || paused || destroyed || inFlight[provider] || !allowedUrl(provider, expected.getUrl())) return;
  if ((provider == PROVIDER_JD || provider == PROVIDER_SF || provider == PROVIDER_JDL) && !pageCommitted[provider]) return;
  inFlight[provider] = true;
  expected.evaluateJavascript(buildProviderEvalScript(provider), result -> {
   if (destroyed || webs[provider] != expected || generations[provider] != token) return;
   inFlight[provider] = false;
   if (result == null || "null".equals(result)) return;
   try {
    JSONObject data = new JSONObject(result);
    if (!samePagePath(data.optString("href"), expected.getUrl())) return;
    if (provider == PROVIDER_DY && dySmsLoginOffered != data.optBoolean("smsLogin")) {
     dySmsLoginOffered = data.optBoolean("smsLogin");
     updateDySmsAction();
    }
    if (provider == PROVIDER_DY && data.optBoolean("ssoDone")) {
     onDySsoLoginSuccess();
     return;
    }
    if (provider == PROVIDER_DY && data.optBoolean("needSso")) {
     onDySessionExpired();
     return;
    }
    if (data.optBoolean("login")) {
     boolean statusChanged = providerSignedIn[provider]
      || ((provider == PROVIDER_JD || provider == PROVIDER_JDL) && !providerErrors[provider].isEmpty());
     providerErrors[provider] = "";
     providerSignedIn[provider] = false;
     providerOrders[provider] = new JSONArray();
     if (provider == PROVIDER_JD) lastSnapshots[provider] = "";
     pendingAutoSummary[provider] = provider != PROVIDER_SF && provider != PROVIDER_JDL;
     if (statusChanged) {
      saveCachedState();
      notifySessionState();
      renderSummary(false);
     }
     if (mode == provider) {
      status.setText(loginHint(provider));
      if (provider == PROVIDER_DY) redirectToDyLoginIfNeeded(expected);
      if (provider == PROVIDER_JD) redirectToJdLoginIfNeeded(expected);
      notifySessionState();
     }
     return;
    }
    String jdProblem = provider == PROVIDER_JD ? data.optString("problem") : "";
    if (!jdProblem.isEmpty()) {
     showJdOrderProblem("busy".equals(jdProblem) ? "京东订单页当前繁忙，请稍后刷新。"
      : "京东订单页暂时无法加载，请稍后刷新。");
     return;
    }
    String errMsg = data.optString("error", "");
    if (!errMsg.isEmpty()) {
     boolean changed = !errMsg.equals(providerErrors[provider]);
     providerErrors[provider] = errMsg;
     if (provider == PROVIDER_JDL && changed) renderSummary(false);
     if (mode == provider) {
      status.setText(errMsg);
     }
     return;
    }
    boolean ready = data.optBoolean("ready");
    if (provider == PROVIDER_JD && !ready) return;
    boolean jdRecovered = (provider == PROVIDER_JD || provider == PROVIDER_JDL) && !providerErrors[provider].isEmpty();
    providerErrors[provider] = "";
    if (!ready && !(provider == PROVIDER_SF && data.optBoolean("authenticated"))) return;
    boolean justLoggedIn = !providerSignedIn[provider] || pendingAutoSummary[provider];
    providerSignedIn[provider] = true;
    if (provider == PROVIDER_DY) {
     dyOrderNeedsAuthorization = false;
     persistDySessionCookie();
    }
    if (justLoggedIn && (provider == PROVIDER_JD || provider == PROVIDER_SF || provider == PROVIDER_JDL)) {
     CookieManager.getInstance().flush();
    }
    JSONArray rows = data.optJSONArray("orders");
     if (ready && rows != null) {
     providerOrders[provider] = rows;
    }
    String next = data.toString();
    if (!next.equals(lastSnapshots[provider]) || justLoggedIn || jdRecovered) {
     lastSnapshots[provider] = next;
     saveCachedState();
     notifySessionState();
     renderSummary(false);
    }
    if (mode == provider && pendingAutoSummary[provider]) {
     pendingAutoSummary[provider] = false;
     Toast.makeText(getContext(), "已完成" + PROVIDER_NAMES[provider] + "登录，正在汇总取件码", Toast.LENGTH_SHORT).show();
     showSummary();
    } else if (mode == provider) {
     status.setText(ready ? signedInStatus(provider) : "顺丰已登录，运单尚未同步。");
    }
   } catch (JSONException ignored) {}
  });
 }

 private static boolean isPickupCode(String code) {
  return code.matches("[A-Za-z0-9][A-Za-z0-9-]{1,23}");
 }

 private static boolean validTaobaoOrderKey(String key) {
  return key.matches("(?:(?:mail|logistics):[A-Za-z0-9_-]{1,80}|trade:[0-9]{1,32}):[0-9]{1,4}")
   || key.matches("pickup:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
 }

 private static String localTaobaoCodeKey(JSONObject row) {
  String identity = row.optString("name") + "\n" + row.optString("code");
  return "pickup:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
 }

 private static boolean sameTaobaoPickup(JSONObject left, JSONObject right) {
  if (left == null || right == null || !left.optString("code").equals(right.optString("code"))) return false;
  String leftName = left.optString("name"), rightName = right.optString("name");
  return leftName.isEmpty() || rightName.isEmpty() || leftName.equals(rightName);
 }

 private boolean hasIdentifiedTaobaoCode(JSONObject candidate) {
  for (Map.Entry<String, JSONArray> entry : taobaoOrderCodes.entrySet()) {
   if (entry.getKey().startsWith("pickup:")) continue;
   JSONArray codes = entry.getValue();
   for (int i = 0; i < codes.length(); i++) {
    if (sameTaobaoPickup(candidate, codes.optJSONObject(i))) return true;
   }
  }
  return false;
 }

 private boolean removeLocalTaobaoCodes(JSONArray identifiedCodes) {
  boolean changed = false;
  Iterator<Map.Entry<String, JSONArray>> groups = taobaoOrderCodes.entrySet().iterator();
  while (groups.hasNext()) {
   Map.Entry<String, JSONArray> entry = groups.next();
   if (!entry.getKey().startsWith("pickup:")) continue;
   JSONObject local = entry.getValue().optJSONObject(0);
   for (int i = 0; i < identifiedCodes.length(); i++) {
    if (!sameTaobaoPickup(local, identifiedCodes.optJSONObject(i))) continue;
    groups.remove();
    changed = true;
    break;
   }
  }
  return changed;
 }

 private static JSONArray validTaobaoOrderCodes(JSONArray input) {
  JSONArray codes = new JSONArray();
  if (input == null) return codes;
  for (int i = 0; i < input.length() && i < 12; i++) {
   JSONObject row = input.optJSONObject(i);
   if (row == null) continue;
   String code = row.optString("code");
   if (!isPickupCode(code)) continue;
   String station = row.optString("name");
   try {
    codes.put(new JSONObject().put("code", code).put("name", station.substring(0, Math.min(60, station.length()))));
   } catch (JSONException ignored) {}
  }
  return codes;
 }

 boolean syncTaobaoOrderCodes(String orderKey, JSONArray input, boolean pickedUp) {
  if (!orderKey.isEmpty() && !validTaobaoOrderKey(orderKey)) return false;
  JSONArray codes = validTaobaoOrderCodes(input);
  boolean changed = false;
  if (orderKey.isEmpty()) {
   if (codes.length() == 0) return false;
   if (pickedUp) changed = removeLocalTaobaoCodes(codes);
   else {
    for (int i = 0; i < codes.length(); i++) {
     JSONObject row = codes.optJSONObject(i);
     String localKey = localTaobaoCodeKey(row);
     if (taobaoOrderCodes.containsKey(localKey) || hasIdentifiedTaobaoCode(row)) continue;
     taobaoOrderCodes.put(localKey, new JSONArray().put(row));
     changed = true;
    }
   }
  } else if (pickedUp) {
   JSONArray previous = taobaoOrderCodes.remove(orderKey);
   if (previous != null) {
    removeLocalTaobaoCodes(previous);
    changed = true;
   }
  } else {
   if (codes.length() == 0) return false;
   changed = removeLocalTaobaoCodes(codes);
   JSONArray previous = taobaoOrderCodes.get(orderKey);
   if (previous == null || !previous.toString().equals(codes.toString())) {
    taobaoOrderCodes.put(orderKey, codes);
    changed = true;
   }
  }
  if (!changed) return true;
  while (taobaoOrderCodes.size() > MAX_TAOBAO_ORDERS) {
   taobaoOrderCodes.remove(taobaoOrderCodes.keySet().iterator().next());
  }
  saveTaobaoOrderCodes();
  renderSummary(false);
  return true;
 }

 void clearTaobaoOrderCodes() {
  if (taobaoOrderCodes.isEmpty()) return;
  taobaoOrderCodes.clear();
  saveTaobaoOrderCodes();
  renderSummary(false);
 }

 private void saveTaobaoOrderCodes() {
  JSONArray saved = new JSONArray();
  for (Map.Entry<String, JSONArray> entry : taobaoOrderCodes.entrySet()) {
   try {
    saved.put(new JSONObject().put("orderKey", entry.getKey()).put("codes", entry.getValue()));
   } catch (JSONException ignored) {}
  }
  getContext().getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE).edit()
   .putString(PREF_TAOBAO_ORDER_CODES, saved.toString()).apply();
 }

 private List<JSONObject> taobaoPickupRows() {
  List<JSONObject> rows = new ArrayList<>();
  Set<String> liveCodesByStation = new HashSet<>();
  if (providerSignedIn[PROVIDER_TB]) {
   JSONArray parcels = providerOrders[PROVIDER_TB];
   for (int i = 0; i < parcels.length(); i++) {
    JSONObject row = parcels.optJSONObject(i);
    if (row == null) continue;
    String code = row.optString("code");
    if (code.isEmpty()) continue;
    rows.add(row);
    liveCodesByStation.add(row.optString("name") + "\n" + code);
   }
  }
  for (JSONArray codes : taobaoOrderCodes.values()) {
   for (int i = 0; i < codes.length(); i++) {
    JSONObject row = codes.optJSONObject(i);
    if (row != null && (row.optString("name").isEmpty()
        || !liveCodesByStation.contains(row.optString("name") + "\n" + row.optString("code")))) rows.add(row);
   }
  }
  return rows;
 }

 private void removeTaobaoOrderCode(JSONObject row) {
  for (Iterator<JSONArray> groups = taobaoOrderCodes.values().iterator(); groups.hasNext();) {
   JSONArray codes = groups.next();
   for (int i = 0; i < codes.length(); i++) {
    if (codes.optJSONObject(i) != row) continue;
    codes.remove(i);
    if (codes.length() == 0) groups.remove();
    saveTaobaoOrderCodes();
    renderSummary(false);
    return;
   }
  }
 }

 private void saveManualJdlCodes() {
  getContext().getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE).edit()
   .putString(PREF_MANUAL_JDL_CODES, manualJdlCodes.toString()).apply();
  renderSummary(false);
 }

 private void removeManualJdlCode(JSONObject row) {
  for (int i = 0; i < manualJdlCodes.length(); i++) {
   if (manualJdlCodes.optJSONObject(i) == row) {
    manualJdlCodes.remove(i);
    saveManualJdlCodes();
    return;
   }
  }
 }

 private void updateSummaryStatusText() {
  if (mode != MODE_SUMMARY) return;
  int loggedInCount = 0;
  int totalCodes = manualJdlCodes.length() + countForPlatform(PROVIDER_TB);
  for (int p : PLATFORMS) {
   if (isPlatformSignedIn(p)) {
    loggedInCount++;
    if (p != PROVIDER_TB) totalCodes += countForPlatform(p);
   }
  }
  if (loggedInCount == 0) {
   status.setText(totalCodes > 0 ? "本机已添加 " + totalCodes + " 个待取件码。"
    : "尚未登录取件平台；请点左上角「切换软件」选择软件登录。");
  } else {
   status.setText("已登录 " + loggedInCount + " 个平台，共汇总 " + totalCodes + " 个待取件码。");
  }
 }

 private void renderSummary(boolean force) {
  updateSummaryStatusText();
  StringBuilder sig = new StringBuilder();
  sig.append(dark).append('|');
  sig.append(manualJdlCodes).append('|');
  sig.append(taobaoOrderCodes).append('|');
  sig.append(providerErrors[PROVIDER_JD]).append('|');
  sig.append(providerErrors[PROVIDER_JDL]).append('|');
  for (int p = 0; p < PROVIDER_COUNT; p++) {
   sig.append(providerSignedIn[p]).append(':').append(providerOrders[p] != null ? providerOrders[p].toString() : "").append('|');
  }
  String nextSig = sig.toString();
  if (!force && nextSig.equals(lastSummarySignature)) return;
  lastSummarySignature = nextSig;

  cards.removeAllViews();
  int loggedInCount = 0;
  List<JSONObject> taobaoRows = taobaoPickupRows();
  int totalCodes = manualJdlCodes.length() + taobaoRows.size();
  for (int p : PLATFORMS) {
   if (isPlatformSignedIn(p)) {
    loggedInCount++;
    if (p != PROVIDER_TB) totalCodes += countForPlatform(p);
   }
  }

  // Platform status & quick login card
  LinearLayout statusCard = new LinearLayout(getContext());
  statusCard.setOrientation(VERTICAL);
  statusCard.setPadding(dp(14), dp(14), dp(14), dp(14));
  statusCard.setBackground(borderedBg(AppTheme.surface(dark), AppTheme.border(dark), 16));

  LinearLayout headRow = new LinearLayout(getContext());
  headRow.setGravity(Gravity.CENTER_VERTICAL);
  TextView headTitle = text("登录与查件", 15);
  headTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
  headTitle.setTextColor(AppTheme.ink(dark));
  headRow.addView(headTitle, new LinearLayout.LayoutParams(0, -2, 1f));
  TextView headHint = text("登录后点平台查件", 12);
  headHint.setTextColor(AppTheme.muted(dark));
  headRow.addView(headHint);
  statusCard.addView(headRow);

  LinearLayout grid = new LinearLayout(getContext());
  grid.setOrientation(VERTICAL);
  grid.setPadding(0, dp(10), 0, 0);
  LinearLayout row1 = new LinearLayout(getContext());
  row1.setOrientation(HORIZONTAL);
  LinearLayout row2 = new LinearLayout(getContext());
  row2.setOrientation(HORIZONTAL);
  row2.setPadding(0, dp(8), 0, 0);
  for (int p : PLATFORMS) {
   final int provider = p;
   boolean signed = isPlatformSignedIn(p);
   int codeCount = countForPlatform(p);
   String chipText = (signed ? "● " : "○ ") + PROVIDER_NAMES[p] + (signed || codeCount > 0 ? " (" + codeCount + ")" : " 去登录");
   TextView chip = text(chipText, 12);
   chip.setGravity(Gravity.CENTER);
   chip.setSingleLine(true);
   chip.setTypeface(Typeface.DEFAULT, signed ? Typeface.BOLD : Typeface.NORMAL);
   chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
   chip.setTextColor(signed ? AppTheme.accentText(dark) : AppTheme.muted(dark));
   chip.setPadding(dp(8), dp(8), dp(8), dp(8));
   chip.setBackground(roundBg(AppTheme.subtleFill(dark), 12));
   chip.setOnClickListener(v -> externalSwitch.accept(provider));
   LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
   boolean topRow = p == PROVIDER_TB || p == PROVIDER_PDD;
   if (p != PROVIDER_PDD && p != PROVIDER_DY) clp.setMarginEnd(dp(8));
   (topRow ? row1 : row2).addView(chip, clp);
  }
  grid.addView(row1, new LinearLayout.LayoutParams(-1, -2));
  grid.addView(row2, new LinearLayout.LayoutParams(-1, -2));
  statusCard.addView(grid);

  LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
  cardLp.bottomMargin = dp(12);
  cards.addView(statusCard, cardLp);

  appendManualJdlCodes(cards);

  // JD Mall and JD Express cards are appended after the other platforms.
  for (int p = 0; p < PROVIDER_COUNT; p++) {
   if (p == LEGACY_CN_SLOT || p == PROVIDER_JD || p == PROVIDER_JDL) continue;
   if (p == PROVIDER_TB) {
    for (JSONObject row : taobaoRows) {
     boolean fromOrder = !row.has("id");
     String name = row.optString("name");
     if (name.isEmpty()) name = fromOrder ? "淘宝订单包裹" : "淘宝/菜鸟包裹";
     addPickupCard(p, name, row.optString("code"), row.optString("status", "待取件"),
      fromOrder ? "来自淘宝订单物流" : row.optString("detail"), fromOrder ? () -> removeTaobaoOrderCode(row) : null);
    }
    continue;
   }
   appendProviderPickupCards(cards, p);
  }
  boolean jdOrdersDisplayed = appendJdMallOrders(cards);
  appendProviderPickupCards(cards, PROVIDER_JDL);
  String jdExpressProblem = providerErrors[PROVIDER_JDL];
  if (!jdExpressProblem.isEmpty()) addInfoCard(cards, "京东快递暂未更新", jdExpressProblem);

  if (totalCodes == 0 && !jdOrdersDisplayed && jdExpressProblem.isEmpty()) {
   addInfoCard(
    loggedInCount == 0 ? "暂未登录任何软件" : "暂无待取件码",
    loggedInCount == 0
     ? "可点击左上角「切换/汇总」或上方标签，登录淘宝 / 菜鸟、拼多多、京东、顺丰或抖音。登录后会自动提取取件码并汇总在此处。"
     : "当前已登录的软件中没有待取包裹或订单未返回取件码。可下拉页面或点击右上角「刷新全部」重新拉取，也可点击上方或左上角切换其他软件登录。"
   );
  }

  TextView pullFooter = text("↓ 下拉页面即可刷新全部取件码", 11);
  pullFooter.setGravity(Gravity.CENTER);
  pullFooter.setTextColor(AppTheme.muted(dark));
  pullFooter.setPadding(0, dp(6), 0, dp(8));
  cards.addView(pullFooter, new LinearLayout.LayoutParams(-1, -2));
 }

 private void appendManualJdlCodes(LinearLayout target) {
  for (int i = 0; i < manualJdlCodes.length(); i++) {
   JSONObject row = manualJdlCodes.optJSONObject(i);
   if (row == null) continue;
   addPickupCard(target, PROVIDER_JDL, "手动录入", row.optString("code"), "待取件", row.optString("note"),
    "已取件", () -> removeManualJdlCode(row));
  }
 }

 private void appendProviderPickupCards(LinearLayout target, int provider) {
  JSONArray orders = providerOrders[provider];
  if (!providerSignedIn[provider] || orders == null) return;
  for (int i = 0; i < orders.length(); i++) {
   JSONObject order = orders.optJSONObject(i);
   if (order == null) continue;
   String code = order.optString("code").trim();
   if (code.isEmpty()) continue;
   addPickupCard(target, provider, order.optString("name", PROVIDER_NAMES[provider] + "包裹"), code,
    order.optString("status", "待取件"), order.optString("detail", ""), "", null);
  }
 }

 private boolean appendJdMallOrders(LinearLayout target) {
  boolean displayed = false;
  String problem = providerErrors[PROVIDER_JD];
  if (!problem.isEmpty()) {
   addInfoCard(target, "京东取件信息暂未更新", problem);
   displayed = true;
  }
  JSONArray orders = providerOrders[PROVIDER_JD];
  if (!providerSignedIn[PROVIDER_JD] || orders == null) return displayed;
  for (int i = 0; i < orders.length(); i++) {
   JSONObject order = orders.optJSONObject(i);
   if (order == null) continue;
   String code = order.optString("code").trim();
   if (code.isEmpty() && !order.optBoolean("arrived")) continue;
   String station = order.optString("station");
   String name = station.isEmpty() ? order.optString("name", "京东订单") : station;
   String detail = order.optString("detail");
   String orderId = order.optString("id");
   String orderLink = order.optString("link");
   boolean canOpenLogistics = orderId.matches("[0-9]{1,32}") || JdUrlPolicy.allowedMall(orderLink);
   if (code.isEmpty()) {
    detail = (detail.isEmpty() ? "" : detail + "\n") + "京东网页未提供取件码";
   }
   addPickupCard(target, PROVIDER_JD, name, code, code.isEmpty() ? "已到站" : "待取件", detail,
    "在京东 App 查看物流", canOpenLogistics ? () -> openJdAppLogistics(orderId, orderLink) : null);
   displayed = true;
  }
  return displayed;
 }

 private void openJdAppLogistics(String orderId, String orderLink) {
  try {
   JSONObject params = new JSONObject().put("category", "jump");
   if (orderId.matches("[0-9]{1,32}")) {
    params.put("des", "orderTrack").put("orderId", orderId);
   } else if (JdUrlPolicy.allowedMall(orderLink)) {
    params.put("des", "m").put("url", orderLink);
   } else return;
   android.net.Uri target = android.net.Uri.parse("openapp.jdmobile://virtual").buildUpon()
    .appendQueryParameter("params", params.toString()).build();
   android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, target)
    .addCategory(android.content.Intent.CATEGORY_BROWSABLE).setPackage("com.jingdong.app.mall");
   getContext().startActivity(intent);
  } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
   Toast.makeText(getContext(), "无法打开京东物流页，请确认已安装京东 App。", Toast.LENGTH_SHORT).show();
  } catch (JSONException ignored) {}
 }

 private void addPickupCard(int provider, String title, String pickupCode, String statusText, String detailText,
                            Runnable markPickedUp) {
  addPickupCard(cards, provider, title, pickupCode, statusText, detailText, "已取件", markPickedUp);
 }

 private void addPickupCard(LinearLayout target, int provider, String title, String pickupCode, String statusText, String detailText,
                            String actionLabel, Runnable onAction) {
  LinearLayout card = new LinearLayout(getContext());
  card.setOrientation(VERTICAL);
  card.setPadding(dp(16), dp(14), dp(16), dp(14));
  card.setBackground(borderedBg(AppTheme.surface(dark), AppTheme.border(dark), 16));

  LinearLayout topRow = new LinearLayout(getContext());
  topRow.setGravity(Gravity.CENTER_VERTICAL);

  TextView badge = text(PROVIDER_NAMES[provider], 11);
  badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
  badge.setTextColor(Color.WHITE);
  badge.setPadding(dp(7), dp(2), dp(7), dp(2));
  badge.setBackground(roundBg(BADGE_COLORS[provider], 6));
  LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
  badgeLp.setMarginEnd(dp(8));
  topRow.addView(badge, badgeLp);

  TextView heading = text(title, 15);
  heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
  heading.setTextColor(AppTheme.ink(dark));
  heading.setSingleLine(true);
  topRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));

  if (!statusText.isEmpty()) {
   TextView stView = text(statusText, 12);
   stView.setTextColor(AppTheme.muted(dark));
   topRow.addView(stView);
  }
  card.addView(topRow);

  if (!pickupCode.isEmpty()) {
   TextView codeView = text("取件码：" + pickupCode, 20);
   codeView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
   codeView.setTextColor(AppTheme.accentText(dark));
   codeView.setPadding(0, dp(10), 0, dp(4));
   codeView.setTextIsSelectable(true);
   card.addView(codeView);
  }

  if (!detailText.isEmpty()) {
   TextView detail = text(detailText, 13);
   detail.setTextColor(AppTheme.muted(dark));
   detail.setPadding(0, dp(4), 0, 0);
   detail.setTextIsSelectable(true);
   card.addView(detail);
  }

  if (onAction != null) {
   Button done = action(actionLabel, onAction);
   done.setBackground(roundBg(AppTheme.subtleFill(dark), 10));
   done.setTextColor(AppTheme.accentText(dark));
   LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(-2, dp(40));
   doneLp.gravity = Gravity.END;
   doneLp.topMargin = dp(6);
   card.addView(done, doneLp);
  }

  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
  lp.bottomMargin = dp(12);
  target.addView(card, lp);
 }

 private void addInfoCard(String title, String body) {
  addInfoCard(cards, title, body);
 }

 private void addInfoCard(LinearLayout target, String title, String body) {
  LinearLayout card = new LinearLayout(getContext());
  card.setOrientation(VERTICAL);
  card.setPadding(dp(16), dp(16), dp(16), dp(16));
  card.setBackground(borderedBg(AppTheme.surface(dark), AppTheme.border(dark), 16));
  TextView heading = text(title, 16);
  heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
  heading.setTextColor(AppTheme.ink(dark));
  card.addView(heading);
  TextView detail = text(body, 14);
  detail.setTextColor(AppTheme.muted(dark));
  detail.setPadding(0, dp(8), 0, 0);
  card.addView(detail);
  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
  lp.bottomMargin = dp(12);
  target.addView(card, lp);
 }

 void reloadActiveOrAll() {
  if (mode >= PROVIDER_JD && mode < PROVIDER_COUNT) {
   showPage();
   reloadProvider(mode);
  } else {
   showSummary();
   for (int p = PROVIDER_JD; p < PROVIDER_COUNT; p++) reloadProvider(p);
   externalSwitch.accept(-2); // Signal MainActivity to also refresh TB & PDD parcels
   Toast.makeText(getContext(), "\u6b63\u5237\u65b0\u5168\u90e8\u5df2\u767b\u5f55\u5e73\u53f0\u7684\u53d6\u4ef6\u7801\u2026", Toast.LENGTH_SHORT).show();
  }
 }

 void reloadProvider(int provider) {
  if (!isWebProvider(provider)) return;
  if (provider == PROVIDER_DY) invalidateDyApiHistory();
  if (provider == PROVIDER_JD) jdLoginRedirected = false;
  generations[provider]++;
  inFlight[provider] = false;
  lastSnapshots[provider] = "";
  providerErrors[provider] = "";
  WebView web = webs[provider];
  if (web == null) return;
  if (provider == PROVIDER_DY && mode == PROVIDER_DY && dyOrderNeedsAuthorization) {
   dyOrderNeedsAuthorization = false;
   dyLoginSuccessAt = 0L;
   loadDyUrl(web, JdUrlPolicy.DY_LOGIN);
  } else {
   loadProviderEntry(provider, web);
  }
 }

 void refreshJdOrdersAfterIdentityLogin() {
  if (destroyed || paused) return;
  if (webs[PROVIDER_JD] == null) ensureWeb(PROVIDER_JD);
  else if (!providerSignedIn[PROVIDER_JD] || JdUrlPolicy.isMallLoginPage(webs[PROVIDER_JD].getUrl())) {
   reloadProvider(PROVIDER_JD);
  }
 }

 void resetProviderSession(int provider) {
  if (!isWebProvider(provider)) return;
  disposeProviderWebView(provider);
  if (provider == PROVIDER_JD) {
   jdLoginRedirected = false;
  } else if (provider == PROVIDER_SF) {
   clearStaleSfCookies();
  } else if (provider == PROVIDER_DY) {
   invalidateDyApiHistory();
   dyLoginSuccessAt = 0L;
   dyOrderNeedsAuthorization = false;
   clearLegacyDyFakeCookie();
   clearAllDySessionCookies();
   clearDySavedCookies();
  }
  lastSnapshots[provider] = "";
  providerErrors[provider] = "";
  providerSignedIn[provider] = false;
  pendingAutoSummary[provider] = false;
  providerOrders[provider] = new JSONArray();
  saveCachedState();
  notifySessionState();
  renderSummary(true);
 }

 private void disposeProviderWebView(int provider) {
  generations[provider]++;
  inFlight[provider] = false;
  pageCommitted[provider] = false;
  WebView web = webs[provider];
  if (web == null) return;
  webs[provider] = null;
  web.setWebViewClient(new WebViewClient());
  web.setWebChromeClient(null);
  web.stopLoading();
  webContainer.removeView(web);
  web.destroy();
 }

 void resetSessionView() {
  pausePage();
  for (int p = PROVIDER_JD; p < PROVIDER_COUNT; p++) resetProviderSession(p);
  mode = MODE_SUMMARY;
  updateModeUi();
  renderSummary(true);
 }

 void idlePage() {
  activeTab = false;
  removeCallbacks(tick);
  polling = false;
 }

 void pausePage() {
  paused = true;
  idlePage();
  for (int p = PROVIDER_JD; p < PROVIDER_COUNT; p++) {
   if (webs[p] != null) webs[p].onPause();
  }
 }

 void resumePage(boolean active) {
  paused = false;
  for (int p = PROVIDER_JD; p < PROVIDER_COUNT; p++) {
   if (webs[p] != null) webs[p].onResume();
  }
  if (active) showPage();
  else idlePage();
 }

 void scrollFocusedInput() {
  if (mode >= PROVIDER_JD && mode < PROVIDER_COUNT && webs[mode] != null) {
   webs[mode].evaluateJavascript("(function(){var e=document.activeElement;if(e&&e.matches('input,textarea'))e.scrollIntoView({block:'center'});})()", null);
  }
 }

 boolean goBack() {
  if (mode >= PROVIDER_JD && mode < PROVIDER_COUNT) {
   WebView w = webs[mode];
   if (w != null && w.canGoBack()) {
    w.goBack();
    return true;
   }
   showSummary();
   return true;
  }
  return false;
 }

 void destroyPage() {
  if (destroyed) return;
  destroyed = true;
  pausePage();
  invalidateDyApiHistory();
  for (int p = PROVIDER_JD; p < PROVIDER_COUNT; p++) disposeProviderWebView(p);
  CookieManager.getInstance().flush();
 }

 void notifyTaobaoLoginChanged(boolean tbLoggedIn) {
  updateExternalParcels(PROVIDER_TB, tbLoggedIn, null);
 }
}
