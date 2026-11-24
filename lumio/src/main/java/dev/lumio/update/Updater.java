package dev.lumio.update;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/**
 * The update window: a small window of its own, before the game. "Update now" downloads the new Lumio
 * and checks it (its fingerprint and that it really is Lumio in that version), tells the game to close,
 * swaps the file in the mods folder and starts the game again. Runs in a Java of its own and needs
 * nothing but Java itself.
 */
public final class Updater {
    private static final Color BG = new Color(0x121218);
    private static final Color CARD = new Color(0x1A1A24);
    private static final Color TEXT = new Color(0xF4F4F8);
    private static final Color MUTED = new Color(0xA1A1B5);
    private static final Color FAINT = new Color(0x6B6B80);
    private static final Color ACCENT = new Color(0x7C5CFF);
    private static final Color ACCENT2 = new Color(0x4FC3F7);
    private static final Color DANGER = new Color(0xF87171);
    private static final Color SUCCESS = new Color(0x4ADE80);
    private static final int W = 500, H = 270;

    private static final Map<String, String> DE = Map.ofEntries(
            Map.entry("title", "Update verfügbar"),
            Map.entry("text", "Lumio {new} ist da. Du hast {old}."),
            Map.entry("ask", "Jetzt aktualisieren? Minecraft schließt sich kurz und startet danach von selbst neu."),
            Map.entry("update", "Jetzt updaten"),
            Map.entry("later", "Später"),
            Map.entry("skip", "Diese Version überspringen"),
            Map.entry("loading", "Lade Lumio {new} …"),
            Map.entry("checking", "Prüfe die Datei …"),
            Map.entry("closing", "Minecraft wird geschlossen …"),
            Map.entry("swapping", "Lumio wird ersetzt …"),
            Map.entry("starting", "Fertig! Minecraft startet neu …"),
            Map.entry("manual", "Lumio {new} ist installiert. Bitte starte Minecraft im Launcher neu."),
            Map.entry("failed", "Das Update hat nicht geklappt: {why}. Minecraft startet mit der alten Version."),
            Map.entry("swapfailed", "Die alte Lumio-Datei ließ sich nicht ersetzen. Lösche sie bitte selbst im mods-Ordner und starte neu."),
            Map.entry("ok", "OK"),
            Map.entry("changes", "Neu:"));
    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("title", "Update available"),
            Map.entry("text", "Lumio {new} is out. You have {old}."),
            Map.entry("ask", "Update now? Minecraft closes for a moment and then starts again by itself."),
            Map.entry("update", "Update now"),
            Map.entry("later", "Later"),
            Map.entry("skip", "Skip this version"),
            Map.entry("loading", "Downloading Lumio {new} …"),
            Map.entry("checking", "Checking the file …"),
            Map.entry("closing", "Closing Minecraft …"),
            Map.entry("swapping", "Replacing Lumio …"),
            Map.entry("starting", "Done! Minecraft is starting again …"),
            Map.entry("manual", "Lumio {new} is installed. Please start Minecraft again in your launcher."),
            Map.entry("failed", "The update did not work: {why}. Minecraft starts with the old version."),
            Map.entry("swapfailed", "The old Lumio file could not be replaced. Please delete it in the mods folder and start again."),
            Map.entry("ok", "OK"),
            Map.entry("changes", "New:"));

    private final Map<String, String> in;
    private final List<String> relaunch;
    private final Map<String, String> t;
    private JFrame frame;
    private View view;
    /** what the window shows: ask, progress, done or a message */
    private enum Mode { ASK, PROGRESS, MESSAGE }
    private volatile Mode mode = Mode.ASK;
    private volatile String status = "";
    private volatile float progress = -1;
    private volatile Color statusColor = MUTED;
    private volatile boolean answered;
    private volatile Runnable afterOk;
    private Font regular, bold;

    private Updater(Map<String, String> in, List<String> relaunch) {
        this.in = in;
        this.relaunch = relaunch;
        this.t = "de".equals(in.get("lang")) ? DE : EN;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> in = new HashMap<>();
        List<String> relaunch = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null && !line.equals("end")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq), v = unescape(line.substring(eq + 1));
            if (k.equals("arg")) relaunch.add(v);
            else in.put(k, v);
        }
        if (!in.containsKey("version")) return;
        try {
            Updater u = new Updater(in, relaunch);
            SwingUtilities.invokeAndWait(u::show);
        } catch (Throwable e) {
            // no window possible here (no screen): the game starts as it is
            System.err.println("Lumio updater: " + e);
            answer("LATER");
            System.exit(0);
        }
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n);
            } else b.append(c);
        }
        return b.toString();
    }

    private static synchronized void answer(String word) {
        System.out.println(word);
        System.out.flush();
    }

    private String text(String key) {
        return t.get(key).replace("{new}", in.getOrDefault("version", "?")).replace("{old}", in.getOrDefault("current", "?"));
    }

    // ---------------------------------------------------------------- the window

    private Font font(String file, float size, int style) {
        try (InputStream s = Updater.class.getResourceAsStream("/assets/lumio/font/" + file)) {
            if (s != null) return Font.createFont(Font.TRUETYPE_FONT, s).deriveFont(size);
        } catch (Exception ignored) {
            // the system font then
        }
        return new Font(Font.SANS_SERIF, style, Math.round(size));
    }

    private void show() {
        regular = font("inter-medium.ttf", 13f, Font.PLAIN);
        bold = font("inter-semibold.ttf", 13f, Font.BOLD);
        frame = new JFrame("Lumio");
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        view = new View();
        view.setPreferredSize(new Dimension(W, H));
        frame.setContentPane(view);
        frame.pack();
        try {
            frame.setShape(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
        } catch (UnsupportedOperationException ignored) {
            // square corners then
        }
        try (InputStream icon = Updater.class.getResourceAsStream("/assets/lumio/icon.png")) {
            if (icon != null) {
                Image img = javax.imageio.ImageIO.read(icon);
                if (img != null) frame.setIconImage(img);
            }
        } catch (IOException ignored) {
            // no icon
        }
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                answerLater();
            }
        });
        frame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE && mode == Mode.ASK) answerLater();
                if (e.getKeyCode() == KeyEvent.VK_ENTER && mode == Mode.ASK) startUpdate();
                if (e.getKeyCode() == KeyEvent.VK_ENTER && mode == Mode.MESSAGE && afterOk != null) afterOk.run();
            }
        });
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
        // a little animation for the progress bar
        new Timer(33, e -> view.repaint()).start();
    }

    private void answerLater() {
        if (answered) return;
        answered = true;
        answer("LATER");
        System.exit(0);
    }

    private void answerSkip() {
        if (answered) return;
        answered = true;
        answer("SKIP");
        System.exit(0);
    }

    private void startUpdate() {
        if (answered) return;
        answered = true;
        mode = Mode.PROGRESS;
        status = text("loading");
        new Thread(this::run, "Lumio update").start();
    }

    /** Shows a message with an OK button; OK runs the given step. */
    private void message(String text, Color color, Runnable ok) {
        status = text;
        statusColor = color;
        afterOk = ok;
        mode = Mode.MESSAGE;
    }

    // ---------------------------------------------------------------- the update

    private void run() {
        Path jar = Path.of(in.get("jar"));
        Path dir = jar.getParent();
        String fileName = "lumio-" + in.get("version") + "+" + in.get("minecraft") + ".jar";
        Path part = dir.resolve(fileName + ".download");
        try {
            download(in.get("url"), part);
            status = text("checking");
            progress = -1;
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(part)));
            if (!sha.equalsIgnoreCase(in.get("sha256"))) throw new IOException("fingerprint does not match");
            if (!isLumio(part, in.get("version"))) throw new IOException("not the right Lumio");
        } catch (Exception e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // left for the next start to clear
            }
            message(text("failed").replace("{why}", String.valueOf(e.getMessage())), DANGER, () -> {
                answer("FAILED");
                System.exit(0);
            });
            return;
        }
        // the game closes; then its files are free to change (Windows keeps open files locked)
        status = text("closing");
        answer("READY");
        try {
            long pid = Long.parseLong(in.getOrDefault("pid", "-1"));
            var game = ProcessHandle.of(pid);
            if (game.isPresent()) game.get().onExit().get(60, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // carry on: the swap tries a few times anyway
        }
        status = text("swapping");
        Path target = dir.resolve(fileName);
        boolean swapped = false;
        for (int i = 0; i < 20 && !swapped; i++) {
            try {
                if (!jar.equals(target)) Files.deleteIfExists(jar);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                swapped = true;
            } catch (IOException e) {
                sleep(300);
            }
        }
        if (!swapped) {
            // as a last way: the old one switched off by its name, so Fabric does not load it
            try {
                Files.move(jar, dir.resolve(jar.getFileName() + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                swapped = true;
            } catch (IOException ignored) {
                // nothing more to try
            }
        }
        if (!swapped) {
            message(text("swapfailed"), DANGER, () -> System.exit(0));
            return;
        }
        // start the game again, the way it was started
        boolean started = false;
        if (!relaunch.isEmpty()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(relaunch).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                String cwd = in.get("cwd");
                if (cwd != null && !cwd.isBlank()) pb.directory(Path.of(cwd).toFile());
                Process game = pb.start();
                status = text("starting");
                statusColor = SUCCESS;
                progress = 1f;
                // a game that ends at once did not start: then the player starts it in the launcher
                started = !game.waitFor(8, TimeUnit.SECONDS) || game.exitValue() == 0;
            } catch (Exception e) {
                started = false;
            }
        }
        if (started) {
            sleep(1500);
            System.exit(0);
        }
        message(text("manual"), SUCCESS, () -> System.exit(0));
    }

    private void download(String url, Path to) throws Exception {
        String next = url;
        HttpURLConnection c = null;
        for (int hops = 0; hops < 5; hops++) {
            if (!Hosts.allowed(next)) throw new IOException("download from an unknown place");
            c = (HttpURLConnection) URI.create(next).toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Lumio-Updater/" + in.getOrDefault("current", "?"));
            int code = c.getResponseCode();
            if (code / 100 == 3 && c.getHeaderField("Location") != null) {
                next = URI.create(next).resolve(c.getHeaderField("Location")).toString();
                continue;
            }
            if (code != 200) throw new IOException("HTTP " + code);
            break;
        }
        long total = c.getContentLengthLong();
        if (total > 64L * 1024 * 1024) throw new IOException("file too big");
        try (InputStream s = c.getInputStream(); OutputStream o = Files.newOutputStream(to)) {
            byte[] buf = new byte[16384];
            long done = 0;
            int n;
            while ((n = s.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                if (done > 64L * 1024 * 1024) throw new IOException("file too big");
                if (total > 0) progress = (float) done / total;
            }
        }
    }

    /** That the file is a mod jar whose fabric.mod.json says id lumio and the expected version. */
    static boolean isLumio(Path jar, String version) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var e = z.getEntry("fabric.mod.json");
            if (e == null) return false;
            String json = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s", "");
            return json.contains("\"id\":\"lumio\"") && json.contains("\"version\":\"" + version + "+");
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- drawing

    private final class View extends JComponent {
        private final Rectangle primary = new Rectangle(), secondary = new Rectangle(), skipLink = new Rectangle(), ok = new Rectangle();
        private Point hover = new Point(-1, -1);
        private Point dragFrom;

        View() {
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    hover = e.getPoint();
                    boolean hand = mode == Mode.ASK && (primary.contains(hover) || secondary.contains(hover) || skipLink.contains(hover))
                            || mode == Mode.MESSAGE && ok.contains(hover);
                    setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    dragFrom = e.getPoint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragFrom == null) return;
                    Point loc = frame.getLocation();
                    frame.setLocation(loc.x + e.getX() - dragFrom.x, loc.y + e.getY() - dragFrom.y);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    Point p = e.getPoint();
                    if (mode == Mode.ASK) {
                        if (primary.contains(p)) startUpdate();
                        else if (secondary.contains(p)) answerLater();
                        else if (skipLink.contains(p)) answerSkip();
                    } else if (mode == Mode.MESSAGE && ok.contains(p) && afterOk != null) afterOk.run();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(BG);
            g.fillRect(0, 0, W, H);
            // the running colour line on top, a soft glow under it
            g.setPaint(new GradientPaint(0, 0, ACCENT, W, 0, ACCENT2));
            g.fillRect(0, 0, W, 3);
            g.setPaint(new GradientPaint(0, 3, new Color(124, 92, 255, 40), 0, 60, new Color(124, 92, 255, 0)));
            g.fillRect(0, 3, W, 60);
            // the mark and the title
            int x = 24, y = 26;
            g.setPaint(new GradientPaint(x, y, ACCENT, x + 30, y + 30, ACCENT2));
            g.fillRoundRect(x, y, 30, 30, 10, 10);
            g.setColor(Color.WHITE);
            g.setFont(bold.deriveFont(18f));
            drawCentered(g, "L", x, y, 30, 30);
            g.setFont(bold.deriveFont(17f));
            g.setColor(TEXT);
            g.drawString("Lumio", x + 42, y + 13);
            g.setFont(regular.deriveFont(12f));
            g.setColor(MUTED);
            g.drawString(mode == Mode.ASK ? text("title") : "Update", x + 42, y + 29);
            int body = 80;
            if (mode == Mode.ASK) {
                g.setFont(bold.deriveFont(14f));
                g.setColor(TEXT);
                g.drawString(text("text"), x, body + 4);
                g.setFont(regular.deriveFont(12.5f));
                g.setColor(MUTED);
                int ty = wrap(g, text("ask"), x, body + 26, W - 2 * x, 17);
                String changes = in.getOrDefault("changes", "");
                if (!changes.isBlank()) {
                    g.setColor(FAINT);
                    wrap(g, text("changes") + " " + changes, x, ty + 4, W - 2 * x, 16, 2);
                }
                // buttons
                int by = H - 62;
                primary.setBounds(W - x - 150, by, 150, 38);
                secondary.setBounds(W - x - 150 - 12 - 100, by, 100, 38);
                button(g, secondary, text("later"), false);
                button(g, primary, text("update"), true);
                g.setFont(regular.deriveFont(11.5f));
                FontMetrics fm = g.getFontMetrics();
                String sk = text("skip");
                skipLink.setBounds(x, by + 12, fm.stringWidth(sk), 16);
                g.setColor(skipLink.contains(hover) ? MUTED : FAINT);
                g.drawString(sk, x, by + 25);
            } else {
                g.setFont(bold.deriveFont(14f));
                g.setColor(mode == Mode.MESSAGE ? statusColor : TEXT);
                int ty = wrap(g, status, x, body + 4, W - 2 * x, 20);
                if (mode == Mode.PROGRESS) {
                    int bw = W - 2 * x, bh = 8, bx = x, byy = Math.max(ty + 14, body + 50);
                    g.setColor(CARD);
                    g.fillRoundRect(bx, byy, bw, bh, bh, bh);
                    float p = progress;
                    if (p < 0) {
                        // not known how far: a light that runs along
                        double phase = (System.currentTimeMillis() % 1400) / 1400.0;
                        int seg = bw / 3;
                        int sx = (int) (bx - seg + phase * (bw + seg));
                        Graphics2D c = (Graphics2D) g.create();
                        c.clip(new RoundRectangle2D.Double(bx, byy, bw, bh, bh, bh));
                        c.setPaint(new GradientPaint(sx, 0, ACCENT, sx + seg, 0, ACCENT2));
                        c.fillRect(sx, byy, seg, bh);
                        c.dispose();
                    } else {
                        g.setPaint(new GradientPaint(bx, 0, ACCENT, bx + bw, 0, ACCENT2));
                        g.fillRoundRect(bx, byy, Math.max(bh, Math.round(bw * Math.min(1f, p))), bh, bh, bh);
                        g.setFont(regular.deriveFont(11.5f));
                        g.setColor(FAINT);
                        g.drawString(Math.round(Math.min(1f, p) * 100) + " %", bx, byy + 26);
                    }
                } else {
                    ok.setBounds(W - x - 110, H - 62, 110, 38);
                    button(g, ok, text("ok"), true);
                }
            }
            // a hairline edge
            g.setColor(new Color(255, 255, 255, 22));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, W - 1, H - 1, 22, 22);
            g.dispose();
        }

        private void button(Graphics2D g, Rectangle r, String label, boolean main) {
            boolean over = r.contains(hover);
            if (main) {
                g.setPaint(new GradientPaint(r.x, r.y, over ? ACCENT.brighter() : ACCENT, r.x + r.width, r.y, over ? ACCENT2.brighter() : ACCENT2));
            } else g.setColor(over ? new Color(0x2A2A38) : CARD);
            g.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
            if (!main) {
                g.setColor(new Color(255, 255, 255, 26));
                g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 12, 12);
            }
            g.setFont(bold.deriveFont(13f));
            g.setColor(main ? Color.WHITE : TEXT);
            drawCentered(g, label, r.x, r.y, r.width, r.height);
        }

        private void drawCentered(Graphics2D g, String s, int x, int y, int w, int h) {
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, x + (w - fm.stringWidth(s)) / 2, y + (h - fm.getHeight()) / 2 + fm.getAscent());
        }

        private int wrap(Graphics2D g, String s, int x, int y, int w, int lh) {
            return wrap(g, s, x, y, w, lh, 6);
        }

        /** Draws text over several lines; returns the y under the last line. */
        private int wrap(Graphics2D g, String s, int x, int y, int w, int lh, int maxLines) {
            FontMetrics fm = g.getFontMetrics();
            int lines = 0;
            for (String para : s.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : para.split(" ")) {
                    String tryLine = line.length() == 0 ? word : line + " " + word;
                    if (fm.stringWidth(tryLine) > w && line.length() > 0) {
                        if (++lines > maxLines) return y;
                        g.drawString(line.toString(), x, y);
                        y += lh;
                        line = new StringBuilder(word);
                    } else line = new StringBuilder(tryLine);
                }
                if (line.length() > 0) {
                    if (++lines > maxLines) return y;
                    g.drawString(line.toString(), x, y);
                    y += lh;
                }
            }
            return y;
        }
    }
}
