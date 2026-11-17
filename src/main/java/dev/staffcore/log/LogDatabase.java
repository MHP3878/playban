package dev.staffcore.log;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Block and container changes in an SQLite file. Paper ships the driver.
 * Writes are queued and done in batches on one background thread, so the game never waits for the disk.
 */
public final class LogDatabase {
    /** One change. For blocks, before/after are block data strings; for items, the serialized item and an amount. */
    public record Entry(long id, long time, String world, int x, int y, int z, String actor, String actorName,
                        Action action, String before, String after, int amount, boolean rolledBack) {
        public boolean isItem() {
            return action == Action.TAKE || action == Action.PUT;
        }
    }

    public enum Action { BREAK, PLACE, EXPLODE, BURN, TAKE, PUT }

    /** What to look for. Unset fields match everything. */
    public static final class Query {
        public String world;
        public int x, y, z, radius = -1;
        public String actor;
        public long since;
        public Boolean rolledBack;
        public boolean blocksOnly;
        public int limit = 500;
        public boolean oldestFirst;
        /** exactly this block */
        public boolean exact;
    }

    private final File file;
    private final Logger log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StaffCore block log");
        t.setDaemon(true);
        return t;
    });
    private final Queue<Entry> pending = new ConcurrentLinkedQueue<>();
    private Connection db;

    public LogDatabase(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public CompletableFuture<Boolean> open(int keepDays) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Class.forName("org.sqlite.JDBC");
                db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                try (Statement s = db.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("CREATE TABLE IF NOT EXISTS changes (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL,"
                            + " world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, actor TEXT NOT NULL,"
                            + " actor_name TEXT NOT NULL, action INTEGER NOT NULL, before TEXT, after TEXT, amount INTEGER DEFAULT 0,"
                            + " rolled INTEGER DEFAULT 0)");
                    s.execute("CREATE INDEX IF NOT EXISTS changes_pos ON changes (world, x, z, y)");
                    s.execute("CREATE INDEX IF NOT EXISTS changes_time ON changes (time)");
                    s.execute("CREATE INDEX IF NOT EXISTS changes_actor ON changes (actor, time)");
                    s.execute("CREATE TABLE IF NOT EXISTS staff_actions (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL,"
                            + " actor TEXT NOT NULL, action TEXT NOT NULL, target TEXT, detail TEXT)");
                    s.execute("CREATE INDEX IF NOT EXISTS staff_actor ON staff_actions (actor COLLATE NOCASE, time)");
                    s.execute("CREATE INDEX IF NOT EXISTS staff_target ON staff_actions (target COLLATE NOCASE, time)");
                    if (keepDays > 0) {
                        s.execute("DELETE FROM changes WHERE time < " + (System.currentTimeMillis() - keepDays * 86_400_000L));
                    }
                }
                return true;
            } catch (ClassNotFoundException | SQLException e) {
                log.severe("The block log could not open its database: " + e.getMessage());
                failed = true;
                pending.clear();
                return false;
            }
        }, worker);
    }

    public boolean ready() {
        return db != null;
    }

    private volatile boolean failed;

    public void add(Entry e) {
        // without a database nothing would ever take them out again
        if (!failed) pending.add(e);
    }

    /** One line of the team protocol: who did what to whom. */
    public record StaffAction(long time, String actor, String action, String target, String detail) {}

    public void staffAction(String actor, String action, String target, String detail) {
        if (failed) return;
        long now = System.currentTimeMillis();
        worker.execute(() -> {
            if (db == null) return;
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO staff_actions (time, actor, action, target, detail) VALUES (?,?,?,?,?)")) {
                ps.setLong(1, now);
                ps.setString(2, actor);
                ps.setString(3, action);
                ps.setString(4, target);
                ps.setString(5, detail == null ? null : (detail.length() > 300 ? detail.substring(0, 300) : detail));
                ps.executeUpdate();
            } catch (SQLException ex) {
                log.warning("Could not write to the team protocol: " + ex.getMessage());
            }
        });
    }

    /** The newest team actions, by or against this name (null: everybody). */
    public CompletableFuture<List<StaffAction>> staffActions(String name, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<StaffAction> out = new java.util.ArrayList<>();
            if (db == null) return out;
            String sql = "SELECT time, actor, action, target, detail FROM staff_actions"
                    + (name == null ? "" : " WHERE actor = ? COLLATE NOCASE OR target = ? COLLATE NOCASE") + " ORDER BY time DESC, id DESC LIMIT ?";
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                int i = 1;
                if (name != null) {
                    ps.setString(i++, name);
                    ps.setString(i++, name);
                }
                ps.setInt(i, limit);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new StaffAction(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            } catch (SQLException ex) {
                log.warning("Could not read the team protocol: " + ex.getMessage());
            }
            return out;
        }, worker);
    }

    /** Writes everything queued so far. */
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(this::write, worker);
    }

    private void write() {
        if (db == null || pending.isEmpty()) return;
        String sql = "INSERT INTO changes (time, world, x, y, z, actor, actor_name, action, before, after, amount) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            db.setAutoCommit(false);
            Entry e;
            int n = 0;
            while ((e = pending.poll()) != null) {
                ps.setLong(1, e.time());
                ps.setString(2, e.world());
                ps.setInt(3, e.x());
                ps.setInt(4, e.y());
                ps.setInt(5, e.z());
                ps.setString(6, e.actor());
                ps.setString(7, e.actorName());
                ps.setInt(8, e.action().ordinal());
                ps.setString(9, e.before());
                ps.setString(10, e.after());
                ps.setInt(11, e.amount());
                ps.addBatch();
                if (++n % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
            db.commit();
        } catch (SQLException ex) {
            log.warning("Could not write to the block log: " + ex.getMessage());
        } finally {
            try {
                db.setAutoCommit(true);
            } catch (SQLException ignored) {
                // the next write tries again
            }
        }
    }

    public CompletableFuture<List<Entry>> find(Query q) {
        return CompletableFuture.supplyAsync(() -> {
            write(); // what just happened is part of the answer
            List<Entry> out = new ArrayList<>();
            if (db == null) return out;
            StringBuilder sql = new StringBuilder("SELECT * FROM changes WHERE time >= ?");
            List<Object> args = new ArrayList<>();
            args.add(q.since);
            if (q.world != null) {
                sql.append(" AND world = ?");
                args.add(q.world);
            }
            if (q.exact) {
                sql.append(" AND x = ? AND y = ? AND z = ?");
                args.add(q.x);
                args.add(q.y);
                args.add(q.z);
            } else if (q.radius >= 0) {
                sql.append(" AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND y BETWEEN ? AND ?");
                args.add(q.x - q.radius);
                args.add(q.x + q.radius);
                args.add(q.z - q.radius);
                args.add(q.z + q.radius);
                args.add(q.y - q.radius);
                args.add(q.y + q.radius);
            }
            if (q.actor != null) {
                sql.append(" AND actor = ?");
                args.add(q.actor);
            }
            if (q.rolledBack != null) {
                sql.append(" AND rolled = ?");
                args.add(q.rolledBack ? 1 : 0);
            }
            if (q.blocksOnly) sql.append(" AND action < ").append(Action.TAKE.ordinal());
            sql.append(" ORDER BY time ").append(q.oldestFirst ? "ASC" : "DESC").append(", id ").append(q.oldestFirst ? "ASC" : "DESC");
            sql.append(" LIMIT ").append(Math.max(1, q.limit));
            try (PreparedStatement ps = db.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    Action[] actions = Action.values();
                    while (rs.next()) {
                        out.add(new Entry(rs.getLong("id"), rs.getLong("time"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"),
                                rs.getInt("z"), rs.getString("actor"), rs.getString("actor_name"), actions[rs.getInt("action")],
                                rs.getString("before"), rs.getString("after"), rs.getInt("amount"), rs.getInt("rolled") == 1));
                    }
                }
            } catch (SQLException e) {
                log.warning("Block log lookup failed: " + e.getMessage());
            }
            return out;
        }, worker);
    }

    /** Marks entries as rolled back or restored. */
    public CompletableFuture<Void> markRolled(List<Long> ids, boolean rolled) {
        return CompletableFuture.runAsync(() -> {
            if (db == null || ids.isEmpty()) return;
            try (PreparedStatement ps = db.prepareStatement("UPDATE changes SET rolled = ? WHERE id = ?")) {
                db.setAutoCommit(false);
                for (long id : ids) {
                    ps.setInt(1, rolled ? 1 : 0);
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
                db.commit();
                db.setAutoCommit(true);
            } catch (SQLException e) {
                log.warning("Could not mark the rollback: " + e.getMessage());
            }
        }, worker);
    }

    public void close() {
        try {
            worker.submit(this::write).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // shutting down anyway
        }
        worker.shutdown();
        try {
            if (db != null) db.close();
        } catch (SQLException ignored) {
            // shutting down anyway
        }
    }
}
