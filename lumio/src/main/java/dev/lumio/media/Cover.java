package dev.lumio.media;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lumio.LumioClient;
import dev.lumio.compat.Compat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * The album cover of what is playing: loaded in the background (from a link or a file), cut square,
 * made small, and turned into a texture on the render thread.
 */
public final class Cover {
    public static final int SIZE = 96;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final byte[] NONE = new byte[0];
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lumio cover");
        t.setDaemon(true);
        return t;
    });
    private static HttpClient http;

    private static volatile String wanted = "";
    /** a finished picture (PNG) waiting for the render thread, NONE for "no cover" */
    private static volatile byte[] pending;
    private static Object texture;

    private Cover() {}

    /** The cover for this reference (link, file, or empty); loads it when it changed. */
    public static void want(String ref) {
        String r = ref == null ? "" : ref;
        if (r.equals(wanted)) return;
        wanted = r;
        if (r.isEmpty()) {
            pending = NONE;
            return;
        }
        LOADER.submit(() -> {
            byte[] png = NONE;
            try {
                png = load(r);
            } catch (Throwable e) {
                LumioClient.LOG.debug("Lumio cover: {}", e.toString());
            }
            if (r.equals(wanted)) pending = png;
        });
    }

    /** The texture to draw, or null; call on the render thread. */
    public static Object texture() {
        byte[] p = pending;
        if (p != null) {
            pending = null;
            if (p.length == 0) texture = null;
            else {
                try {
                    texture = Compat.texture("cover", NativeImage.read(p));
                } catch (IOException | RuntimeException e) {
                    texture = null;
                }
            }
        }
        return texture;
    }

    // ---------------------------------------------------------------- loading

    private static byte[] load(String ref) throws IOException, InterruptedException {
        byte[] raw;
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            if (http == null) http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(ref)).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Lumio").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) return NONE;
            try (InputStream in = res.body()) {
                raw = in.readNBytes(MAX_BYTES);
            }
        } else if (ref.startsWith("file:")) {
            String s = ref;
            int hash = s.lastIndexOf('#');
            if (hash > 0) s = s.substring(0, hash);
            Path path = s.startsWith("file://") ? Path.of(URI.create(s)) : Path.of(s.substring(5));
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return NONE;
            raw = Files.readAllBytes(path);
        } else {
            return NONE;
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
        if (img == null) return NONE;
        return png(square(img, SIZE));
    }

    /** The middle square of the picture, made SIZE×SIZE by averaging (no AWT drawing needed). */
    static int[] square(BufferedImage img, int size) {
        int w = img.getWidth(), h = img.getHeight();
        int side = Math.min(w, h);
        int ox = (w - side) / 2, oy = (h - side) / 2;
        int[] src = img.getRGB(ox, oy, side, side, null, 0, side);
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) {
            int y0 = y * side / size, y1 = Math.max(y0 + 1, (y + 1) * side / size);
            for (int x = 0; x < size; x++) {
                int x0 = x * side / size, x1 = Math.max(x0 + 1, (x + 1) * side / size);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int c = src[yy * side + xx];
                        a += c >>> 24;
                        r += c >> 16 & 0xFF;
                        g += c >> 8 & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                out[y * size + x] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        return out;
    }

    /** A small PNG writer (RGBA, no filter), so the picture can go through NativeImage. */
    static byte[] png(int[] argb) throws IOException {
        int size = (int) Math.round(Math.sqrt(argb.length));
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(data, new Deflater(Deflater.BEST_SPEED))) {
            byte[] row = new byte[1 + size * 4];
            for (int y = 0; y < size; y++) {
                row[0] = 0;
                for (int x = 0; x < size; x++) {
                    int c = argb[y * size + x];
                    int i = 1 + x * 4;
                    row[i] = (byte) (c >> 16);
                    row[i + 1] = (byte) (c >> 8);
                    row[i + 2] = (byte) c;
                    row[i + 3] = (byte) (c >>> 24);
                }
                z.write(row);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(ihdr);
        h.writeInt(size);
        h.writeInt(size);
        h.write(new byte[]{8, 6, 0, 0, 0});
        chunk(d, "IHDR", ihdr.toByteArray());
        chunk(d, "IDAT", data.toByteArray());
        chunk(d, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(DataOutputStream d, String type, byte[] body) throws IOException {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        d.writeInt(body.length);
        d.write(t);
        d.write(body);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(body);
        d.writeInt((int) crc.getValue());
    }
}
