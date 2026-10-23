package com.tvlite.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small Home screen for Android 5.1 TVs: a wallpaper with a clock, three pills and a dock of the most used
 * apps on top; the full app grid opens from the last dock tile. Only the app query and the wallpaper decode
 * run off the main thread, on one background thread.
 */
public class MainActivity extends Activity {

    private static final int COLUMNS = 5;
    private static final int DOCK_APPS = 5;
    private static final String MITV_SETTINGS = "com.xiaomi.mitv.settings";
    private static final String INPUTSOURCE_POPUP = "com.xiaomi.mitv.settings.INPUTSOURCE_POPUP";
    private static final ComponentName PATCHWALL =
            new ComponentName("com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode");

    // Bundled wallpapers, drawn by tools/wallpapers.py. The first one is the default.
    private static final String[] WP_KEYS = {"aurora", "dusk", "ocean", "galaxy", "waves"};
    private static final int[] WP_RES = {R.drawable.wp_aurora, R.drawable.wp_dusk, R.drawable.wp_ocean,
            R.drawable.wp_galaxy, R.drawable.wp_waves};
    private static final int[] WP_NAMES = {R.string.wp_aurora, R.string.wp_dusk, R.string.wp_ocean,
            R.string.wp_galaxy, R.string.wp_waves};
    private static final String[] PICTURE_DIRS = {"TVLite", "Pictures", "DCIM", "Download", "bluetooth"};

    static final class App {
        final ComponentName cn;
        final String label;
        final Drawable art;
        final boolean banner;
        final boolean system;

        App(ComponentName cn, String label, Drawable art, boolean banner, boolean system) {
            this.cn = cn;
            this.label = label;
            this.art = art;
            this.banner = banner;
            this.system = system;
        }

        String key() {
            return cn.flattenToShortString();
        }
    }

    // Kept for the life of the process, so returning Home never re-reads every icon.
    private static List<App> sApps;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private ImageView wallpaper;
    private View home;
    private ScrollView drawer;
    private LinearLayout grid, dock;
    private TextView clock, date, greeting, wifiPill, focusedName;
    private View inputsPill, settingsPill;
    private final List<View> tiles = new ArrayList<>();
    private final List<View> dockTiles = new ArrayList<>();
    private String focusKey, dockFocusKey;
    private int loadGen, wallpaperGen;
    private String wallpaperShown;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d MMMM", new Locale("vi"));

    // No inner (non-static) classes in this file: javac 21 writes a MethodParameters entry without a
    // name for their outer-instance parameter, and d8 34.0.0 crashes on it. Lambdas and static classes are fine.
    static final class PackageReceiver extends BroadcastReceiver {
        private final MainActivity activity;

        PackageReceiver(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            sApps = null;
            activity.load();
        }
    }

    private final Runnable tick = this::onTick;
    private final BroadcastReceiver packageReceiver = new PackageReceiver(this);

