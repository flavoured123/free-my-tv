package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The TV Home screen's rows (DiscoverActivity), from TMDB with the person's own key: trending this week, shows with a
 * new episode this week on the German streaming services, popular on Netflix and on Prime Video in the region, and the
 * "Surprise me" picker. Every title opens in Nuvio by its TMDB id. Rows are kept for 3 hours.
 */
final class Discover {
    private Discover() {}

    static final class Title {
        int id;
        String kind;    // "movie" or "tv"
        String name = "", poster, backdrop, overview = "", year = "";
        double rating;
    }

    static final class Row {
        String key, title;
        List<Title> items = new ArrayList<>();
    }

    private static final String API = "https://api.themoviedb.org/3";
    static final String POSTER = "https://image.tmdb.org/t/p/w342";
    static final String BACKDROP = "https://image.tmdb.org/t/p/w1280";
    private static final long KEEP = 3 * 3600_000L;
    /** TMDB provider ids in Germany: Netflix, Prime Video, Disney+, Apple TV+, Paramount+, WOW, RTL+, Joyn. */
    private static final String SERVICES = "8|9|337|350|531|30|298|304";

    static boolean hasKey() {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    static String region() {
        String r = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
        return r.isEmpty() ? "DE" : r;
    }

    static String lang() {
        return Ui.language.startsWith("de") ? "de-DE" : "en-US";
    }

    /** The rows, from the 3-hour cache when it's fresh (or [force] = false and the network fails). */
    static List<Row> rows(Context c, boolean force) {
        SharedPreferences sp = c.getSharedPreferences("discover", Context.MODE_PRIVATE);
        long at = sp.getLong("at", 0);
        String saved = sp.getString("rows", null);
        boolean fresh = saved != null && System.currentTimeMillis() - at < KEEP && lang().equals(sp.getString("lang", ""));
        if (fresh && !force) {
            List<Row> cached = parse(saved);
            if (!cached.isEmpty()) return cached;
        }
        List<Row> rows = new ArrayList<>();
        String common = "&language=" + lang() + "&watch_region=" + region() + "&with_watch_monetization_types=flatrate&include_adult=false";
        add(rows, "trending", Ui.tr("Trending this week"), "/trending/all/week?language=" + lang(), null);
        add(rows, "episodes", Ui.tr("New episodes this week"),
                "/discover/tv?sort_by=popularity.desc&air_date.gte=" + day(-7) + "&air_date.lte=" + day(0)
                        + "&with_watch_providers=" + enc(SERVICES) + common, "tv");
        add(rows, "netflix", Ui.trf("Popular on {service}", "service", "Netflix"),
                "/discover/movie?sort_by=popularity.desc&vote_count.gte=50&with_watch_providers=8" + common, "movie");
        add(rows, "netflix-tv", Ui.trf("Series on {service}", "service", "Netflix"),
                "/discover/tv?sort_by=popularity.desc&vote_count.gte=50&with_watch_providers=8" + common, "tv");
        add(rows, "prime", Ui.trf("Popular on {service}", "service", "Prime Video"),
                "/discover/movie?sort_by=popularity.desc&vote_count.gte=50&with_watch_providers=9" + common, "movie");
        add(rows, "new", Ui.tr("New movies on your services"),
                "/discover/movie?sort_by=popularity.desc&vote_count.gte=20&primary_release_date.gte=" + day(-120)
                        + "&primary_release_date.lte=" + day(0) + "&with_watch_providers=" + enc(SERVICES) + common, "movie");
        add(rows, "bollywood", Ui.tr("Bollywood"),
                "/discover/movie?sort_by=popularity.desc&with_original_language=hi&vote_count.gte=30&primary_release_date.gte="
                        + day(-730) + "&language=" + lang(), "movie");
        if (rows.isEmpty()) return saved == null ? rows : parse(saved);
        sp.edit().putString("rows", save(rows)).putLong("at", System.currentTimeMillis()).putString("lang", lang()).apply();
        return rows;
    }

    private static void add(List<Row> rows, String key, String title, String path, String kind) {
        try {
            JSONArray results = get(path).optJSONArray("results");
            Row row = new Row();
            row.key = key;
            row.title = title;
            for (int i = 0; results != null && i < results.length() && row.items.size() < 20; i++) {
                Title t = title(results.getJSONObject(i), kind);
                if (t != null) row.items.add(t);
            }
            if (!row.items.isEmpty()) rows.add(row);
        } catch (Exception e) {
            AppLog.w("discover " + key + ": " + e.getMessage());
        }
    }

    private static Title title(JSONObject o, String kind) {
        String k = kind != null ? kind : o.optString("media_type", "movie");
        if (!k.equals("movie") && !k.equals("tv")) return null;
        String poster = o.optString("poster_path", "");
        if (poster.isEmpty() || "null".equals(poster)) return null;
        Title t = new Title();
        t.id = o.optInt("id");
        t.kind = k;
        t.name = o.optString(k.equals("movie") ? "title" : "name", "");
        t.poster = poster;
        String b = o.optString("backdrop_path", "");
        t.backdrop = b.isEmpty() || "null".equals(b) ? null : b;
        t.overview = o.optString("overview", "");
        String date = o.optString(k.equals("movie") ? "release_date" : "first_air_date", "");
        t.year = date.length() >= 4 ? date.substring(0, 4) : "";
        t.rating = o.optDouble("vote_average", 0);
        return t;
    }

    // MARK: - Surprise me

    /** Genres for "Surprise me": label, TMDB genre id (movie), or "hi" for Bollywood, or "tv" for any series. */
    static String[][] moods() {
        return new String[][] {
            {Ui.tr("Anything good"), ""},
            {Ui.tr("Action"), "28"},
            {Ui.tr("Comedy"), "35"},
            {Ui.tr("Thriller"), "53"},
            {Ui.tr("Horror"), "27"},
            {Ui.tr("Sci-Fi"), "878"},
            {Ui.tr("Drama"), "18"},
            {Ui.tr("Bollywood"), "hi"},
            {Ui.tr("A series"), "tv"},
        };
    }

    /** One well-rated title for the mood, at random (two requests: the page count, then a random page). */
    static Title surprise(String mood) throws Exception {
        boolean tv = "tv".equals(mood);
        StringBuilder q = new StringBuilder(tv ? "/discover/tv?" : "/discover/movie?");
        q.append("language=").append(lang()).append("&include_adult=false&sort_by=vote_count.desc");
        if ("hi".equals(mood)) {
            q.append("&with_original_language=hi&vote_count.gte=150&vote_average.gte=6.8");
        } else if (tv) {
            q.append("&vote_count.gte=300&vote_average.gte=7.5&without_genres=16,10764,10767");
        } else {
            q.append("&vote_count.gte=800&vote_average.gte=7");
            if (!mood.isEmpty()) q.append("&with_genres=").append(mood);
        }
        JSONObject first = get(q.toString());
        int pages = Math.max(1, Math.min(first.optInt("total_pages", 1), 25));
        Random random = new Random();
        JSONObject page = pages == 1 ? first : get(q + "&page=" + (1 + random.nextInt(pages)));
        JSONArray results = page.optJSONArray("results");
        List<Title> all = new ArrayList<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            Title t = title(results.getJSONObject(i), tv ? "tv" : "movie");
            if (t != null) all.add(t);
        }
        if (all.isEmpty()) throw new IOException("no titles");
        return all.get(random.nextInt(all.size()));
    }

