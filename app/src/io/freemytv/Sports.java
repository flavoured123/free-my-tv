package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Sports hub's schedule: cricket (every series, India first), football (Bundesliga, the big leagues, Champions
 * League and national teams), WWE and tennis. Free sources without keys: ESPN's public scoreboards and, for WWE's
 * premium events, TheSportsDB's free key; WWE's weekly shows (RAW, NXT, SmackDown) come from their fixed slots.
 * Fetched in the background every 2 hours (every 10 minutes while something is live), kept on disk between starts.
 */
final class Sports {
    static final String CRICKET = "cricket", FOOTBALL = "football", WRESTLING = "wrestling", TENNIS = "tennis";
    static final String[] ALL = {CRICKET, FOOTBALL, WRESTLING, TENNIS};

    static final class Event {
        String id = "";
        String sport, league, title, detail, state;   // state: "pre", "in" (live) or "post"
        String home = "", away = "", homeAbbr = "", awayAbbr = "";   // teams or players, for finding the channel
        String clock = "";   // while live: "67'", "HT", or cricket's "India need 45 runs from 30 balls"
        long start;
        int priority;      // higher first among the same day: India, Bundesliga, national teams…
    }

    interface Listener { void onSports(); }

    /** ESPN football leagues: slug, name, priority. Today's games of every league come from the scoreboard header. */
    private static final String[][] FOOTBALL_LEAGUES = {
        {"ger.1", "Bundesliga", "9"},
        {"uefa.champions", "Champions League", "8"},
        {"fifa.world", "FIFA World Cup", "8"},
        {"fifa.worldq.uefa", "World Cup Qualifying", "7"},
        {"uefa.nations", "Nations League", "7"},
        {"uefa.euro", "EURO", "8"},
        {"fifa.friendly", "Internationals", "6"},
        {"ger.dfb_pokal", "DFB-Pokal", "6"},
        {"eng.1", "Premier League", "5"},
        {"esp.1", "LaLiga", "5"},
        {"uefa.europa", "Europa League", "4"},
        {"ita.1", "Serie A", "3"},
        {"fra.1", "Ligue 1", "2"},
    };
    /** Leagues looked up day by day for the week ahead (the rest only show on their match day). */
    private static final int WEEK_LEAGUES = 8;
    private static final int DAYS_AHEAD = 6;
    private static final String ESPN = "https://site.api.espn.com/apis";
    private static final long EVERY = 2 * 3600_000L, EVERY_LIVE = 10 * 60_000L;

    /** Known IPTV apps the hub can open, in this order of preference: package, name. */
    static final String[][] IPTV_APPS = {
        {"com.debrify.app.personal", "Debrify Personal"},
        {"com.debrify.app", "Debrify"},
        {"ar.tvplayer.tv", "TiviMate"},
        {"studio.scillarium.ottnavigator", "OTT Navigator"},
        {"se.hedekonsult.sparkle", "Sparkle"},
        {"com.nst.iptvsmarterstvbox", "IPTV Smarters"},
        {"com.niklabs.pp", "Perfect Player"},
    };

    private static Sports instance;

