package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import static com.sts15.enderdrives.inventory.EnderDiskInventory.deserializeItemStackFromBytes;

public class EnderDBManager {
    private static final ConcurrentSkipListMap<AEKey, StoredEntry> dbMap = new ConcurrentSkipListMap<>();
    private static final BlockingQueue<byte[]> walQueue = new LinkedBlockingQueue<>();
    private static final ConcurrentHashMap<AEKey, Long> deltaBuffer = new ConcurrentHashMap<>();
    private static final int MERGE_BUFFER_THRESHOLD = 1000;
    private static File dbFile, currentWAL;
    private static FileOutputStream walFileStream;
    private static DataOutputStream walWriter;
    private static final Object commitLock = new Object();
    private static volatile boolean running = true, dirty = false;
    private static final long MIN_COMMIT_INTERVAL_MS = 2500, MAX_COMMIT_INTERVAL_MS = 60000;
    private static long lastCommitTime = System.currentTimeMillis();
    private static long lastDbCommitTime = System.currentTimeMillis();
    private static final AtomicLong totalItemsWritten = new AtomicLong(0);
    private static final AtomicLong totalCommits = new AtomicLong(0);
    private static final long MIN_DB_COMMIT_INTERVAL_MS = 5000;
    private static final long MAX_DB_COMMIT_INTERVAL_MS = 60000;
    private static final boolean DEBUG_LOG = false;

// ==== Public API ====

