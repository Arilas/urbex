package dev.krona.urbex.varia;

/**
 * This class keeps track of the average calculation time of something. It only keeps the
 * last 100 measurements. It is used for statistics.
 * <p>
 * Synchronized: chunks are timed from every worldgen worker thread and read from the server thread.
 * One monitor per generated chunk is nothing next to generating it.
 */
public class Statistics {

    public static final int COUNT = 100;

    private final long[] times = new long[COUNT];
    private int totalCnt = 0;
    private long minTime = Long.MAX_VALUE;
    private long maxTime = Long.MIN_VALUE;

    public synchronized void addTime(long time) {
        times[(int) (totalCnt % times.length)] = time;
        totalCnt++;
        if (time < minTime) {
            minTime = time;
        }
        if (time > maxTime) {
            maxTime = time;
        }
    }

    public synchronized float getAverageTime() {
        // Calculate the average time starting at totalCnt - times.length and counting 100 items.
        // Use modulo
        long total = 0;
        for (int i = totalCnt + 1; i < totalCnt + 1 + times.length; i++) {
            total += times[i % times.length];
        }
        return (float) total / times.length;
    }

    public synchronized long getMinTime() {
        return minTime;
    }

    public synchronized long getMaxTime() {
        return maxTime;
    }
}