    static synchronized Sports get(Context c) {
        if (instance == null) instance = new Sports(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Event> events = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;
    private volatile String error = "";

    private Sports(Context c) {
        context = c;
        try (FileInputStream in = new FileInputStream(file())) {
            JSONObject o = new JSONObject(read(in));
            fetchedAt = o.optLong("at");
            events = parse(o.optJSONArray("events"));
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    String error() { return error; }
    boolean loading() { return fetching; }
    long fetchedAt() { return fetchedAt; }

    /** Everything for one sport, from now on (live first, then by start; finished games stay 3 hours). */
    List<Event> list(String sport, boolean todayOnly) {
        long now = System.currentTimeMillis();
        long endOfDay = endOfDay(now);
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (!sport.equals(e.sport)) continue;
            if ("post".equals(e.state) && now - e.start > 5 * 3600_000L) continue;
            if (!"in".equals(e.state) && !"post".equals(e.state) && e.start < now - 6 * 3600_000L) continue;   // stale
            if (todayOnly && e.start > endOfDay && !"in".equals(e.state)) continue;
            out.add(e);
        }
        Collections.sort(out, (a, b) -> {
            int la = rank(a), lb = rank(b);
            if (la != lb) return la - lb;
            boolean sameDay = day(a.start) == day(b.start);
            if (sameDay && a.priority != b.priority) return b.priority - a.priority;
            return Long.compare(a.start, b.start);
        });
        return out;
    }

    /** Live first, then upcoming, finished last. */
    private static int rank(Event e) {
        return "in".equals(e.state) ? 0 : "post".equals(e.state) ? 2 : 1;
    }

    boolean anyLive() {
        for (Event e : events) if ("in".equals(e.state)) return true;
        return false;
    }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        long every = anyLive() ? EVERY_LIVE : EVERY;
        if (fetching || (now - fetchedAt < every && now >= fetchedAt && !events.isEmpty())) return;
        refresh();
    }

    void refresh() {
        if (fetching) return;
        fetching = true;
        notifyListeners();
        io.execute(() -> {
            List<Event> all = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            try { cricket(all); } catch (Exception e) { failed.add("cricket: " + e.getMessage()); }
            try { football(all); } catch (Exception e) { failed.add("football: " + e.getMessage()); }
            try { wrestling(all); } catch (Exception e) { failed.add("wrestling: " + e.getMessage()); }
            try { tennis(all); } catch (Exception e) { failed.add("tennis: " + e.getMessage()); }
            if (!all.isEmpty()) {
                events = all;
                fetchedAt = System.currentTimeMillis();
                save();
            }
            error = failed.size() == 4 ? failed.get(0) : "";
            for (String f : failed) AppLog.w("sports " + f);
            fetching = false;
            notifyListeners();
        });
    }

    /**
     * While the hub shows a live game: only the two small scoreboards (cricket and today's football), every minute,
     * and the games already listed take their new score, clock and state.
     */
    void refreshLive() {
        if (fetching) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Event> fresh = new ArrayList<>();
                try { cricket(fresh); } catch (Exception e) { AppLog.w("sports live cricket: " + e.getMessage()); }
                footballToday(fresh, new HashSet<>());
                java.util.Map<String, Event> byId = new java.util.HashMap<>();
                for (Event e : fresh) if (!e.id.isEmpty()) byId.put(e.id, e);
                List<Event> next = new ArrayList<>();
                for (Event old : events) {
                    Event now = byId.get(old.id);
                    if (now == null || !now.sport.equals(old.sport)) {
                        next.add(old);
                        continue;
                    }
                    now.priority = old.priority;
                    next.add(now);
                    byId.remove(old.id);
                }
                next.addAll(byId.values());   // a game that just started in a series not listed before
                events = next;
                save();
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onSports(); });
    }

    // MARK: - Cricket: ESPN's scoreboard header lists the live or next game of every series running now

    private void cricket(List<Event> out) throws Exception {
        JSONObject o = get(ESPN + "/personalized/v2/scoreboard/header?sport=cricket&region=in&lang=en");
        JSONArray sports = o.optJSONArray("sports");
        Set<String> seen = new HashSet<>();
        for (int s = 0; sports != null && s < sports.length(); s++) {
            JSONArray leagues = sports.getJSONObject(s).optJSONArray("leagues");
            for (int l = 0; leagues != null && l < leagues.length(); l++) {
                JSONObject league = leagues.getJSONObject(l);
                String series = league.optString("name");
                JSONArray evs = league.optJSONArray("events");
                for (int i = 0; evs != null && i < evs.length(); i++) {
                    JSONObject e = evs.getJSONObject(i);
                    if (!seen.add(e.optString("id"))) continue;
                    Event ev = new Event();
                    ev.id = "espn:" + e.optString("id");
                    ev.sport = CRICKET;
                    ev.league = series;
                    ev.start = time(e.optString("date"));
                    ev.state = e.optString("status", "pre");
                    List<String> teams = new ArrayList<>();
                    JSONArray cs = e.optJSONArray("competitors");
                    for (int c = 0; cs != null && c < cs.length(); c++) {
                        JSONObject t = cs.getJSONObject(c);
                        String score = t.optString("score", "");
                        boolean shows = ("in".equals(ev.state) || "post".equals(ev.state)) && !score.isEmpty();
                        teams.add(t.optString("displayName") + (shows ? " " + score : ""));
                        if (c == 0) { ev.home = t.optString("displayName"); ev.homeAbbr = t.optString("abbreviation"); }
                        if (c == 1) { ev.away = t.optString("displayName"); ev.awayAbbr = t.optString("abbreviation"); }
                    }
                    if ("in".equals(ev.state) || "post".equals(ev.state)) ev.clock = e.optString("summary", "");
                    ev.title = teams.size() == 2 ? teams.get(0) + " v " + teams.get(1) : e.optString("name");
                    ev.detail = series;
                    String text = (series + " " + ev.title).toLowerCase(Locale.ROOT);
                    boolean india = text.contains("india");
                    boolean youth = text.contains("under-19") || text.contains("u19") || text.contains(" xi");
                    boolean women = text.contains("women");
                    ev.priority = (india ? 10 : 0) + (youth ? -2 : 0) + (women ? -1 : 0)
                            + (text.contains("icc") || text.contains("ipl") || text.contains("world cup") ? 3 : 0);
                    if (ev.start > 0) out.add(ev);
                }
            }
        }
    }