    /**
     * Initializes the EnderDB system, loading the database and replaying WAL logs.
     * Sets up the background commit thread and registers a shutdown hook.
     */
    public static void init() {
        try {
            Path worldDir = ServerLifecycleHooks.getCurrentServer()
                    .getWorldPath(LevelResource.ROOT).resolve("data").resolve("enderdrives");
            Files.createDirectories(worldDir);
            dbFile = worldDir.resolve("enderdrives.bin").toFile();
            currentWAL = worldDir.resolve("enderdrives.wal").toFile();
            loadDatabase();
            migrateOldRecords();
            openWALStream();
            replayWALs();
            startBackgroundCommit();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[EnderDB] Shutdown hook triggered.");
                shutdown();
            }));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Shuts down the EnderDB system gracefully by flushing buffers, committing data, and closing WAL streams.
     */
    public static void shutdown() {
        running = false;
        try {
            synchronized (commitLock) {
                flushDeltaBuffer();
                commitDatabase();
                truncateCurrentWAL();
                closeWALStream();
            }
        } catch (IOException ignored) {}
    }

    /**
     * Saves an item delta into the database, merging counts by item key.
     *
     * @param scopePrefix The scope name (e.g. player or global).
     * @param freq        The frequency ID associated with the item.
     * @param itemNbtBinary Serialized ItemStack data.
     * @param deltaCount  The count delta to apply (positive or negative).
     */
    public static void saveItem(String scopePrefix, int freq, byte[] itemNbtBinary, long deltaCount) {
        AEKey key = new AEKey(scopePrefix, freq, itemNbtBinary);
        deltaBuffer.merge(key, deltaCount, Long::sum);

        if (deltaBuffer.size() >= MERGE_BUFFER_THRESHOLD) {
            flushDeltaBuffer();
        }
    }

    /**
     * Retrieves the stored count of an item in the database.
     *
     * @param scopePrefix The scope name.
     * @param freq        The frequency ID.
     * @param itemNbtBinary The serialized item key.
     * @return The current stored count for the item.
     */
    public static long getItemCount(String scopePrefix, int freq, byte[] itemNbtBinary) {
        AEKey key = new AEKey(scopePrefix, freq, itemNbtBinary);
        return dbMap.getOrDefault(key, new StoredEntry(0L, null)).count();
    }

    /**
     * Clears all entries for a given frequency and scope.
     *
     * @param scopePrefix The scope name.
     * @param frequency   The frequency ID to clear.
     */
    public static void clearFrequency(String scopePrefix, int frequency) {
        AEKey from = new AEKey(scopePrefix, frequency, new byte[0]);
        AEKey to = new AEKey(scopePrefix, frequency + 1, new byte[0]);
        NavigableMap<AEKey, StoredEntry> sub = dbMap.subMap(from, true, to, false);
        int removed = sub.size();
        sub.clear();
        log("Cleared frequency %d for scope %s (%d entries)", frequency, scopePrefix, removed);
    }

    /**
     * Gets the number of unique item types stored for a given frequency and scope.
     *
     * @param scopePrefix The scope name.
     * @param freq        The frequency ID.
     * @return The number of unique item keys.
     */
    public static int getTypeCount(String scopePrefix, int freq) {
        AEKey from = new AEKey(scopePrefix, freq, new byte[0]);
        AEKey to = new AEKey(scopePrefix, freq + 1, new byte[0]);
        return dbMap.subMap(from, true, to, false).size();
    }

    /**
     * Queries all items under a given frequency and scope, returning their keys and counts.
     *
     * @param scopePrefix The scope name.
     * @param freq        The frequency ID.
     * @return A list of matching cache entries.
     */
    public static List<AEKeyCacheEntry> queryItemsByFrequency(String scopePrefix, int freq) {
        List<AEKeyCacheEntry> result = new ArrayList<>();
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
            AEKey key = entry.getKey();
            if (key.scope().equals(scopePrefix) && key.freq() == freq) {
                StoredEntry stored = entry.getValue();
                AEItemKey aeKey = stored.aeKey();
                if (aeKey != null) {
                    result.add(new AEKeyCacheEntry(key, aeKey, stored.count()));
                }
            }
        }
        log("QueryItemsByFrequency: scope=%s freq=%d entriesFound=%d", scopePrefix, freq, result.size());
        return result;
    }

    /**
     * Commits the current state of the database to disk, flushing all in-memory changes.
     */
    public static void commitDatabase() {
        try {
            File temp = new File(dbFile.getAbsolutePath() + ".tmp");
            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temp), 1024 * 512))) {
                for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
                    AEKey key = entry.getKey();
                    long count = entry.getValue().count();
                    dos.writeUTF(key.scope());
                    dos.writeInt(key.freq());
                    dos.writeInt(key.itemBytes().length);
                    dos.write(key.itemBytes());
                    dos.writeLong(count);
                }
            }
            Files.move(temp.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            log("Database committed successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


// ==== Public Getters / Stats ====

    public static AtomicLong getTotalItemsWritten() { return totalItemsWritten; }
    public static AtomicLong getTotalCommits() { return totalCommits; }
    public static int getWalQueueSize() { return walQueue.size(); }
    public static int getDatabaseSize() { return dbMap.size(); }
    public static long getDatabaseFileSizeBytes() { return dbFile.exists() ? dbFile.length() : 0; }

// ==== Background Thread Handling ====

    /**
     * Starts the background commit thread that flushes WAL entries and periodically writes the database to disk.
     */
    private static void startBackgroundCommit() {
        Thread t = new Thread(() -> {
            List<byte[]> batch = new ArrayList<>();
            while (running) {
                try {
                    flushDeltaBuffer();

                    int walSizeBytes = walQueue.stream().mapToInt(arr -> arr.length + Long.BYTES + Integer.BYTES).sum();
                    int dynamicBatchSize = Math.min(50_000, Math.max(1_000, walQueue.size() / 10));

                    if (walSizeBytes <= 5 * 1024 * 1024) {
                        walQueue.drainTo(batch);
                    } else {
                        walQueue.drainTo(batch, dynamicBatchSize);
                    }

                    long now = System.currentTimeMillis();

                    boolean minIntervalElapsed = now - lastCommitTime >= MIN_COMMIT_INTERVAL_MS;
                    boolean maxIntervalElapsed = now - lastCommitTime >= MAX_COMMIT_INTERVAL_MS;

                    boolean shouldCommit = (!batch.isEmpty() && minIntervalElapsed)
                            || walQueue.size() >= dynamicBatchSize * 2
                            || maxIntervalElapsed;

                    if (shouldCommit) {
                        synchronized (commitLock) {
                            for (byte[] entry : batch) {
                                walWriter.writeInt(entry.length);
                                walWriter.write(entry);
                                walWriter.writeLong(checksum(entry));
                                totalItemsWritten.incrementAndGet();
                                lastCommitTime = now;
                            }
                            walWriter.flush();

                            if (!batch.isEmpty()) {
                                log("Committed %d WAL entries. TotalItems=%d", batch.size(), totalItemsWritten);
                            }
                            boolean minDbCommitElapsed = now - lastDbCommitTime >= MIN_DB_COMMIT_INTERVAL_MS;
                            boolean maxDbCommitElapsed = now - lastDbCommitTime >= MAX_DB_COMMIT_INTERVAL_MS;
                            if (dirty && (batch.size() > 1000 || maxDbCommitElapsed || minDbCommitElapsed)) {
                                commitDatabase();
                                truncateCurrentWAL();
                                lastDbCommitTime = now;
                                totalCommits.incrementAndGet();
                            }
                        }
                        batch.clear();
                    }

                    long lastReplayCheck = System.currentTimeMillis();
                    final long REPLAY_IDLE_INTERVAL_MS = 10_000;
                    if (walQueue.isEmpty()) {
                        if (now - lastReplayCheck >= REPLAY_IDLE_INTERVAL_MS) {
                            lastReplayCheck = now;
                            try {
                                File dir = currentWAL.getParentFile();
                                File[] rotatedWALs = dir.listFiles((d, name) -> name.matches("enderdrives\\.wal\\.\\d+"));
                                if (rotatedWALs != null && rotatedWALs.length > 0) {
                                    Arrays.sort(rotatedWALs, Comparator.comparing(File::getName));
                                    for (File wal : rotatedWALs) {
                                        if (wal.length() > 0) {
                                            log("Idle Replay: Processing %s", wal.getName());
                                            replayAndDeleteWAL(wal);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    Thread.sleep(5);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "EnderDB-CommitThread");

        t.setDaemon(true);
        t.start();
    }

// ==== WAL Handling & Processing ====

    /**
     * Flushes the delta buffer into the WAL queue, preparing it for commit.
     */
    private static void flushDeltaBuffer() {
        for (Map.Entry<AEKey, Long> entry : deltaBuffer.entrySet()) {
            AEKey key = entry.getKey();
            long delta = entry.getValue();
            if (delta == 0) continue;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeUTF(key.scope());
                dos.writeInt(key.freq());
                dos.writeInt(key.itemBytes().length);
                dos.write(key.itemBytes());
                dos.writeLong(delta);
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }

            byte[] walEntry = baos.toByteArray();
            walQueue.add(walEntry);
            applyBinaryOperation(walEntry);
        }
        deltaBuffer.clear();
    }

    /**
     * Applies a binary WAL entry to the in-memory database map.
     *
     * @param data Serialized WAL operation.
     */
    private static void applyBinaryOperation(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            String scopePrefix = dis.readUTF();
            int freq = dis.readInt();
            int keyLen = dis.readInt();
            byte[] keyBytes = new byte[keyLen];
            dis.readFully(keyBytes);
            long delta = dis.readLong();
            AEKey key = new AEKey(scopePrefix, freq, keyBytes);
            long oldVal = dbMap.getOrDefault(key, new StoredEntry(0L, null)).count();
            long newVal = oldVal + delta;
            if (newVal <= 0) {
                dbMap.remove(key);
            } else {
                AEItemKey aeKey = null;
                try {
                    ItemStack stack = deserializeItemStackFromBytes(keyBytes);
                    if (!stack.isEmpty()) {
                        aeKey = AEItemKey.of(stack);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (aeKey != null) {
                    dbMap.put(key, new StoredEntry(newVal, aeKey));
                } else {
                    dbMap.put(key, new StoredEntry(newVal, null));
                }
            }
            log("Applying WAL: key=%s delta=%d old=%d new=%d", key, delta, oldVal, newVal);
            dirty = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Truncates (clears and resets) the current WAL file to a clean state.
     */
    private static void truncateCurrentWAL() {
        try {
            closeWALStream();
            new FileOutputStream(currentWAL).close();
            openWALStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Replays the current WAL and any rotated WAL files, applying their entries to memory.
     *
     * @throws IOException if file access fails.
     */
    private static void replayWALs() throws IOException {
        if (currentWAL.exists()) {
            replayAndDeleteWAL(currentWAL);
        }
        File dir = currentWAL.getParentFile();
        File[] rotatedWALs = dir.listFiles((d, name) -> name.startsWith("enderdrives.wal.") && name.matches(".*\\.\\d+$"));
        if (rotatedWALs != null) {
            Arrays.sort(rotatedWALs);
            for (File rotated : rotatedWALs) {
                replayAndDeleteWAL(rotated);
            }
        }
        truncateCurrentWAL();
    }

    /**
     * Replays a specific WAL file and deletes it after successful processing.
     *
     * @param walFile The WAL file to replay.
     */
    private static void replayAndDeleteWAL(File walFile) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(walFile)))) {
            while (dis.available() > 0) {
                int length = dis.readInt();
                byte[] data = new byte[length];
                dis.readFully(data);
                long storedChecksum = dis.readLong();
                if (checksum(data) != storedChecksum) continue;
                applyBinaryOperation(data);
            }
            commitDatabase();
            System.out.println("Attempting to delete WAL: " + walFile.getName());
            boolean deleted = walFile.delete();
            System.out.println("WAL deleted: " + deleted);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

// ==== File & Stream Management ====

    /**
     * Opens the WAL stream for appending new entries.
     *
     * @throws IOException if opening fails.
     */
    private static void openWALStream() throws IOException {
        walFileStream = new FileOutputStream(currentWAL, true);
        walWriter = new DataOutputStream(new BufferedOutputStream(walFileStream));
    }

    /**
     * Closes the current WAL output stream.
     *
     * @throws IOException if closing fails.
     */
    private static void closeWALStream() throws IOException {
        walWriter.close();
        walFileStream.close();
    }

    /**
     * Loads the database from disk into memory, if the file exists.
     *
     * @throws IOException if reading fails.
     */
    private static void loadDatabase() throws IOException {
        if (!dbFile.exists()) return;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(dbFile)))) {
            dbMap.clear();
            while (dis.available() > 0) {
                dis.mark(512);
                try {
                    String scope = dis.readUTF();
                    int freq = dis.readInt();
                    int keyLen = dis.readInt();
                    byte[] keyBytes = new byte[keyLen];
                    dis.readFully(keyBytes);
                    long count = dis.readLong();
                    AEKey key = new AEKey(scope, freq, keyBytes);
                    dbMap.put(key, new StoredEntry(count, null));
                } catch (EOFException | UTFDataFormatException e) {
                    dis.reset();
                    int freq = dis.readInt();
                    int keyLen = dis.readInt();
                    byte[] keyBytes = new byte[keyLen];
                    dis.readFully(keyBytes);
                    long count = dis.readLong();
                    AEKey key = new AEKey("global", freq, keyBytes);
                    dbMap.put(key, new StoredEntry(count, null));
                }
            }
        }
    }

// ==== Internal DB Tools ====

    /**
     * Calculates a checksum for a byte array using CRC32.
     *
     * @param data The byte array to checksum.
     * @return The CRC32 value.
     */
    private static long checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Logs debug messages to console if DEBUG_LOG is enabled.
     *
     * @param format The message format string.
     * @param args   Format arguments.
     */
    private static void log(String format, Object... args) {
        if (DEBUG_LOG) System.out.printf("[EnderDB] " + format + "%n", args);
    }

    /**
     * Migrates any legacy records with malformed or missing scope names to the "global" scope.
     */
    private static void migrateOldRecords() {
        List<Map.Entry<AEKey, StoredEntry>> toMigrate = new ArrayList<>();
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
            AEKey key = entry.getKey();
            String scope = key.scope();
            if (scope == null || scope.isEmpty() || scope.length() > 0 && !scope.matches("^[a-z]+_[a-z0-9\\-]+$") && !scope.equals("global")) {
                toMigrate.add(entry);
            }
        }

        if (toMigrate.isEmpty()) return;

        System.out.println("[EnderDB] Detected " + toMigrate.size() + " old-format records. Migrating to global scope...");

        for (Map.Entry<AEKey, StoredEntry> entry : toMigrate) {
            AEKey oldKey = entry.getKey();
            StoredEntry value = entry.getValue();
            AEKey newKey = new AEKey("global", oldKey.freq(), oldKey.itemBytes());
            long existing = dbMap.getOrDefault(newKey, new StoredEntry(0L, null)).count();
            dbMap.put(newKey, new StoredEntry(existing + value.count(), null));
            dbMap.remove(oldKey);
        }
        System.out.println("[EnderDB] Migration complete. Migrated " + toMigrate.size() + " entries.");
        dirty = true;
    }

}
