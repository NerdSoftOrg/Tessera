package com.nerdsoft.mods.tessera.atlas;

import java.util.*;

public final class TextureFamilyDetector {

    private static final int DCT_SIZE = 8;
    private static final double[][] COSINE_TABLE = buildCosineTable();

    private static double[][] downsampleToLuminance(byte[] rgba8, int width, int height) {
        double[][] luminance = new double[DCT_SIZE][DCT_SIZE];
        for (int by = 0; by < DCT_SIZE; by++) {
            for (int bx = 0; bx < DCT_SIZE; bx++) {
                int startX = (bx * width) / DCT_SIZE;
                int endX = Math.max(startX + 1, ((bx + 1) * width) / DCT_SIZE);
                int startY = (by * height) / DCT_SIZE;
                int endY = Math.max(startY + 1, ((by + 1) * height) / DCT_SIZE);

                double sum = 0.0;
                int samples = 0;
                for (int y = startY; y < endY && y < height; y++) {
                    for (int x = startX; x < endX && x < width; x++) {
                        int offset = (y * width + x) * 4;
                        int r = rgba8[offset] & 0xFF;
                        int g = rgba8[offset + 1] & 0xFF;
                        int b = rgba8[offset + 2] & 0xFF;
                        sum += 0.299 * r + 0.587 * g + 0.114 * b;
                        samples++;
                    }
                }
                luminance[by][bx] = samples == 0 ? 0.0 : sum / samples;
            }
        }
        return luminance;
    }

    private static double[][] forwardDct(double[][] block) {
        double[][] result = new double[DCT_SIZE][DCT_SIZE];
        for (int u = 0; u < DCT_SIZE; u++) {
            for (int v = 0; v < DCT_SIZE; v++) {
                double sum = 0.0;
                for (int x = 0; x < DCT_SIZE; x++) {
                    for (int y = 0; y < DCT_SIZE; y++) {
                        sum += block[y][x] * COSINE_TABLE[x][u] * COSINE_TABLE[y][v];
                    }
                }
                double cu = u == 0 ? 1.0 / Math.sqrt(2.0) : 1.0;
                double cv = v == 0 ? 1.0 / Math.sqrt(2.0) : 1.0;
                result[v][u] = 0.25 * cu * cv * sum;
            }
        }
        return result;
    }

    private static double[][] buildCosineTable() {
        double[][] table = new double[DCT_SIZE][DCT_SIZE];
        for (int x = 0; x < DCT_SIZE; x++) {
            for (int u = 0; u < DCT_SIZE; u++) {
                table[x][u] = Math.cos(((2.0 * x + 1.0) * u * Math.PI) / (2.0 * DCT_SIZE));
            }
        }
        return table;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[mid - 1] + sorted[mid]) / 2.0
                : sorted[mid];
    }

    public long fingerprint(byte[] rgba8, int width, int height) {
        double[][] luminance = downsampleToLuminance(rgba8, width, height);
        double[][] dct = forwardDct(luminance);

        double[] lowFrequency = new double[DCT_SIZE * DCT_SIZE - 1];
        int index = 0;
        for (int y = 0; y < DCT_SIZE; y++) {
            for (int x = 0; x < DCT_SIZE; x++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                lowFrequency[index++] = dct[y][x];
            }
        }

        double median = median(lowFrequency);

        long fingerprint = 0L;
        int bit = 0;
        for (int y = 0; y < DCT_SIZE; y++) {
            for (int x = 0; x < DCT_SIZE; x++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                if (dct[y][x] > median) {
                    fingerprint |= (1L << bit);
                }
                bit++;
            }
        }
        return fingerprint;
    }

    public List<Family> groupBySimilarity(List<SpriteSample> samples, int maxHammingDistance) {
        int count = samples.size();
        long[] fingerprints = new long[count];
        boolean[] tinted = new boolean[count];
        for (int i = 0; i < count; i++) {
            SpriteSample sample = samples.get(i);
            fingerprints[i] = fingerprint(sample.rgba8(), sample.width(), sample.height());
            tinted[i] = sample.tinted();
        }

        boolean[] visited = new boolean[count];
        List<Family> families = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            if (visited[i]) {
                continue;
            }
            visited[i] = true;

            List<Integer> members = new ArrayList<>();
            Deque<Integer> frontier = new ArrayDeque<>();
            frontier.push(i);

            while (!frontier.isEmpty()) {
                int current = frontier.pop();
                for (int j = 0; j < count; j++) {
                    if (visited[j] || tinted[j] != tinted[current]) {
                        continue;
                    }
                    if (Long.bitCount(fingerprints[current] ^ fingerprints[j]) <= maxHammingDistance) {
                        visited[j] = true;
                        members.add(samples.get(j).id());
                        frontier.push(j);
                    }
                }
            }

            families.add(new Family(samples.get(i).id(), members));
        }

        return families;
    }

    public record SpriteSample(int id, int width, int height, byte[] rgba8, boolean tinted) {
    }

    public record Family(int representativeId, List<Integer> memberIds) {
    }
}