    // MARK: - Football

    private void football(List<Event> out) throws Exception {
        Set<String> seen = new HashSet<>();
        footballToday(out, seen);
        footballWeek(out, seen);
    }

    /** Today, every league ESPN has: kept only for the leagues above (and national teams). Also the live scores. */
    private void footballToday(List<Event> out, Set<String> seen) {
        try {
            JSONObject o = get(ESPN + "/personalized/v2/scoreboard/header?sport=soccer&region=de&lang=en");
            JSONArray sports = o.optJSONArray("sports");
            for (int s = 0; sports != null && s < sports.length(); s++) {
                JSONArray leagues = sports.getJSONObject(s).optJSONArray("leagues");
                for (int l = 0; leagues != null && l < leagues.length(); l++) {
                    JSONObject league = leagues.getJSONObject(l);
                    String[] known = league(league.optString("slug"));
                    if (known == null) continue;
                    JSONArray evs = league.optJSONArray("events");
                    for (int i = 0; evs != null && i < evs.length(); i++) {
                        JSONObject e = evs.getJSONObject(i);
                        if (!seen.add(e.optString("id"))) continue;
                        JSONArray cs = e.optJSONArray("competitors");
                        String[] home = new String[3], away = new String[3];
                        for (int c = 0; cs != null && c < cs.length(); c++) {
                            JSONObject t = cs.getJSONObject(c);
                            String[] side = "away".equals(t.optString("homeAway")) ? away : home;
                            side[0] = t.optString("displayName");
                            side[1] = t.optString("score", "");
                            side[2] = t.optString("abbreviation", "");
                        }
                        String state = e.optString("status", "pre");
                        String clock = e.optString("summary", "");
                        if (clock.isEmpty() || "Scheduled".equals(clock)) clock = e.optString("clock", "");
                        out.add(footballEvent(e.optString("id"), known, e.optString("date"), state, clock, home, away));
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w("sports football today: " + e.getMessage());
        }
    }

    /** The week ahead, day by day, for the main leagues. */
    private void footballWeek(List<Event> out, Set<String> seen) throws Exception {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        int failures = 0, tries = 0;
        for (int d = 0; d <= DAYS_AHEAD; d++) {
            String date = f.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, 1);
            for (int i = 0; i < WEEK_LEAGUES; i++) {
                String[] known = FOOTBALL_LEAGUES[i];
                tries++;
                try {
                    JSONObject o = get(ESPN + "/site/v2/sports/soccer/" + known[0] + "/scoreboard?dates=" + date);
                    JSONArray evs = o.optJSONArray("events");
                    for (int k = 0; evs != null && k < evs.length(); k++) {
                        JSONObject e = evs.getJSONObject(k);
                        if (!seen.add(e.optString("id"))) continue;
                        JSONObject comp = e.optJSONArray("competitions") == null ? null : e.getJSONArray("competitions").optJSONObject(0);
                        JSONArray cs = comp == null ? null : comp.optJSONArray("competitors");
                        String[] home = new String[3], away = new String[3];
                        for (int c = 0; cs != null && c < cs.length(); c++) {
                            JSONObject t = cs.getJSONObject(c);
                            String[] side = "away".equals(t.optString("homeAway")) ? away : home;
                            JSONObject team = t.optJSONObject("team");
                            side[0] = team == null ? "" : team.optString("displayName");
                            side[1] = t.optString("score", "");
                            side[2] = team == null ? "" : team.optString("abbreviation", "");
                        }
                        JSONObject st = e.optJSONObject("status");
                        JSONObject type = st == null ? null : st.optJSONObject("type");
                        String state = type == null ? "pre" : type.optString("state", "pre");
                        String clock = type == null ? "" : type.optString("shortDetail", "");
                        out.add(footballEvent(e.optString("id"), known, e.optString("date"), state, clock, home, away));
                    }
                } catch (Exception e) {
                    failures++;
                }
            }
        }
        if (failures == tries && seen.isEmpty()) throw new IOException("no football data");
    }

    private static Event footballEvent(String id, String[] league, String date, String state, String clock, String[] home, String[] away) {
        Event ev = new Event();
        ev.id = "espn:" + id;
        ev.home = home[0] == null ? "" : home[0];
        ev.away = away[0] == null ? "" : away[0];
        ev.homeAbbr = home[2] == null ? "" : home[2];
        ev.awayAbbr = away[2] == null ? "" : away[2];
        ev.clock = "in".equals(state) ? clock : "";
        ev.sport = FOOTBALL;
        ev.league = league[1];
        ev.start = time(date);
        ev.state = state;
        boolean scores = ("in".equals(state) || "post".equals(state)) && home[1] != null && !home[1].isEmpty();
        ev.title = scores ? home[0] + " " + home[1] + " – " + away[1] + " " + away[0] : home[0] + " v " + away[0];
        ev.detail = league[1];
        ev.priority = Integer.parseInt(league[2]);
        return ev;
    }

    private static String[] league(String slug) {
        for (String[] l : FOOTBALL_LEAGUES) if (l[0].equals(slug)) return l;
        return null;
    }

    // MARK: - WWE: the weekly shows in their fixed slots, and the next premium event from TheSportsDB

    private void wrestling(List<Event> out) {
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        String[][] weekly = {
            {"RAW", String.valueOf(Calendar.MONDAY), "Netflix"},
            {"NXT", String.valueOf(Calendar.TUESDAY), "The CW"},
            {"SmackDown", String.valueOf(Calendar.FRIDAY), "USA Network"},
        };
        long now = System.currentTimeMillis();
        for (String[] show : weekly) {
            Calendar c = Calendar.getInstance(ny);
            c.setTimeInMillis(now - 3 * 3600_000L);
            c.set(Calendar.HOUR_OF_DAY, 20);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            int want = Integer.parseInt(show[1]);
            int add = (want - c.get(Calendar.DAY_OF_WEEK) + 7) % 7;
            c.add(Calendar.DAY_OF_YEAR, add);
            if (c.getTimeInMillis() + 3 * 3600_000L < now) c.add(Calendar.DAY_OF_YEAR, 7);
            Event ev = new Event();
            ev.id = "wwe:" + show[0];
            ev.sport = WRESTLING;
            ev.league = "WWE";
            ev.title = "WWE " + show[0];
            ev.detail = "WWE · " + show[2];
            ev.start = c.getTimeInMillis();
            ev.state = now >= ev.start && now < ev.start + 3 * 3600_000L ? "in" : "pre";
            ev.priority = 5;
            out.add(ev);
        }
        try {
            JSONObject o = get("https://www.thesportsdb.com/api/v1/json/123/eventsnextleague.php?id=4444");
            JSONArray evs = o.optJSONArray("events");
            for (int i = 0; evs != null && i < evs.length(); i++) {
                JSONObject e = evs.getJSONObject(i);
                String name = e.optString("strEvent");
                String n = name.toLowerCase(Locale.ROOT);
                if (n.startsWith("raw") || n.startsWith("smackdown") || n.startsWith("nxt") || n.startsWith("main event")
                        || n.startsWith("evolve")) continue;   // the weekly shows are above
                Event ev = new Event();
                ev.id = "tsdb:" + e.optString("idEvent");
                ev.sport = WRESTLING;
                ev.league = "WWE";
                ev.title = name;
                ev.detail = "WWE · PLE";
                ev.start = time(e.optString("strTimestamp"));
                ev.state = "pre";
                ev.priority = 9;
                if (ev.start > 0) out.add(ev);
            }
        } catch (Exception e) {
            AppLog.w("sports wwe events: " + e.getMessage());
        }
    }

    // MARK: - Tennis: singles matches of the tournaments running now, today and tomorrow

    private void tennis(List<Event> out) throws Exception {
        long now = System.currentTimeMillis();
        int failures = 0;
        for (String tour : new String[] {"atp", "wta"}) {
            try {
                JSONObject o = get(ESPN + "/site/v2/sports/tennis/" + tour + "/scoreboard");
                JSONArray evs = o.optJSONArray("events");
                for (int i = 0; evs != null && i < evs.length(); i++) {
                    JSONObject t = evs.getJSONObject(i);
                    String tournament = t.optString("name");
                    JSONArray groups = t.optJSONArray("groupings");
                    for (int g = 0; groups != null && g < groups.length(); g++) {
                        JSONObject group = groups.getJSONObject(g);
                        String kind = group.optJSONObject("grouping") == null ? "" : group.getJSONObject("grouping").optString("displayName");
                        if (!kind.contains("Singles")) continue;
                        JSONArray comps = group.optJSONArray("competitions");
                        for (int k = 0; comps != null && k < comps.length(); k++) {
                            JSONObject c = comps.getJSONObject(k);
                            long start = time(c.optString("date"));
                            JSONObject st = c.optJSONObject("status");
                            String state = st == null || st.optJSONObject("type") == null ? "pre" : st.getJSONObject("type").optString("state", "pre");
                            if (!"in".equals(state) && (start < now - 3 * 3600_000L || start > now + 36 * 3600_000L)) continue;
                            String round = c.optJSONObject("round") == null ? "" : c.getJSONObject("round").optString("displayName");
                            if (round.startsWith("Qualifying")) continue;
                            JSONArray cs = c.optJSONArray("competitors");
                            List<String> names = new ArrayList<>();
                            for (int p = 0; cs != null && p < cs.length(); p++) {
                                JSONObject a = cs.getJSONObject(p).optJSONObject("athlete");
                                names.add(a == null ? "?" : a.optString("displayName", "?"));
                            }
                            if (names.size() != 2) continue;
                            Event ev = new Event();
                            ev.id = "espn:" + c.optString("id");
                            ev.home = names.get(0);
                            ev.away = names.get(1);
                            ev.sport = TENNIS;
                            ev.league = tour.toUpperCase(Locale.ROOT);
                            ev.title = names.get(0) + " v " + names.get(1);
                            ev.detail = tour.toUpperCase(Locale.ROOT) + " · " + tournament + (round.isEmpty() ? "" : " · " + round);
                            ev.start = start;
                            ev.state = state;
                            ev.priority = round.contains("Final") ? 8 : round.contains("Semi") ? 6 : round.contains("Quarter") ? 4 : 0;
                            out.add(ev);
                        }
                    }
                }
            } catch (Exception e) {
                failures++;
            }
        }
        if (failures == 2) throw new IOException("no tennis data");
    }

    // MARK: - Which app a game opens in

    /** The IPTV app picked in the hub ("" = the first installed one of IPTV_APPS), if it's on the TV. */
    static String app(Context c) {
        SharedPreferences sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String chosen = sp.getString("sports_app", "");
        if (!chosen.isEmpty() && HomeApps.launchIntent(c.getPackageManager(), chosen) != null) return chosen;
        List<String[]> apps = installedApps(c);
        return apps.isEmpty() ? null : apps.get(0)[0];
    }

    static void setApp(Context c, String pkg) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("sports_app", pkg).apply();
    }

    /** The IPTV_APPS that are on the TV, in the order of that list. */
    static List<String[]> installedApps(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String[] a : IPTV_APPS) if (HomeApps.launchIntent(c.getPackageManager(), a[0]) != null) out.add(a);
        return out;
    }

