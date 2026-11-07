package com.bimvo.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.io.InterruptedIOException;

/** Global complete linkage, reliable seeds, conservative short turns.
 * Distance .52 is calibrated on local validation recordings; no fixed speaker count.
 * Complete linkage is retained to avoid merging different voices through a chain of windows.
 */
final class SpeakerClustering {
    static final double DISTANCE = .52, MIN_SIMILARITY = .45, MARGIN = .05;
    static final int MAX_SEEDS = 512;
    static final class Window {
        final long start, end;
        long left, right;
        float[] embedding;
        int label;
        Window(long start, long end) { this.start = this.left = start; this.end = this.right = end; }
    }
    private static final class Pair {
        final int a, b; final float distance;
        Pair(int a, int b, float distance) { this.a = a; this.b = b; this.distance = distance; }
    }
    static float[] normalize(float[] vector) {
        if (vector == null) return null;
        double norm = 0;
        for (float value : vector) { if (!Float.isFinite(value)) return null; norm += value * (double)value; }
        if (norm < 1e-20) return null;
        float[] result = vector.clone();
        for (int i = 0; i < result.length; i++) result[i] /= Math.sqrt(norm);
        return result;
    }
    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * (double)b[i];
        return Math.max(-1, Math.min(1, sum));
    }
    static void cluster(List<Window> windows, BooleanSupplier cancelled) throws InterruptedIOException {
        List<Integer> reliable = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.get(i); w.label = 0; w.embedding = normalize(w.embedding);
            if (w.embedding != null && w.end - w.start >= 19200) reliable.add(i);
        }
        if (reliable.isEmpty()) return;
        List<Integer> seeds = new ArrayList<>();
        int n = Math.min(MAX_SEEDS, reliable.size());
        for (int i = 0; i < n; i++) seeds.add(reliable.get(n == 1 ? 0 : (int)((long)i * (reliable.size() - 1) / (n - 1))));
        float[][] distance = new float[n][n];
        int[] parent = new int[n]; boolean[] active = new boolean[n];
        PriorityQueue<Pair> queue = new PriorityQueue<>(Comparator.comparingDouble((Pair p) -> p.distance)
                .thenComparingInt(p -> p.a).thenComparingInt(p -> p.b));
        for (int i = 0; i < n; i++) {
            check(cancelled); parent[i] = i; active[i] = true;
            for (int j = 0; j < i; j++) {
                distance[j][i] = distance[i][j] = (float)(1 - dot(windows.get(seeds.get(i)).embedding, windows.get(seeds.get(j)).embedding));
                queue.add(new Pair(j, i, distance[j][i]));
            }
        }
        while (!queue.isEmpty()) {
            check(cancelled); Pair pair = queue.poll();
            if (!active[pair.a] || !active[pair.b] || Float.compare(distance[pair.a][pair.b], pair.distance) != 0) continue;
            if (pair.distance > DISTANCE) break;
            int a = pair.a, b = pair.b; active[b] = false; parent[b] = a;
            for (int k = 0; k < n; k++) if (active[k] && k != a) {
                distance[a][k] = distance[k][a] = Math.max(distance[a][k], distance[b][k]);
                queue.add(new Pair(Math.min(a, k), Math.max(a, k), distance[a][k]));
            }
        }
        Map<Integer, Integer> labels = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = i; while (parent[root] != root) root = parent[root];
            if (!labels.containsKey(root)) labels.put(root, labels.size() + 1);
            windows.get(seeds.get(i)).label = labels.get(root);
        }
        int dimension = windows.get(seeds.get(0)).embedding.length;
        float[][] centers = new float[labels.size()][dimension];
        for (int i : seeds) {
            Window w = windows.get(i);
            double weight = Math.min(4, (w.end - w.start) / 16000.0);
            for (int k = 0; k < dimension; k++) centers[w.label - 1][k] += w.embedding[k] * weight;
        }
        for (int i = 0; i < centers.length; i++) centers[i] = normalize(centers[i]);
        for (Window w : windows) {
            check(cancelled);
            if (w.label != 0 || w.embedding == null) continue;
            double best = -2, second = -1; int label = 0;
            for (int i = 0; i < centers.length; i++) {
                double similarity = centers[i] == null ? -1 : dot(w.embedding, centers[i]);
                if (similarity > best) { second = best == -2 ? -1 : best; best = similarity; label = i + 1; }
                else second = Math.max(second, similarity);
            }
            if (best >= MIN_SIMILARITY && best - second >= MARGIN) w.label = label;
        }
        Map<Integer, Integer> chronological = new LinkedHashMap<>();
        windows.sort(Comparator.comparingLong(w -> w.left));
        for (Window w : windows) if (w.label != 0) {
            int before = w.label;
            if (!chronological.containsKey(before)) chronological.put(before, chronological.size() + 1);
            w.label = chronological.get(before);
        }
    }
    static void check(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Обработка отменена");
    }
}
