package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The Sports hub: a column per sport (cricket, football, wrestling, tennis) with what's live and what's next, in the
 * TV's time. OK on a game opens the IPTV app picked at the top (Debrify, TiviMate…). The row at the top switches
 * between today and the week, turns sports on or off (OK) and moves them (hold OK), and picks the app.
 * Opened from the Sports button on the home screen, or as its own app ("Sports") on TVs where Free My TV isn't the home.
 */
public class SportsActivity extends Activity {
    private SportsView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new SportsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class SportsView extends View implements Sports.Listener {
        private static final int BG = Color.rgb(9, 12, 22), CARD = Color.rgb(22, 27, 42), LINE = Color.argb(40, 255, 255, 255);
        private static final int TEXT = Color.WHITE, SUB = Color.argb(150, 255, 255, 255), DIM = Color.argb(95, 255, 255, 255);
        private static final int LIVE = Color.rgb(255, 69, 58), ACCENT = Color.rgb(110, 168, 255);
        private static final int[] SPORT_COLORS = {Color.rgb(64, 200, 140), Color.rgb(110, 168, 255), Color.rgb(255, 170, 60), Color.rgb(200, 120, 255)};

        private final Activity activity;
        private final Sports sports;
        private final Click click;
        private float k = 1;

        // Top row: [Today] [This week] | one chip per sport | [Opens in: …] [Refresh]
        private static final int Z_TOP = 0, Z_GAMES = 1;
        private int zone = Z_GAMES, topIndex, col, row;
        private boolean week;
        private final List<String> order = new ArrayList<>();
        private final List<String> columns = new ArrayList<>();       // the sports shown, in order
        private final List<List<Sports.Event>> games = new ArrayList<>();
        private final List<RectF> topRects = new ArrayList<>();
        private final int[] scroll = new int[Sports.ALL.length];
        private boolean okDown, okHeld;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private TextPaint title, heading, item, small, chip, timeLabel;
        private final RectF r = new RectF();
        private final SimpleDateFormat dayName = new SimpleDateFormat("EEE d", Ui.locale());

        SportsView(Activity a) {
            super(a);
            activity = a;
            sports = Sports.get(a);
            click = new Click(a);
            setFocusable(true);
            setBackgroundColor(BG);
            load();
        }

        /** Every minute while open: live scores when a game is on, otherwise the schedule when it's stale. */
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (sports.anyLive()) sports.refreshLive(); else sports.refreshIfStale();
                postDelayed(this, 60_000);
            }
        };

        void resume() {
            sports.addListener(this);
            sports.refreshIfStale();
            load();
            removeCallbacks(tick);
            postDelayed(tick, 60_000);
        }

        void pause() {
            sports.removeListener(this);
            removeCallbacks(tick);
        }

        @Override public void onSports() {
            load();
        }

        /** The sports and games as they are now (after a fetch, a toggle or a tab change). */
        private void load() {
            Context c = getContext();
            order.clear();
            order.addAll(Sports.order(c));
            columns.clear();
            games.clear();
            for (String s : order) {
                if (!Sports.shown(c, s)) continue;
                columns.add(s);
                games.add(sports.list(s, !week));
            }
            col = Math.min(col, Math.max(0, columns.size() - 1));
            clampRow();
            invalidate();
        }

        private void clampRow() {
            int n = col < games.size() ? games.get(col).size() : 0;
            if (zone == Z_GAMES && n == 0) row = 0;
            row = Math.max(0, Math.min(row, Math.max(0, n - 1)));
        }

        private int topCount() {
            return 2 + order.size() + 2;
        }

        // MARK: - Keys

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int code = e.getKeyCode();
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (!down) return true;
                    if (zone == Z_TOP) {
                        zone = Z_GAMES;
                        invalidate();
                        return true;
                    }
                    activity.finish();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) {
                            okDown = true;
                            okHeld = false;
                        } else if (!okHeld && e.getRepeatCount() >= 2) {
                            okHeld = true;
                            hold();
                        }
                    } else if (okDown) {
                        okDown = false;
                        if (!okHeld) press();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(code);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    if (down) {
                        zone = Z_TOP;
                        invalidate();
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void move(int code) {
            int before = zone * 10000 + topIndex * 100 + col * 1000 + row;
            if (zone == Z_TOP) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) topIndex = Math.max(0, topIndex - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) topIndex = Math.min(topCount() - 1, topIndex + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && !columns.isEmpty()) zone = Z_GAMES;
            } else {
                int n = col < games.size() ? games.get(col).size() : 0;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (row > 0) row--;
                    else zone = Z_TOP;
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (row < n - 1) row++;
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) {
                    col--;
                    clampRow();
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && col < columns.size() - 1) {
                    col++;
                    clampRow();
                }
            }
            int after = zone * 10000 + topIndex * 100 + col * 1000 + row;
            if (after != before) {
                click.move();
                invalidate();
            }
        }

        private void press() {
            Context c = getContext();
            if (zone == Z_GAMES) {
                if (col >= games.size() || games.get(col).isEmpty()) return;
                open();
                return;
            }
            click.open();
            int i = topIndex;
            if (i == 0 || i == 1) {
                week = i == 1;
            } else if (i < 2 + order.size()) {
                String s = order.get(i - 2);
                Sports.setShown(c, s, !Sports.shown(c, s));
            } else if (i == 2 + order.size()) {
                List<String[]> apps = Sports.installedApps(c);
                if (apps.isEmpty()) {
                    Toast.makeText(c, Ui.tr("No IPTV app found on this TV."), Toast.LENGTH_LONG).show();
                    return;
                }
                String now = Sports.app(c);
                int at = -1;
                for (int a = 0; a < apps.size(); a++) if (apps.get(a)[0].equals(now)) at = a;
                Sports.setApp(c, apps.get((at + 1) % apps.size())[0]);
            } else {
                sports.refresh();
            }
            load();
        }

        /** Holding OK on a sport moves it one place to the left (the first goes to the end). */
        private void hold() {
            if (zone != Z_TOP || topIndex < 2 || topIndex >= 2 + order.size()) return;
            int i = topIndex - 2;
            List<String> list = new ArrayList<>(order);
            String s = list.remove(i);
            int to = i == 0 ? list.size() : i - 1;
            list.add(to, s);
            Sports.setOrder(getContext(), list);
            topIndex = 2 + to;
            click.open();
            load();
        }

        private void open() {
            Context c = getContext();
            List<Sports.Event> list = col < games.size() ? games.get(col) : null;
            Sports.Event ev = list == null || row >= list.size() ? null : list.get(row);
            Intent intent = Sports.openIntent(c, ev);
            if (intent == null) {
                Toast.makeText(c, Ui.tr("No IPTV app found on this TV."), Toast.LENGTH_LONG).show();
                return;
            }
            click.open();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                activity.startActivity(intent);
            } catch (RuntimeException e) {
                Toast.makeText(c, Ui.trf("Couldn't open {app}.", "app", Sports.appName(c, Sports.app(c))), Toast.LENGTH_LONG).show();
            }
        }

        // MARK: - Drawing (1920×1080 design, scaled)

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            title = paint(Ui.displayMedium, 52, TEXT);
            heading = paint(Ui.semibold, 21, SUB);
            heading.setLetterSpacing(0.08f);
            item = paint(Ui.semibold, 27, TEXT);
            small = paint(Ui.regular, 20, SUB);
            chip = paint(Ui.medium, 22, TEXT);
            timeLabel = paint(Ui.medium, 19, SUB);
        }

        private TextPaint paint(android.graphics.Typeface face, float size, int color) {
            TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            p.setTypeface(face);
            p.setTextSize(size * k);
            p.setColor(color);
            return p;
        }

        @Override protected void onDraw(Canvas c) {
            if (title == null) return;
            Context ctx = getContext();
            float pad = 96 * k;
            // A soft glow at the top, like the home screen's sky.
            fill.setShader(new LinearGradient(0, 0, 0, 420 * k, Color.argb(70, 60, 90, 180), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), 420 * k, fill);
            fill.setShader(null);

            c.drawText(Ui.tr("Sports"), pad, 120 * k, title);
            String app = Sports.app(ctx);
            String sub = sports.loading() ? Ui.tr("Updating…")
                    : sports.fetchedAt() > 0 ? Ui.trf("Updated {time}", "time", Ui.time(sports.fetchedAt())) : "";
            if (!sports.error().isEmpty() && columns.size() > 0 && total() == 0) sub = Ui.tr("Couldn't load the games. Check the connection.");
            c.drawText(sub, pad, 162 * k, small);

            // Top row
            topRects.clear();
            float x = pad, y = 200 * k, h = 58 * k;
            List<String> labels = new ArrayList<>();
            labels.add(Ui.tr("Live & today"));
            labels.add(Ui.tr("This week"));
            for (String s : order) labels.add((Sports.shown(ctx, s) ? "✓  " : "✕  ") + Sports.label(s));
            labels.add(Ui.trf("Opens in: {app}", "app", app == null ? Ui.tr("no IPTV app") : Sports.appName(ctx, app)));
            labels.add(Ui.tr("Refresh"));
            for (int i = 0; i < labels.size(); i++) {
                if (i == 2 || i == 2 + order.size()) x += 24 * k;   // gaps between the groups
                float w = chip.measureText(labels.get(i)) + 48 * k;
                RectF rect = new RectF(x, y, x + w, y + h);
                topRects.add(rect);
                boolean focused = zone == Z_TOP && topIndex == i;
                boolean selected = (i == 0 && !week) || (i == 1 && week);
                boolean off = i >= 2 && i < 2 + order.size() && !Sports.shown(ctx, order.get(i - 2));
                fill.setColor(focused ? Color.WHITE : selected ? Color.argb(70, 110, 168, 255) : Color.argb(28, 255, 255, 255));
                c.drawRoundRect(rect, h / 2, h / 2, fill);
                chip.setColor(focused ? BG : off ? DIM : TEXT);
                c.drawText(labels.get(i), x + 24 * k, y + h / 2 + Ui.halfCap(chip), chip);
                x += w + 14 * k;
            }
            chip.setColor(TEXT);

            // Columns
            float top = 310 * k, bottom = getHeight() - 90 * k;
            if (columns.isEmpty()) {
                small.setColor(SUB);
                c.drawText(Ui.tr("Every sport is off. Turn one on above."), pad, top + 60 * k, small);
                return;
            }
            float gap = 28 * k;
            float colW = (getWidth() - 2 * pad - gap * (columns.size() - 1)) / columns.size();
            for (int ci = 0; ci < columns.size(); ci++) {
                float cx = pad + ci * (colW + gap);
                String sport = columns.get(ci);
                List<Sports.Event> list = games.get(ci);
                int color = SPORT_COLORS[java.util.Arrays.asList(Sports.ALL).indexOf(sport)];
                fill.setColor(color);
                c.drawCircle(cx + 8 * k, top - 6 * k, 6 * k, fill);
                heading.setColor(zone == Z_GAMES && ci == col ? TEXT : SUB);
                c.drawText(Sports.label(sport).toUpperCase(Ui.locale()) + "  ·  " + list.size(), cx + 26 * k, top, heading);
                fill.setColor(LINE);
                c.drawRect(cx, top + 16 * k, cx + colW, top + 18 * k, fill);

                float rowH = 128 * k, y0 = top + 34 * k;
                int fits = Math.max(1, (int) ((bottom - y0) / rowH));
                int sc = scroll[ci];
                if (ci == col) {
                    if (row < sc) sc = row;
                    if (row >= sc + fits) sc = row - fits + 1;
                }
                sc = Math.max(0, Math.min(sc, Math.max(0, list.size() - fits)));
                scroll[ci] = sc;
                if (list.isEmpty()) {
                    small.setColor(DIM);
                    c.drawText(sports.loading() ? Ui.tr("Loading…") : week ? Ui.tr("Nothing this week") : Ui.tr("Nothing today"),
                            cx + 4 * k, y0 + 40 * k, small);
                    small.setColor(SUB);
                    if (zone == Z_GAMES && ci == col) {
                        r.set(cx - 12 * k, y0, cx + colW + 12 * k, y0 + 64 * k);
                        fill.setColor(Color.argb(30, 255, 255, 255));
                        c.drawRoundRect(r, 18 * k, 18 * k, fill);
                    }
                    continue;
                }
                for (int i = sc; i < list.size() && i < sc + fits; i++) {
                    Sports.Event ev = list.get(i);
                    float ry = y0 + (i - sc) * rowH;
                    boolean focused = zone == Z_GAMES && ci == col && i == row;
                    if (focused) {
                        r.set(cx - 14 * k, ry - 4 * k, cx + colW + 14 * k, ry + rowH - 14 * k);
                        fill.setColor(CARD);
                        c.drawRoundRect(r, 20 * k, 20 * k, fill);
                        fill.setColor(Color.WHITE);
                        fill.setStyle(Paint.Style.STROKE);
                        fill.setStrokeWidth(3 * k);
                        c.drawRoundRect(r, 20 * k, 20 * k, fill);
                        fill.setStyle(Paint.Style.FILL);
                    }
                    // Status line: LIVE, or when it starts
                    boolean live = "in".equals(ev.state), done = "post".equals(ev.state);
                    float ty = ry + 26 * k;
                    fill.setColor(live ? LIVE : done ? DIM : ev.priority >= 9 ? ACCENT : color);
                    c.drawCircle(cx + 6 * k, ty - 7 * k, 5 * k, fill);
                    timeLabel.setColor(live ? LIVE : SUB);
                    boolean longClock = Sports.CRICKET.equals(ev.sport);   // cricket's clock is a sentence: it goes below
                    String when = live ? (ev.clock.isEmpty() || longClock ? Ui.tr("LIVE") : Ui.tr("LIVE") + "  ·  " + ev.clock)
                            : done ? Ui.tr("Finished") : when(ev.start);
                    String under = longClock && !ev.clock.isEmpty() && (live || done) ? ev.clock : ev.detail;
                    c.drawText(when, cx + 20 * k, ty, timeLabel);
                    // Title on up to two lines, then the league
                    item.setColor(done ? SUB : TEXT);
                    String t = ev.title == null ? "" : ev.title;
                    float tw = colW - 8 * k;
                    int cut = item.breakText(t, true, tw, null);
                    if (cut >= t.length()) {
                        c.drawText(t, cx, ry + 62 * k, item);
                        c.drawText(TextUtils.ellipsize(under, small, tw, TextUtils.TruncateAt.END).toString(), cx, ry + 94 * k, small);
                    } else {
                        int space = t.lastIndexOf(' ', cut);
                        if (space <= 0) space = cut;
                        c.drawText(t.substring(0, space), cx, ry + 62 * k, item);
                        c.drawText(TextUtils.ellipsize(t.substring(space).trim(), item, tw, TextUtils.TruncateAt.END).toString(), cx, ry + 94 * k, item);
                    }
                }
                if (list.size() > sc + fits) {
                    small.setColor(DIM);
                    c.drawText(Ui.trf("{n} more ↓", "n", list.size() - sc - fits), cx, bottom + 30 * k, small);
                    small.setColor(SUB);
                }
            }
            // Hint
            small.setColor(DIM);
            String hint = zone == Z_TOP ? Ui.tr("OK: choose · Hold OK on a sport: move it left")
                    : Ui.trf("OK: open {app} · ▲ at the top: settings", "app", app == null ? "IPTV" : Sports.appName(ctx, app));
            c.drawText(hint, getWidth() - pad - small.measureText(hint), getHeight() - 40 * k, small);
            small.setColor(SUB);
            if (sports.anyLive()) postInvalidateDelayed(30_000);   // "LIVE" turns into "Finished" by itself
        }

        private int total() {
            int n = 0;
            for (List<Sports.Event> l : games) n += l.size();
            return n;
        }

        /** "19:30", "Tomorrow 19:30", "Sat 25 19:30". */
        private String when(long at) {
            Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
            then.setTimeInMillis(at);
            int days = (then.get(Calendar.YEAR) - now.get(Calendar.YEAR)) * 366 + then.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR);
            String time = Ui.time(at);
            if (days == 0) return Ui.trf("Today {time}", "time", time);
            if (days == 1) return Ui.trf("Tomorrow {time}", "time", time);
            return dayName.format(at) + "  " + time;
        }
    }
}