    static String appName(Context c, String pkg) {
        for (String[] a : IPTV_APPS) if (a[0].equals(pkg)) return a[1];
        try {
            return String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /**
     * What OK on a game opens. Debrify gets the game itself (debrify://sports/match): it finds the channels showing
     * it in its playlists, best stream first. Other apps open on their home.
     */
    static Intent openIntent(Context c, Event ev) {
        String pkg = app(c);
        if (pkg == null) return null;
        if (pkg.startsWith("com.debrify.app") && ev != null) {
            android.net.Uri.Builder b = new android.net.Uri.Builder().scheme("debrify").authority("sports").path("match")
                    .appendQueryParameter("sport", WRESTLING.equals(ev.sport) ? "wwe" : ev.sport)
                    .appendQueryParameter("league", ev.league == null ? "" : ev.league)
                    .appendQueryParameter("start", String.valueOf(ev.start));
            if (!ev.home.isEmpty() && !ev.away.isEmpty()) {
                b.appendQueryParameter("home", ev.home).appendQueryParameter("away", ev.away);
                if (!ev.homeAbbr.isEmpty()) b.appendQueryParameter("homeAbbr", ev.homeAbbr);
                if (!ev.awayAbbr.isEmpty()) b.appendQueryParameter("awayAbbr", ev.awayAbbr);
            } else {
                b.appendQueryParameter("title", ev.title == null ? "" : ev.title);
            }
            return new Intent(Intent.ACTION_VIEW, b.build()).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE);
        }
        return HomeApps.launchIntent(c.getPackageManager(), pkg);
    }

    // MARK: - Which sports show, in which order (the hub's own settings)

    static List<String> order(Context c) {
        String saved = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("sports_order", "");
        List<String> out = new ArrayList<>();
        for (String s : saved.split(",")) if (java.util.Arrays.asList(ALL).contains(s) && !out.contains(s)) out.add(s);
        for (String s : ALL) if (!out.contains(s)) out.add(s);
        return out;
    }

    static void setOrder(Context c, List<String> order) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("sports_order", android.text.TextUtils.join(",", order)).apply();
    }