    private void onTick() {
        updateClock();
        long now = System.currentTimeMillis();
        handler.postDelayed(tick, 60_000 - now % 60_000 + 50);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        wallpaper = (ImageView) findViewById(R.id.wallpaper);
        home = findViewById(R.id.home);
        drawer = (ScrollView) findViewById(R.id.drawer);
        grid = (LinearLayout) findViewById(R.id.grid);
        dock = (LinearLayout) findViewById(R.id.dock);
        focusedName = (TextView) findViewById(R.id.focused_name);
        clock = (TextView) findViewById(R.id.clock);
        date = (TextView) findViewById(R.id.date);
        greeting = (TextView) findViewById(R.id.greeting);
        inputsPill = findViewById(R.id.pill_inputs);
        wifiPill = (TextView) findViewById(R.id.pill_wifi);
        settingsPill = findViewById(R.id.pill_settings);

        inputsPill.setOnClickListener(v -> openInputs());
        wifiPill.setOnClickListener(v -> openSettings(".wifi.MitvWifiActivity", Settings.ACTION_WIFI_SETTINGS));
        settingsPill.setOnClickListener(v -> openSettings(".entry.MainActivity", Settings.ACTION_SETTINGS));
        for (View p : new View[]{inputsPill, wifiPill, settingsPill}) {
            p.setNextFocusUpId(p.getId());
            p.setOnFocusChangeListener((v, has) -> {
                if (has) focusedName.setText("");
            });
        }
        inputsPill.setNextFocusLeftId(inputsPill.getId());
        settingsPill.setNextFocusRightId(settingsPill.getId());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        if (sApps != null) {
            render();
        } else {
            load();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        tick.run();
        updateWifi();
        applyWallpaper(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        handler.removeCallbacksAndMessages(null);
        loader.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already Home: close the app list, first dock app focused.
        if (hasWindowFocus()) {
            closeDrawer();
            if (!dockTiles.isEmpty()) dockTiles.get(0).requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back only closes the app list.
        if (drawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
        } else if (!dockTiles.isEmpty()) {
            dockTiles.get(0).requestFocus();
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Menu anywhere that is not an app tile: wallpaper and the other screen-wide choices.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMenu(null);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---- Apps ----

    private void load() {
        final int gen = ++loadGen;
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        loader.execute(() -> {
            LinkedHashMap<String, ResolveInfo> byPackage = new LinkedHashMap<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (!pkg.equals(self) && !byPackage.containsKey(pkg)) byPackage.put(pkg, ri);
                }
            }
            List<App> apps = new ArrayList<>();
            for (ResolveInfo ri : byPackage.values()) {
                Drawable art = null;
                boolean banner = false;
                try {
                    art = ri.activityInfo.loadBanner(pm);
                    banner = art != null;
                    if (art == null) art = ri.loadIcon(pm);
                } catch (RuntimeException ignored) {
                    // A broken resource in one app must not empty the whole Home screen.
                }
                CharSequence label = ri.loadLabel(pm);
                apps.add(new App(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                        label == null ? ri.activityInfo.packageName : label.toString(), art, banner,
                        (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0));
            }
            handler.post(() -> {
                if (gen != loadGen || isDestroyed()) return;
                sApps = apps;
                render();
            });
        });
    }

    private List<App> ordered() {
        Set<String> hidden = prefs.getStringSet("hidden", Collections.<String>emptySet());
        final List<String> pins = pins();
        final Collator collator = Collator.getInstance(new Locale("vi"));
        List<App> out = new ArrayList<>();
        for (App a : sApps) if (!hidden.contains(a.key())) out.add(a);
        Collections.sort(out, (a, b) -> {
            int pa = pins.indexOf(a.key()), pb = pins.indexOf(b.key());
            if (pa >= 0 || pb >= 0) {
                if (pa < 0) return 1;
                if (pb < 0) return -1;
                return pa - pb;
            }
            int ca = prefs.getInt("n:" + a.key(), 0), cb = prefs.getInt("n:" + b.key(), 0);
            if (ca != cb) return cb - ca;
            return collator.compare(a.label, b.label);
        });
        return out;
    }

    private void render() {
        renderDock();
        if (drawer.getVisibility() == View.VISIBLE) renderGrid();
    }

    private void renderDock() {
        View focused = getCurrentFocus();
        boolean pillFocused = !dockTiles.isEmpty() && focused != null && focused.getParent() == findViewById(R.id.pills);

        dock.removeAllViews();
        dockTiles.clear();
        List<App> apps = ordered();
        int w = dp(124), h = dp(70), gap = dp(16);
        int n = Math.min(DOCK_APPS, apps.size());
        for (int i = 0; i <= n; i++) {
            final View tile;
            final String name;
            if (i < n) {
                final App app = apps.get(i);
                name = app.label;
                tile = makeTile(app, app.art, app.banner, w, h, null);
                tile.setOnClickListener(v -> launch(app));
            } else {
                name = getString(R.string.all_apps);
                tile = makeTile(null, getDrawable(R.drawable.ic_all_apps), false, w, h, null);
                tile.setTag("all");
                tile.setOnClickListener(v -> openDrawer());
            }
            final String key = (String) tile.getTag();
            tile.setOnFocusChangeListener((v, has) -> {
                scaleCard(v, has, 1.12f);
                if (has) {
                    focusedName.setText(name);
                    dockFocusKey = key;
                }
            });
            tile.setId(View.generateViewId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i < n) lp.rightMargin = gap;
            dock.addView(tile, lp);
            dockTiles.add(tile);
        }

        // Deterministic D-pad: the dock is one row that stops at both ends; Up goes to the pills.
        for (int i = 0; i < dockTiles.size(); i++) {
            View t = dockTiles.get(i);
            t.setNextFocusLeftId(i == 0 ? t.getId() : dockTiles.get(i - 1).getId());
            t.setNextFocusRightId(i == dockTiles.size() - 1 ? t.getId() : dockTiles.get(i + 1).getId());
            t.setNextFocusUpId(R.id.pill_inputs);
            t.setNextFocusDownId(t.getId());
        }
        int firstId = dockTiles.get(0).getId();
        for (int id : new int[]{R.id.pill_inputs, R.id.pill_wifi, R.id.pill_settings}) {
            findViewById(id).setNextFocusDownId(firstId);
        }

        if (pillFocused || drawer.getVisibility() == View.VISIBLE) return;
        View target = dockTiles.get(0);
        if (dockFocusKey != null) {
            for (View t : dockTiles) if (dockFocusKey.equals(t.getTag())) target = t;
        }
        target.requestFocus();
    }

    private void renderGrid() {
        grid.removeAllViews();
        tiles.clear();
        List<App> apps = ordered();

        int cardW = dp(160), cardH = dp(90), gap = dp(16);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(14);
                grid.addView(row, rp);
            }
            final App app = apps.get(i);
            View tile = makeTile(app, app.art, app.banner, cardW, cardH, app.label);
            tile.setOnClickListener(v -> launch(app));
            tile.setOnFocusChangeListener((v, has) -> {
                scaleCard(v, has, 1.08f);
                if (has) focusKey = app.key();
            });
            tile.setId(View.generateViewId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLUMNS != COLUMNS - 1) lp.rightMargin = gap;
            row.addView(tile, lp);
            tiles.add(tile);
        }

        // One row per press, edges stop instead of wrapping.
        int n = tiles.size();
        for (int i = 0; i < n; i++) {
            View t = tiles.get(i);
            int r = i / COLUMNS, c = i % COLUMNS, lastRow = (n - 1) / COLUMNS;
            t.setNextFocusLeftId(c == 0 ? t.getId() : tiles.get(i - 1).getId());
            t.setNextFocusRightId(c == COLUMNS - 1 || i == n - 1 ? t.getId() : tiles.get(i + 1).getId());
            t.setNextFocusUpId(r == 0 ? t.getId() : tiles.get(i - COLUMNS).getId());
            t.setNextFocusDownId(r == lastRow ? t.getId() : tiles.get(Math.min(i + COLUMNS, n - 1)).getId());
        }

        if (n == 0) return;
        View target = tiles.get(0);
        if (focusKey != null) {
            for (View t : tiles) if (focusKey.equals(t.getTag())) target = t;
        }
        target.requestFocus();
    }

    private void openDrawer() {
        home.setVisibility(View.INVISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.scrollTo(0, 0);
        focusKey = null;
        renderGrid();
    }

    private void closeDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        drawer.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        grid.removeAllViews();
        tiles.clear();
        // Pins or hides made in the list show up in the dock straight away.
        dockFocusKey = "all";
        renderDock();
    }

    /** A focusable tile: a rounded card with the app's banner or icon, and an optional label under it. */
    private View makeTile(App app, Drawable art, boolean banner, int w, int h, String label) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipChildren(false);
        if (app != null) tile.setTag(app.key());

        FrameLayout card = new FrameLayout(this);
        card.setBackgroundResource(R.drawable.card);
        card.setForeground(getDrawable(R.drawable.card_focus));
        card.setDuplicateParentStateEnabled(true);
        card.setClipToOutline(true);

        ImageView img = new ImageView(this);
        img.setImageDrawable(art);
        FrameLayout.LayoutParams ip;
        if (banner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            int s = Math.min(dp(52), h * 2 / 3);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ip = new FrameLayout.LayoutParams(s, s, Gravity.CENTER);
        }
        card.addView(img, ip);
        tile.addView(card, new LinearLayout.LayoutParams(w, h));

        if (label != null) {
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER_HORIZONTAL);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTextColor(getResources().getColorStateList(R.color.label_text));
            tv.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            tile.addView(tv, lp);
        }

        if (app != null) {
            tile.setOnLongClickListener(v -> {
                showMenu(app);
                return true;
            });
            tile.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.getAction() == KeyEvent.ACTION_UP) showMenu(app);
                    return true;
                }
                return false;
            });
        }
        return tile;
    }

    private void scaleCard(View tile, boolean focused, float scale) {
        View card = ((ViewGroup) tile).getChildAt(0);
        float s = focused ? scale : 1f;
        card.animate().scaleX(s).scaleY(s).setDuration(120).start();
    }

    private void launch(App app) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(app.cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (start(i)) {
            String k = "n:" + app.key();
            prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply();
        }
    }

    private void showMenu(final App app) {
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (app != null) {
            final List<String> pins = pins();
            final boolean pinned = pins.contains(app.key());
            items.add(getString(pinned ? R.string.unpin : R.string.pin));
            actions.add(() -> {
                pins.remove(app.key());
                if (!pinned) pins.add(app.key());
                prefs.edit().putString("pins", TextUtils.join("\n", pins)).apply();
                render();
            });
            items.add(getString(R.string.hide));
            actions.add(() -> {
                Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
                hidden.add(app.key());
                prefs.edit().putStringSet("hidden", hidden).apply();
                focusKey = null;
                dockFocusKey = null;
                render();
            });
            items.add(getString(R.string.info));
            actions.add(() -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.cn.getPackageName(), null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            // Only apps the owner installed: Android's own installer asks for confirmation first.
            if (!app.system) {
                items.add(getString(R.string.uninstall));
                actions.add(() -> start(new Intent(Intent.ACTION_DELETE,
                        Uri.fromParts("package", app.cn.getPackageName(), null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            }
        }
        items.add(getString(R.string.wallpaper));
        actions.add(this::showWallpaperPicker);
        if (!prefs.getStringSet("hidden", Collections.<String>emptySet()).isEmpty()) {
            items.add(getString(R.string.show_hidden));
            actions.add(() -> {
                prefs.edit().remove("hidden").apply();
                render();
            });
        }
        // A hidden (pm hide) PatchWall counts as not installed, so the entry goes away with it.
        if (isInstalled(PATCHWALL.getPackageName())) {
            items.add(getString(R.string.old_home));
            actions.add(() -> start(new Intent(Intent.ACTION_MAIN).setComponent(PATCHWALL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(app != null ? app.label : getString(R.string.app_name))
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private List<String> pins() {
        String s = prefs.getString("pins", "");
        return s.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    // ---- Wallpaper ----

    /**
     * Pictures on the TV or a USB drive, newest first: the drive root, the folders in PICTURE_DIRS and one
     * level below them (DCIM/Camera, Pictures/Screenshots), which is where the Gallery app finds its albums
     * and where Bluetooth puts received files.
     */
    private List<File> pictureFiles() {
        Set<File> roots = new LinkedHashSet<>();
        roots.add(Environment.getExternalStorageDirectory());
        for (String base : new String[]{"/storage", "/mnt/usb", "/mnt/media_rw"}) {
            File[] mounts = new File(base).listFiles();
            if (mounts == null) continue;
            for (File m : mounts) {
                if (m.isDirectory() && m.canRead() && !m.getName().startsWith("emulated")
                        && !m.getName().equals("self")) {
                    roots.add(m);
                }
            }
        }
        List<File> out = new ArrayList<>();
        // /sdcard, /storage/sdcard0 and /storage/emulated are one disk here: the same photo under several
        // paths is told apart by name, size and date rather than by path.
        Set<String> seen = new HashSet<>();
        for (File root : roots) {
            List<File> dirs = new ArrayList<>();
            dirs.add(root);
            for (String d : PICTURE_DIRS) {
                File dir = new File(root, d);
                dirs.add(dir);
                File[] subs = dir.listFiles();
                if (subs == null) continue;
                for (File s : subs) if (s.isDirectory() && !s.getName().startsWith(".")) dirs.add(s);
            }
            for (File dir : dirs) {
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    String n = f.getName().toLowerCase(Locale.US);
                    if (!f.isFile() || !(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                            || n.endsWith(".webp"))) continue;
                    if (seen.add(n + "|" + f.length() + "|" + f.lastModified())) out.add(f);
                }
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out.size() > 60 ? new ArrayList<>(out.subList(0, 60)) : out;
    }

    private void showWallpaperPicker() {
        loader.execute(() -> {
            final List<File> files = pictureFiles();
            handler.post(() -> {
                if (isDestroyed()) return;
                final String current = prefs.getString("wp", WP_KEYS[0]);
                final List<String> keys = new ArrayList<>();
                final List<String> names = new ArrayList<>();
                keys.add("daily");
                names.add(getString(R.string.wp_daily));
                for (int i = 0; i < WP_KEYS.length; i++) {
                    keys.add(WP_KEYS[i]);
                    names.add(getString(WP_NAMES[i]));
                }
                for (File f : files) {
                    keys.add("file:" + f.getAbsolutePath());
                    names.add(f.getName());
                }
                for (int i = 0; i < keys.size(); i++) {
                    names.set(i, (keys.get(i).equals(current) ? "✓  " : "     ") + names.get(i));
                }
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(R.string.wallpaper)
                        .setItems(names.toArray(new String[0]), (d, which) -> {
                            prefs.edit().putString("wp", keys.get(which)).apply();
                            applyWallpaper(true);
                        })
                        .show();
                if (files.isEmpty()) Toast.makeText(this, R.string.wp_folder_hint, Toast.LENGTH_LONG).show();
            });
        });
    }

    /** Shows the chosen wallpaper; in daily mode, today's one. Decodes off the main thread at screen size. */
    private void applyWallpaper(final boolean force) {
        final String choice = prefs.getString("wp", WP_KEYS[0]);
        final int day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        final int gen = ++wallpaperGen;
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        loader.execute(() -> {
            String source = choice;
            if (choice.equals("daily")) {
                List<String> all = new ArrayList<>(Arrays.asList(WP_KEYS));
                for (File f : pictureFiles()) all.add("file:" + f.getAbsolutePath());
                source = all.get(day % all.size());
            }
            if (!force && source.equals(wallpaperShown)) return;
            Bitmap bmp = decodeWallpaper(source, screenW);
            if (bmp == null) {
                source = WP_KEYS[0];
                bmp = decodeWallpaper(source, screenW);
            }
            final Bitmap result = bmp;
            final String shown = source;
            handler.post(() -> {
                if (gen != wallpaperGen || isDestroyed()) return;
                wallpaper.setImageBitmap(result);
                wallpaperShown = shown;
            });
        });
    }

    private Bitmap decodeWallpaper(String source, int screenW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        // 16-bit is half the memory of 32-bit and looks the same on a photo at TV distance.
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            if (source.startsWith("file:")) {
                String path = source.substring(5);
                BitmapFactory.Options b = new BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, b);
                int sample = 1;
                while (b.outWidth / (sample * 2) >= screenW) sample *= 2;
                o.inSampleSize = sample;
                return BitmapFactory.decodeFile(path, o);
            }
            for (int i = 0; i < WP_KEYS.length; i++) {
                if (WP_KEYS[i].equals(source)) return BitmapFactory.decodeResource(getResources(), WP_RES[i], o);
            }
        } catch (OutOfMemoryError | RuntimeException e) {
            // Fall back to the default picture.
        }
        return null;
    }

    // ---- Top bar ----

    private void openInputs() {
        if (isInstalled(MITV_SETTINGS)) {
            sendBroadcast(new Intent(INPUTSOURCE_POPUP));
        } else {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings(String mitvActivity, String fallbackAction) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(MITV_SETTINGS, MITV_SETTINGS + mitvActivity))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!isInstalled(MITV_SETTINGS) || !startQuietly(i)) {
            start(new Intent(fallbackAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));
        String d = dateFmt.format(now);
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 11 ? R.string.morning : h < 18 ? R.string.afternoon : R.string.evening);
    }

    private void updateWifi() {
        String label = getString(R.string.wifi);
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid != null && ssid.length() > 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                label = label + " · " + ssid.substring(1, ssid.length() - 1);
            }
        } catch (RuntimeException ignored) {
            // No Wi-Fi service: the pill just says Wi-Fi.
        }
        wifiPill.setText(label);
    }

    // ---- Helpers ----

    private boolean start(Intent i) {
        if (startQuietly(i)) return true;
        Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        return false;
    }

    private boolean startQuietly(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
