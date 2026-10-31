package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Poster/backdrop loading for the fork's tracker rows.
 * Memory LRU (24 MB, byte-capped) over a disk cache
 * (filesDir/posters/, 200 files / 50 MB, oldest pruned on
 * write), served by a small daemon pool with a waiter map
 * per URL — the same delivery shape as Sreeb's TmdbImages,
 * so a home rebuild mid-download never strands a card
 * blank. Every failure is fail-open: the view keeps
 * whatever it shows.
 *
 * Posters render 2:3 portrait, never centre-cropped — the
 * card views use FIT_CENTER; this loader only bounds the
 * decode (longest side near maxDim) and never crops.
 */
final class PosterLoader {
    private static final int MAX_DOWNLOAD_BYTES = 6 * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 24L * 1024L * 1024L;
    private static final int MAX_DISK_FILES = 200;
    private static final long MAX_DISK_BYTES = 50L * 1024L * 1024L;

    private final File diskDir;
    private final Map<String, Bitmap> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes = 0;
    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, List<ImageView>> waiters = new HashMap<>();
    private final AtomicInteger threadNo = new AtomicInteger(0);
    private final ThreadPoolExecutor pool;

    PosterLoader(File filesDir) {
        diskDir = new File(filesDir, "posters");
        LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
        pool = new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, queue, r ->
                new Thread(r, "fork-poster-" + threadNo.incrementAndGet()) {
                    {
                        setDaemon(true);
                        setPriority(Thread.NORM_PRIORITY - 1);
                    }
                });
        pool.allowCoreThreadTimeOut(true);
        pool.prestartAllCoreThreads();
    }

    private static String cacheKey(String url, int maxDim) {
        return url + "@" + maxDim;
    }

    private static String diskName(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(key.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString() + ".jpg";
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode()) + ".jpg";
        }
    }

    void load(String url, ImageView target, int maxDim) {
        if (url == null || url.trim().isEmpty() || target == null) return;
        String key = cacheKey(url, maxDim);
        synchronized (lock) {
            Bitmap hit = cache.get(key);
            if (hit != null) {
                target.setImageBitmap(hit);
                return;
            }
        }
        target.setTag(url);
        boolean alreadyDownloading;
        synchronized (lock) {
            List<ImageView> list = waiters.get(key);
            if (list == null) {
                list = new ArrayList<>();
                waiters.put(key, list);
                alreadyDownloading = false;
            } else {
                alreadyDownloading = true;
            }
            boolean found = false;
            for (ImageView v : list) {
                if (v == target) { found = true; break; }
            }
            if (!found) list.add(target);
        }
        if (alreadyDownloading) return;
        pool.execute(() -> {
            Bitmap bmp = loadFromDisk(key, maxDim);
            if (bmp == null) bmp = download(url, maxDim);
            if (bmp != null) {
                saveToDisk(key, bmp);
                synchronized (lock) {
                    Bitmap old = cache.remove(key);
                    if (old != null) cacheBytes -= old.getByteCount();
                    cache.put(key, bmp);
                    cacheBytes += bmp.getByteCount();
                    while (cacheBytes > MAX_CACHE_BYTES && cache.size() > 1) {
                        Map.Entry<String, Bitmap> eldest = cache.entrySet().iterator().next();
                        cacheBytes -= eldest.getValue().getByteCount();
                        cache.remove(eldest.getKey());
                    }
                }
            }
            List<ImageView> served;
            synchronized (lock) {
                served = waiters.remove(key);
            }
            if (bmp != null && served != null) {
                final Bitmap done = bmp;
                mainHandler.post(() -> {
                    for (ImageView v : served) {
                        if (url.equals(v.getTag())) v.setImageBitmap(done);
                    }
                });
            }
        });
    }

    private Bitmap loadFromDisk(String key, int maxDim) {
        try {
            File f = new File(diskDir, diskName(key));
            if (!f.isFile()) return null;
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] bytes = readAll(in, MAX_DOWNLOAD_BYTES);
                if (bytes == null) return null;
                return decode(bytes, maxDim);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void saveToDisk(String key, Bitmap bmp) {
        try {
            if (!diskDir.isDirectory()) diskDir.mkdirs();
            File f = new File(diskDir, diskName(key));
            FileOutputStream out = new FileOutputStream(f);
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } finally {
                out.close();
            }
            pruneDisk();
        } catch (Exception e) {
            // Disk cache is best-effort; memory cache still serves.
        }
    }

    private void pruneDisk() {
        try {
            File[] files = diskDir.listFiles();
            if (files == null) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            long total = 0;
            for (File f : files) total += f.length();
            int i = 0;
            while ((files.length - i > MAX_DISK_FILES || total > MAX_DISK_BYTES) && i < files.length) {
                total -= files[i].length();
                files[i].delete();
                i++;
            }
        } catch (Exception e) {
            // Best-effort.
        }
    }

    private Bitmap download(String url, int maxDim) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "CleanYourTV-Fork/1.0");
            if (conn.getResponseCode() < 200 || conn.getResponseCode() > 299) return null;
            byte[] bytes = readAll(conn.getInputStream(), MAX_DOWNLOAD_BYTES);
            if (bytes == null) return null;
            return decode(bytes, maxDim);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] chunk = new byte[32 * 1024];
        int total = 0;
        while (true) {
            int n = in.read(chunk);
            if (n < 0) break;
            total += n;
            if (total > max) return null;
            bos.write(chunk, 0, n);
        }
        return bos.toByteArray();
    }

    private static Bitmap decode(byte[] bytes, int maxDim) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxDim
                && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }
}