    static boolean shown(Context c, String sport) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("sport_" + sport, true);
    }

    static void setShown(Context c, String sport, boolean on) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("sport_" + sport, on).apply();
    }

    static String label(String sport) {
        switch (sport) {
            case CRICKET: return Ui.tr("Cricket");
            case FOOTBALL: return Ui.tr("Football");
            case WRESTLING: return Ui.tr("Wrestling");
            default: return Ui.tr("Tennis");
        }
    }

    // MARK: - Helpers

    private static long time(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        String s = iso.trim();
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mmXXX"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                f.setLenient(false);
                return f.parse(s).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static long endOfDay(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        // Late games (after midnight, up to 4:00) still count as tonight's.
        return c.getTimeInMillis() + 4 * 3600_000L;
    }

    private static int day(long at) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR);
    }

    private JSONObject get(String url) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) FreeMyTV");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private File file() {
        return new File(context.getFilesDir(), "sports.json");
    }

    private void save() {
        try (FileOutputStream out = new FileOutputStream(file())) {
            JSONArray a = new JSONArray();
            for (Event e : events) {
                a.put(new JSONObject().put("id", e.id).put("sport", e.sport).put("league", e.league).put("title", e.title).put("detail", e.detail)
                        .put("state", e.state).put("start", e.start).put("priority", e.priority).put("home", e.home).put("away", e.away)
                        .put("homeAbbr", e.homeAbbr).put("awayAbbr", e.awayAbbr).put("clock", e.clock));
            }
            out.write(new JSONObject().put("at", fetchedAt).put("events", a).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w("sports save: " + e.getMessage());
        }
    }

    private static List<Event> parse(JSONArray a) {
        List<Event> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Event e = new Event();
            e.sport = o.optString("sport");
            e.league = o.optString("league");
            e.title = o.optString("title");
            e.detail = o.optString("detail");
            e.state = o.optString("state", "pre");
            e.start = o.optLong("start");
            e.priority = o.optInt("priority");
            e.id = o.optString("id");
            e.home = o.optString("home");
            e.away = o.optString("away");
            e.homeAbbr = o.optString("homeAbbr");
            e.awayAbbr = o.optString("awayAbbr");
            e.clock = o.optString("clock");
            out.add(e);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
