package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "TV Home": Free My TV's home as an app, for TVs where it can't be the home screen (Fire TV). A big backdrop that
 * follows the focused title, live sports with scores, rows of what's trending, new episodes this week and what's
 * popular on Netflix and Prime Video, "Surprise me", and the apps. Titles open in Nuvio; games open in the IPTV app
 * picked in Sports (Debrify finds the channel).
 */
public class DiscoverActivity extends Activity implements Sports.Listener, HomeData.Listener {
    private static final int BG = Color.rgb(8, 11, 20), TEXT = Color.WHITE, SUB = Color.argb(170, 255, 255, 255),
            DIM = Color.argb(110, 255, 255, 255), ACCENT = Color.rgb(110, 168, 255), LIVE = Color.rgb(255, 69, 58);
    /** Apps that lead the apps row when installed. */
    private static final List<String> FIRST_APPS = Arrays.asList(
            "com.nuvio.tv", "com.nuvio.app", "com.debrify.app.personal", "com.debrify.app", "com.dreamplayer.personal",
            "ar.tvplayer.tv", "com.amazon.amazonvideo.livingroom", "com.netflix.ninja", "com.google.android.youtube.tv");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private float dp;

    private ImageView backdrop;
    private TextView heroTitle, heroMeta, heroText, clock, weather;
    private LinearLayout rowsBox, sportsRow;
    private View sportsBlock;
    private View firstCard;
    private String backdropWanted;
    private final Runnable loadBackdrop = () -> {
        if (backdropWanted != null) load(backdropWanted, backdrop, 1280);
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            Sports s = Sports.get(DiscoverActivity.this);
            if (s.anyLive()) s.refreshLive(); else s.refreshIfStale();
            main.postDelayed(this, 60_000);
        }
    };

    // MARK: - Lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        dp = getResources().getDisplayMetrics().density;
        try {
            if (HttpResponseCache.getInstalled() == null) {
                HttpResponseCache.install(new File(getCacheDir(), "discover-http"), 60L * 1024 * 1024);
            }
        } catch (Exception ignored) {
        }
        setContentView(build());
        loadRows();
    }

    @Override protected void onResume() {
        super.onResume();
        Sports.get(this).addListener(this);
        HomeData.get(this).addListener(this);
        HomeData.get(this).refreshIfStale();
        Sports.get(this).refreshIfStale();
        fillSports();
        onData();
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override protected void onPause() {
        Sports.get(this).removeListener(this);
        HomeData.get(this).removeListener(this);
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onSports() { fillSports(); }

    @Override public void onData() {
        HomeData.Weather w = HomeData.get(this).weather;
        if (weather == null) return;
        if (Double.isNaN(w.temp)) {
            weather.setText("");
            return;
        }
        String city = Prefs.city().isEmpty() ? "" : Prefs.city() + "  ";
        weather.setText(city + HomeData.degrees(w.temp) + "  " + HomeData.condition(w.code, w.isDay));
    }

    // MARK: - Layout

    private int px(float v) { return Math.round(v * dp); }

    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (face != null) t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.55f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View shadeLeft = new View(this);
        shadeLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {Color.argb(245, 8, 11, 20), Color.argb(150, 8, 11, 20), Color.argb(0, 8, 11, 20)}));
        root.addView(shadeLeft, new FrameLayout.LayoutParams(-1, -1));
        View shadeBottom = new View(this);
        shadeBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Color.argb(0, 8, 11, 20), Color.argb(120, 8, 11, 20), Color.argb(250, 8, 11, 20)}));
        root.addView(shadeBottom, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(px(40), px(22), px(0), 0);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        // Top bar: clock and weather on the left, the buttons on the right.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, px(40), 0);
        clock = text(22, Ui.displayMedium, TEXT);
        top.addView(clock);
        weather = text(14, Ui.medium, SUB);
        weather.setPadding(px(16), 0, 0, 0);
        top.addView(weather, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(button("🎲  " + Ui.tr("Surprise me"), v -> surprise()));
        top.addView(button(Ui.tr("Sports"), v -> startActivity(new Intent(this, SportsActivity.class))));
        top.addView(button("⚙  " + Ui.tr("Settings"), v -> startActivity(new Intent(this, SettingsActivity.class)
                .putExtra("settings", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        column.addView(top);

        // Hero text for the focused title.
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(0, px(26), 0, px(10));
        heroTitle = text(34, Ui.displaySemibold, TEXT);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(heroTitle, new LinearLayout.LayoutParams(px(560), -2));
        heroMeta = text(14, Ui.medium, ACCENT);
        heroMeta.setPadding(0, px(10), 0, px(8));
        hero.addView(heroMeta);
        heroText = text(14, Ui.regular, SUB);
        heroText.setMaxLines(3);
        heroText.setEllipsize(TextUtils.TruncateAt.END);
        heroText.setLineSpacing(px(3), 1f);
        hero.addView(heroText, new LinearLayout.LayoutParams(px(520), -2));
        column.addView(hero, new LinearLayout.LayoutParams(-1, px(170)));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, px(60));
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rowsBox);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // Live sports (filled by fillSports), then the title rows (loadRows), then the apps.
        sportsRow = new LinearLayout(this);
        sportsBlock = rowBlock(Ui.tr("Live & today in sports"), sportsRow);
        sportsBlock.setVisibility(View.GONE);
        rowsBox.addView(sportsBlock);

        heroTitle.setText(Discover.hasKey() ? Ui.tr("Loading…") : Ui.tr("Add a TMDB key to see what's on"));
        updateClock();
        return root;
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Ui.semibold);
        b.setTextColor(TEXT);
        b.setPadding(px(18), px(8), px(18), px(8));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(px(20));
        normal.setColor(Color.argb(60, 255, 255, 255));
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(px(20));
        focused.setColor(Color.WHITE);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, focused);
        bg.addState(new int[] {}, normal);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> b.setTextColor(has ? BG : TEXT));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(10);
        b.setLayoutParams(lp);
        return b;
    }

    /** A row: its heading and a sideways list. */
    private View rowBlock(String title, LinearLayout items) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, px(6), 0, px(6));
        TextView heading = text(16, Ui.semibold, TEXT);
        heading.setText(title);
        heading.setPadding(0, 0, 0, px(8));
        block.addView(heading);
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.setPadding(px(4), px(10), px(60), px(10));
        items.setOrientation(LinearLayout.HORIZONTAL);
        h.addView(items);
        block.addView(h);
        return block;
    }

    /** A focusable card that grows and gets a white ring under the remote's focus. */
    private FrameLayout card(int w, int h, float radius) {
        FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(Color.rgb(26, 31, 48));
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(px(3), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        f.setForeground(null);
        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(140).start();
            v.setForeground(has ? ring : null);
            v.setElevation(has ? px(8) : 0);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = px(14);
        f.setLayoutParams(lp);
        return f;
    }

    // MARK: - Title rows

    private void loadRows() {
        if (!Discover.hasKey()) {
            addApps();
            return;
        }
        io.execute(() -> {
            List<Discover.Row> rows = Discover.rows(this, false);
            main.post(() -> {
                if (isFinishing()) return;
                for (Discover.Row r : rows) addTitleRow(r);
                addApps();
                if (rows.isEmpty()) heroTitle.setText(Ui.tr("Couldn't load. Check the connection."));
                if (firstCard != null) firstCard.requestFocus();
            });
        });
    }

    private void addTitleRow(Discover.Row row) {
        LinearLayout items = new LinearLayout(this);
        for (Discover.Title t : row.items) {
            FrameLayout c = card(px(118), px(177), px(10));
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            c.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            load(Discover.POSTER + t.poster, poster, 342);
            c.setContentDescription(t.name);
            View.OnFocusChangeListener grow = c.getOnFocusChangeListener();
            c.setOnFocusChangeListener((v, has) -> {
                grow.onFocusChange(v, has);
                if (has) showHero(t);
            });
            c.setOnClickListener(v -> openInNuvio(t));
            items.addView(c);
            if (firstCard == null) firstCard = c;
        }
        rowsBox.addView(rowBlock(row.title, items));
    }

    private void showHero(Discover.Title t) {
        heroTitle.setText(t.name);
        List<String> meta = new ArrayList<>();
        meta.add("tv".equals(t.kind) ? Ui.tr("Series") : Ui.tr("Movie"));
        if (!t.year.isEmpty()) meta.add(t.year);
        if (t.rating > 0) meta.add(String.format(Locale.US, "★ %.1f", t.rating));
        heroMeta.setText(TextUtils.join("  ·  ", meta));
        heroText.setText(t.overview);
        backdropWanted = t.backdrop == null ? null : Discover.BACKDROP + t.backdrop;
        main.removeCallbacks(loadBackdrop);
        main.postDelayed(loadBackdrop, 220);
    }

    private void openInNuvio(Discover.Title t) {
        String nuvio = Recs.nuvio(this);
        if (nuvio == null) {
            Toast.makeText(this, Ui.tr("Nuvio isn't installed."), Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("nuvio://tmdb/" + ("tv".equals(t.kind) ? "tv" : "movie") + "/" + t.id))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(nuvio).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, Ui.trf("Couldn't open {app}.", "app", "Nuvio"), Toast.LENGTH_LONG).show();
        }
    }

    // MARK: - Live sports

    private void fillSports() {
        if (sportsRow == null) return;
        boolean hadFocus = sportsRow.hasFocus();
        sportsRow.removeAllViews();
        Sports s = Sports.get(this);
        List<Sports.Event> all = new ArrayList<>();
        for (String sport : Sports.order(this)) {
            if (!Sports.shown(this, sport)) continue;
            List<Sports.Event> list = s.list(sport, true);
            for (int i = 0; i < list.size() && i < 6; i++) if (!"post".equals(list.get(i).state)) all.add(list.get(i));
        }
        Collections.sort(all, (a, b) -> {
            boolean la = "in".equals(a.state), lb = "in".equals(b.state);
            if (la != lb) return la ? -1 : 1;
            return Long.compare(a.start, b.start);
        });
        for (int i = 0; i < all.size() && i < 14; i++) {
            Sports.Event ev = all.get(i);
            FrameLayout c = card(px(250), px(96), px(12));
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(px(12), px(10), px(12), px(10));
            boolean live = "in".equals(ev.state);
            TextView when = text(11, Ui.semibold, live ? LIVE : ACCENT);
            when.setText(live ? (ev.clock.isEmpty() || Sports.CRICKET.equals(ev.sport) ? "● " + Ui.tr("LIVE") : "● " + Ui.tr("LIVE") + "  " + ev.clock)
                    : Sports.label(ev.sport) + "  ·  " + when(ev.start));
            box.addView(when);
            TextView title = text(14, Ui.semibold, TEXT);
            title.setText(ev.title);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, px(6), 0, px(4));
            box.addView(title);
            TextView detail = text(11, Ui.regular, DIM);
            detail.setText(live && Sports.CRICKET.equals(ev.sport) && !ev.clock.isEmpty() ? ev.clock : ev.detail);
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(detail);
            c.addView(box, new FrameLayout.LayoutParams(-1, -1));
            c.setOnClickListener(v -> {
                Intent intent = Sports.openIntent(this, ev);
                if (intent == null) {
                    Toast.makeText(this, Ui.tr("No IPTV app found on this TV."), Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    Toast.makeText(this, Ui.trf("Couldn't open {app}.", "app", Sports.appName(this, Sports.app(this))), Toast.LENGTH_LONG).show();
                }
            });
            sportsRow.addView(c);
        }
        sportsBlock.setVisibility(sportsRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        if (hadFocus && sportsRow.getChildCount() > 0) sportsRow.getChildAt(0).requestFocus();
    }

    private static String when(long at) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(at);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return today ? Ui.time(at) : Ui.trf("Tomorrow {time}", "time", Ui.time(at));
    }

    // MARK: - Apps

    private void addApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        List<String> seen = new ArrayList<>();
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
            seen.add(pkg);
            apps.add(r);
        }
        Collections.sort(apps, (a, b) -> {
            int ia = FIRST_APPS.indexOf(a.activityInfo.packageName), ib = FIRST_APPS.indexOf(b.activityInfo.packageName);
            if (ia < 0) ia = 999;
            if (ib < 0) ib = 999;
            if (ia != ib) return ia - ib;
            return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
        });
        LinearLayout items = new LinearLayout(this);
        for (ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            FrameLayout c = card(px(170), px(96), px(10));
            Drawable art = r.activityInfo.loadBanner(pm);
            if (art == null) art = r.activityInfo.applicationInfo.loadBanner(pm);
            ImageView img = new ImageView(this);
            if (art != null) {
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setImageDrawable(art);
                c.addView(img, new FrameLayout.LayoutParams(-1, -1));
            } else {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                img.setImageDrawable(r.loadIcon(pm));
                box.addView(img, new LinearLayout.LayoutParams(px(40), px(40)));
                TextView name = text(12, Ui.medium, TEXT);
                name.setText(r.loadLabel(pm));
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(px(8), px(6), px(8), 0);
                box.addView(name);
                c.addView(box, new FrameLayout.LayoutParams(-1, -1));
            }
            String label = String.valueOf(r.loadLabel(pm));
            View.OnFocusChangeListener grow = c.getOnFocusChangeListener();
            c.setOnFocusChangeListener((v, has) -> {
                grow.onFocusChange(v, has);
                if (has) {
                    heroTitle.setText(label);
                    heroMeta.setText(Ui.tr("App"));
                    heroText.setText("");
                }
            });
            c.setOnClickListener(v -> {
                Intent launch = HomeApps.launchIntent(pm, pkg);
                if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
            items.addView(c);
            if (firstCard == null) firstCard = c;
        }
        if (items.getChildCount() > 0) rowsBox.addView(rowBlock(Ui.tr("Your apps"), items));
    }

    // MARK: - Surprise me

    private void surprise() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(24), px(18), px(24), px(18));
        box.setBackgroundColor(Color.rgb(18, 22, 36));
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setView(box).create();
        showMoods(dialog, box);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private void showMoods(AlertDialog dialog, LinearLayout box) {
        box.removeAllViews();
        TextView title = text(20, Ui.semibold, TEXT);
        title.setText("🎲  " + Ui.tr("What are you in the mood for?"));
        title.setPadding(0, 0, 0, px(14));
        box.addView(title);
        String[][] moods = Discover.moods();
        LinearLayout line = null;
        Button first = null;
        for (int i = 0; i < moods.length; i++) {
            if (i % 3 == 0) {
                line = new LinearLayout(this);
                line.setPadding(0, 0, 0, px(10));
                box.addView(line);
            }
            String mood = moods[i][1];
            Button b = button(moods[i][0], v -> roll(dialog, box, mood));
            ((LinearLayout.LayoutParams) b.getLayoutParams()).leftMargin = i % 3 == 0 ? 0 : px(10);
            line.addView(b);
            if (first == null) first = b;
        }
        if (first != null) first.requestFocus();
    }

    private void roll(AlertDialog dialog, LinearLayout box, String mood) {
        box.removeAllViews();
        TextView wait = text(16, Ui.medium, SUB);
        wait.setText(Ui.tr("Picking something good…"));
        box.addView(wait);
        io.execute(() -> {
            Discover.Title t;
            try {
                t = Discover.surprise(mood);
            } catch (Exception e) {
                t = null;
            }
            Discover.Title pick = t;
            main.post(() -> {
                if (!dialog.isShowing()) return;
                if (pick == null) {
                    wait.setText(Ui.tr("Couldn't load. Check the connection."));
                    return;
                }
                showPick(dialog, box, mood, pick);
            });
        });
    }

    private void showPick(AlertDialog dialog, LinearLayout box, String mood, Discover.Title t) {
        box.removeAllViews();
        LinearLayout row = new LinearLayout(this);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setClipToOutline(true);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(10));
            }
        });
        row.addView(poster, new LinearLayout.LayoutParams(px(140), px(210)));
        load(Discover.POSTER + t.poster, poster, 342);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(px(20), 0, 0, 0);
        TextView name = text(22, Ui.displaySemibold, TEXT);
        name.setText(t.name);
        info.addView(name);
        TextView meta = text(13, Ui.medium, ACCENT);
        List<String> m = new ArrayList<>();
        m.add("tv".equals(t.kind) ? Ui.tr("Series") : Ui.tr("Movie"));
        if (!t.year.isEmpty()) m.add(t.year);
        if (t.rating > 0) m.add(String.format(Locale.US, "★ %.1f", t.rating));
        meta.setText(TextUtils.join("  ·  ", m));
        meta.setPadding(0, px(8), 0, px(8));
        info.addView(meta);
        TextView text = text(13, Ui.regular, SUB);
        text.setText(t.overview);
        text.setMaxLines(6);
        text.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(text, new LinearLayout.LayoutParams(px(380), -2));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, px(14), 0, 0);
        Button play = button("▶  " + Ui.tr("Open in Nuvio"), v -> {
            dialog.dismiss();
            openInNuvio(t);
        });
        ((LinearLayout.LayoutParams) play.getLayoutParams()).leftMargin = 0;
        buttons.addView(play);
        buttons.addView(button("🎲  " + Ui.tr("Another one"), v -> roll(dialog, box, mood)));
        buttons.addView(button(Ui.tr("Change mood"), v -> showMoods(dialog, box)));
        info.addView(buttons);
        row.addView(info);
        box.addView(row);
        play.requestFocus();
    }

    // MARK: - Clock and images

    private void updateClock() {
        if (clock != null) clock.setText(Ui.clock(System.currentTimeMillis()));
    }

    /** Loads [url] into [view] in the background (memory cache + the HTTP cache on disk), sized to [width]. */
    private void load(String url, ImageView view, int width) {
        view.setTag(url);
        Bitmap cached = images.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        io.execute(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setUseCaches(true);
                try (InputStream in = c.getInputStream()) {
                    byte[] data = readBytes(in);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 1;
                    while (bounds.outWidth / (opts.inSampleSize * 2) >= width) opts.inSampleSize *= 2;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    b = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                } finally {
                    c.disconnect();
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("discover image: " + e.getMessage());
            }
            Bitmap done = b;
            if (done != null) images.put(url, done);
            main.post(() -> {
                if (done != null && url.equals(view.getTag())) {
                    view.setImageBitmap(done);
                    if (view == backdrop) {
                        backdrop.setAlpha(0f);
                        backdrop.animate().alpha(0.55f).setDuration(260).start();
                    }
                }
            });
        });
    }

    private static byte[] readBytes(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // MENU reloads the rows (fresh from TMDB).
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
            io.execute(() -> {
                Discover.rows(this, true);
                main.post(this::recreate);
            });
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
