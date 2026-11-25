package com.jahmel.amnyboox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * amNY for Boox.
 *
 * 1. Reads https://www.amny.com/digital-editions/ and lists the editions (Calameo book IDs).
 * 2. To download one, it opens the Calameo reader in a hidden-ish WebView, presses the
 *    reader's own Download button, and catches the short-lived signed PDF link
 *    (book-aws.calameo.com/.../book.pdf?X-Amz-...) the moment Calameo produces it.
 * 3. Hands that link to Android's DownloadManager -> Download/amNY/amNY-2026-11-24.pdf
 *    and (optionally) opens it in NeoReader.
 *
 * Doing it through the real reader means we never depend on Calameo's private API:
 * if the auto-press ever stops working you can tap Download yourself and the app
 * still catches and saves the file.
 */
public class MainActivity extends Activity {

    static final String EDITIONS_URL = "https://www.amny.com/digital-editions/";
    static final String READER_URL = "https://www.calameo.com/read/";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    static final String FOLDER = "amNY";
    static final long AUTO_TIMEOUT_MS = 45_000;

    static class Edition {
        final String id, title;
        Edition(String id, String title) { this.id = id; this.title = title; }
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    DownloadManager dm;

    FrameLayout root;
    LinearLayout mainPane, listBox, webPane;
    TextView status, webStatus;
    WebView web;

    final List<Edition> editions = new ArrayList<>();
    Edition current;
    volatile boolean captured;
    long downloadId = -1;
    boolean launchAutoDone;

    final Runnable autoTimeout = () -> {
        if (!captured && webPane.getVisibility() == View.VISIBLE) {
            webStatus.setText("Couldn't press Download automatically. Tap Calameo's download "
                    + "button yourself (top right) and the app will save the PDF.");
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("amny", MODE_PRIVATE);
        dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        buildUi();
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(onDone, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(onDone, f);
        loadEditions();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(onDone); } catch (Exception ignored) { }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webPane.getVisibility() == View.VISIBLE) closeWeb("Cancelled.");
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- UI (plain, high-contrast for e-ink)

    TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.BLACK);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setTextSize(18);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }

    CheckBox check(String label, String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(16);
        c.setTextColor(Color.BLACK);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        return c;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        // main pane
        mainPane = new LinearLayout(this);
        mainPane.setOrientation(LinearLayout.VERTICAL);
        mainPane.setPadding(dp(16), dp(16), dp(16), dp(8));

        mainPane.addView(text(getString(R.string.app_name), 28, true));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button latest = button("Get today's amNY", v -> {
            Edition e = latestMain();
            if (e == null) toast("Editions not loaded yet");
            else startGrab(e);
        });
        row.addView(latest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        row.addView(button("Refresh", v -> loadEditions()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mainPane.addView(row);

        mainPane.addView(check("Download today's amNY when the app opens", "auto", true));
        mainPane.addView(check("Open in reader after download", "open", true));

        status = text("Loading editions…", 16, false);
        status.setPadding(0, dp(8), 0, dp(8));
        mainPane.addView(status);

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox);
        mainPane.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.addView(mainPane);

        // web pane (Calameo reader)
        webPane = new LinearLayout(this);
        webPane.setOrientation(LinearLayout.VERTICAL);
        webPane.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        webStatus = text("", 15, false);
        bar.addView(webStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(button("Cancel", v -> closeWeb("Cancelled.")));
        webPane.addView(bar);
        web = new WebView(this);
        webPane.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        webPane.setVisibility(View.GONE);
        root.addView(webPane);

        setContentView(root);
        setupWebView();
    }

    void setStatus(String s) { status.setText(s); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ---------------------------------------------------------------- editions list

    void loadEditions() {
        setStatus("Loading editions…");
        new Thread(() -> {
            try {
                List<Edition> list = parseEditions(httpGet(EDITIONS_URL));
                ui.post(() -> {
                    editions.clear();
                    editions.addAll(list);
                    renderList();
                    if (list.isEmpty()) {
                        setStatus("No editions found on amny.com/digital-editions.");
                        return;
                    }
                    setStatus(list.size() + " editions. Tap one to download, long-press to re-download.");
                    maybeAutoRun();
                });
            } catch (Exception ex) {
                ui.post(() -> setStatus("Couldn't load amny.com: " + ex.getMessage()
                        + "\nCheck Wi-Fi, then tap Refresh."));
            }
        }).start();
    }

    void maybeAutoRun() {
        if (launchAutoDone || !prefs.getBoolean("auto", true)) return;
        launchAutoDone = true;
        Edition e = latestMain();
        if (e == null) return;
        if (isDone(e)) {
            setStatus("Today's paper (" + e.title + ") is already downloaded.");
            if (prefs.getBoolean("open", true)) openEdition(e);
        } else {
            startGrab(e);
        }
    }

    /** First edition titled "amNY ..." that isn't a borough edition; else the first edition. */
    Edition latestMain() {
        for (Edition e : editions) {
            String t = e.title.toLowerCase(Locale.US);
            if (t.startsWith("amny") && !t.contains("edition")) return e;
        }
        return editions.isEmpty() ? null : editions.get(0);
    }

    void renderList() {
        listBox.removeAllViews();
        for (Edition e : editions) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(4), dp(12), dp(4), dp(12));
            item.addView(text(e.title, 19, true));
            item.addView(text(isDone(e) ? "✓ Downloaded — tap to open" : "Tap to download", 14, false));
            item.setOnClickListener(v -> {
                if (isDone(e)) openEdition(e); else startGrab(e);
            });
            item.setOnLongClickListener(v -> { startGrab(e); return true; });
            listBox.addView(item);
            View line = new View(this);
            line.setBackgroundColor(Color.BLACK);
            listBox.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    static List<Edition> parseEditions(String html) {
        Map<String, String> found = new LinkedHashMap<>();
        // Preferred: <h2><a href="https://www.calameo.com/read/ID">amNY November 24, 2026</a></h2>
        Matcher m = Pattern.compile(
                "<h[1-4][^>]*>\\s*<a[^>]+href=\"https?://[a-z.]*calameo\\.com/read/([0-9a-z]+)[^\"]*\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String title = clean(m.group(2));
            if (!found.containsKey(m.group(1)) && !title.isEmpty()) found.put(m.group(1), title);
        }
        // Fallback: any Calameo link, using its title attribute if present.
        Matcher a = Pattern.compile("<a\\b([^>]*calameo\\.com/read/([0-9a-z]+)[^>]*)>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (a.find()) {
            String id = a.group(2);
            if (found.containsKey(id)) continue;
            Matcher t = Pattern.compile("title=\"([^\"]*)\"").matcher(a.group(1));
            found.put(id, t.find() ? clean(t.group(1)) : "Edition " + id);
        }
        List<Edition> out = new ArrayList<>();
        for (Map.Entry<String, String> e : found.entrySet()) out.add(new Edition(e.getKey(), e.getValue()));
        return out;
    }

    static String clean(String s) {
        String t = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
        return t.replace(':', ' ').replaceAll("\\s+", " ").trim();
    }

    /** "amNY November 24, 2026" -> "amNY-2026-11-24.pdf" (sorts nicely in the Boox library). */
    static String fileName(Edition e) {
        String title = e.title;
        Matcher m = Pattern.compile("([A-Z][a-z]+\\.? \\d{1,2},? \\d{4})").matcher(title);
        String date = null, prefix = title;
        if (m.find()) {
            String raw = m.group(1).replace(".", "").replace(",", "");
            try {
                Date d = new SimpleDateFormat("MMMM d yyyy", Locale.US).parse(raw);
                date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
                prefix = title.substring(0, m.start());
            } catch (Exception ignored) { }
        }
        String p = prefix.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (p.isEmpty()) p = "amNY";
        return (date != null ? p + "-" + date : p + "-" + e.id) + ".pdf";
    }

    static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- download state

    boolean isDone(Edition e) { return prefs.getLong("dl_" + e.id, -1) >= 0 && prefs.getBoolean("ok_" + e.id, false); }

    void openEdition(Edition e) {
        long id = prefs.getLong("dl_" + e.id, -1);
        Uri uri = id >= 0 ? dm.getUriForDownloadedFile(id) : null;
        if (uri == null) {
            prefs.edit().remove("ok_" + e.id).apply();
            renderList();
            toast("File not found (deleted?). Tap the edition again to re-download.");
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/pdf");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (Exception ex) { toast("Saved in Download/" + FOLDER + "/" + fileName(e)); }
    }

    final BroadcastReceiver onDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != downloadId || current == null) return;
            Edition e = current;
            int st = -1, reason = 0;
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (c != null && c.moveToFirst()) {
                    st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                }
            }
            if (st == DownloadManager.STATUS_SUCCESSFUL) {
                prefs.edit().putBoolean("ok_" + e.id, true).apply();
                setStatus("Saved: Download/" + FOLDER + "/" + fileName(e));
                renderList();
                if (prefs.getBoolean("open", true)) openEdition(e);
            } else {
                setStatus("Download failed (code " + reason + "). Long-press the edition to retry.");
            }
        }
    };

    // ---------------------------------------------------------------- Calameo grab

    @SuppressLint("SetJavaScriptEnabled")
    void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);          // window.open() stays in this WebView
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "AmnyBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (isPdfUrl(u)) { capture(u); return true; }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (isPdfUrl(u)) ui.post(() -> capture(u));
                return null;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!captured && url.contains("calameo.com")) v.evaluateJavascript(CLICKER_JS, null);
            }
        });

        web.setDownloadListener((url, ua, disp, mime, len) -> {
            if (url.startsWith("http")) capture(url);
            else webStatus.setText("Calameo offered an unsupported link type (" + url.split(":")[0] + ").");
        });
    }

    static boolean isPdfUrl(String u) {
        String l = u.toLowerCase(Locale.US);
        return l.contains("book-aws.calameo.com") || l.matches("^https?://[^?#]+\\.pdf([?#].*)?$");
    }

    void startGrab(Edition e) {
        current = e;
        captured = false;
        ui.removeCallbacks(autoTimeout);
        webStatus.setText("Opening " + e.title + " and pressing Download…");
        webPane.setVisibility(View.VISIBLE);
        mainPane.setVisibility(View.GONE);
        web.loadUrl(READER_URL + e.id);
        ui.postDelayed(autoTimeout, AUTO_TIMEOUT_MS);
    }

    void closeWeb(String msg) {
        ui.removeCallbacks(autoTimeout);
        web.stopLoading();
        web.loadUrl("about:blank");
        webPane.setVisibility(View.GONE);
        mainPane.setVisibility(View.VISIBLE);
        if (msg != null) setStatus(msg);
    }

    void capture(String url) {
        if (captured || current == null) return;
        captured = true;
        Edition e = current;
        String name = fileName(e);
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            r.addRequestHeader("User-Agent", UA);
            r.addRequestHeader("Referer", "https://www.calameo.com/");
            r.setTitle(e.title);
            r.setDescription(name);
            r.setMimeType("application/pdf");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FOLDER + "/" + name);
            downloadId = dm.enqueue(r);
            prefs.edit().putLong("dl_" + e.id, downloadId).remove("ok_" + e.id).apply();
            closeWeb("Downloading " + name + "…");
        } catch (Exception ex) {
            captured = false;
            webStatus.setText("Couldn't start download: " + ex.getMessage());
        }
    }

    class Bridge {
        @JavascriptInterface public void found(String url) { ui.post(() -> { if (url != null && url.startsWith("http")) capture(url); }); }
        @JavascriptInterface public void log(String msg) { ui.post(() -> { if (!captured) webStatus.setText(msg); }); }
        @JavascriptInterface public boolean isDone() { return captured; }
    }

    /**
     * Injected into the Calameo reader: catches PDF links opened via window.open / <a href>,
     * and presses anything that looks like a Download button (once each), every 1.5 s.
     * Also handles a confirmation dialog, since a second "Download" button would be pressed too.
     */
    static final String CLICKER_JS =
            "(function(){"
            + "if(window.__amny)return;window.__amny=1;"
            + "var pdf=/book-aws\\.calameo\\.com|\\.pdf(\\?|#|$)/i,rx=/download|t[ée]l[ée]charger/i;"
            + "var oo=window.open;window.open=function(u){if(u&&pdf.test(String(u))){AmnyBridge.found(new URL(String(u),location.href).href);return null;}return oo.apply(window,arguments);};"
            + "var seen=new WeakSet(),n=0;"
            + "function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}"
            + "function tick(){if(AmnyBridge.isDone()||n++>40)return;"
            + " var els=document.querySelectorAll('a,button,[role=button],[aria-label],[title],[data-action],[class*=download],[class*=Download]');"
            + " for(var i=0;i<els.length;i++){var e=els[i];if(seen.has(e)||!vis(e))continue;"
            + "  var h=e.href||e.getAttribute('href')||'';if(h&&pdf.test(h)){AmnyBridge.found(String(h));return;}"
            + "  var txt=(e.innerText||'');if(txt.length>40)txt='';"
            + "  var lab=[e.getAttribute('aria-label'),e.getAttribute('title'),e.getAttribute('data-action'),e.getAttribute('class'),e.id,txt].join(' ');"
            + "  if(rx.test(lab)){seen.add(e);AmnyBridge.log('Pressing Download…');"
            + "   try{e.click();}catch(x){}"
            + "   break;}"
            + " }"
            + " setTimeout(tick,1500);}"
            + "setTimeout(tick,2500);"
            + "})();";
}