    // MARK: - Helpers

    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String save(List<Row> rows) {
        try {
            JSONArray a = new JSONArray();
            for (Row r : rows) {
                JSONArray items = new JSONArray();
                for (Title t : r.items) {
                    items.put(new JSONObject().put("id", t.id).put("kind", t.kind).put("name", t.name).put("poster", t.poster)
                            .put("backdrop", t.backdrop == null ? "" : t.backdrop).put("overview", t.overview).put("year", t.year)
                            .put("rating", t.rating));
                }
                a.put(new JSONObject().put("key", r.key).put("title", r.title).put("items", items));
            }
            return a.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static List<Row> parse(String json) {
        List<Row> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Row r = new Row();
                r.key = o.optString("key");
                r.title = o.optString("title");
                JSONArray items = o.optJSONArray("items");
                for (int j = 0; items != null && j < items.length(); j++) {
                    JSONObject x = items.getJSONObject(j);
                    Title t = new Title();
                    t.id = x.optInt("id");
                    t.kind = x.optString("kind");
                    t.name = x.optString("name");
                    t.poster = x.optString("poster");
                    t.backdrop = x.optString("backdrop").isEmpty() ? null : x.optString("backdrop");
                    t.overview = x.optString("overview");
                    t.year = x.optString("year");
                    t.rating = x.optDouble("rating", 0);
                    r.items.add(t);
                }
                if (!r.items.isEmpty()) out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
