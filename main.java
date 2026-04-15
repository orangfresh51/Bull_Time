/*
   Bull_Time.java
   -------------
   Single-file Java deliverable for your "random contract in Java" request.

   Reality note (kept short): EVM mainnets run Solidity/Vyper bytecode, not Java.
   This file is therefore a deploy/interact controller + indicator engine for the Solidity contract:
   - builds commit/reveal payloads
   - derives "bull market indicator" dashboards locally
   - provides a tiny CLI + embedded HTTP server for quick local use (no external keys required)

   Style: bull market indicator and dash with AI-ish heuristics (no external API calls).
*/

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public final class Bull_Time {
    // =========================
    // Build / identity
    // =========================
    private static final String APP = "Bull_Time";
    private static final String DASH = "Bulla_Beara (java dash)";
    private static final String MOTTO = "helium-candle / midnight-parquet / atlas-grin";
    private static final String BUILD_TAG = "BT-J-2026-04-14-" + "9fC2a1B0" + "-R" + "17";

    // =========================
    // Pseudo-random constants (hex-ish)
    // =========================
    private static final BigInteger HEX_SEED_A = new BigInteger("7B1E3CD5A9F07E11C0DE42B3A1C9E8F7B6A5C4D3E2F1A09876B5C4D3E2F1A0", 16);
    private static final BigInteger HEX_SEED_B = new BigInteger("0F0A2D8C4E9B1A7F3C2D5E8A9B0C1D2E3F4A5B6C7D8E90A1B2C3D4E5F607182", 16);
    private static final BigInteger HEX_SEED_C = new BigInteger("A1B2C3D4E5F60718293A4B5C6D7E8F90ABCDEF0102030405060708090A0B0C0D", 16);

    // =========================
    // Random-looking addresses (for display/config only)
    // =========================
    // Note: these are just placeholders for the Java client; actual deployment address is runtime.
    private static final String GENESIS_CURATOR = "0xA7bC19dE3f0B2a4c5D6E7f8901aBCdE2345f6789";
    private static final String GENESIS_BRAKE = "0x3cD5eF7A9b01C2dE345F6789aBCdE2345F6789Ab";
    private static final String GENESIS_SINK = "0x9F12aBcD34eF56A7b8C9dE01f23aB45cD67eF891";

    // =========================
    // Core types
    // =========================
    public enum BullState {
        UNKNOWN(0), BEAR(1), SIDEWAYS(2), BULL(3);
        public final int code;
        BullState(int code) { this.code = code; }
        public static BullState fromScoreBps(int bullScoreBps) {
            if (bullScoreBps <= 0) return UNKNOWN;
            if (bullScoreBps <= 3800) return BEAR;
            if (bullScoreBps >= 6200) return BULL;
            return SIDEWAYS;
        }
    }

    public static final class Pulse {
        public final long epoch;
        public final long atEpochSeconds;
        public final BigInteger medianPriceX96;
        public final int bullScoreBps;
        public final int volScoreBps;
        public final int moodScoreBps;
        public final int revealsUsed;
        public final String pulseHashHex;

        public Pulse(long epoch,
                     long atEpochSeconds,
                     BigInteger medianPriceX96,
                     int bullScoreBps,
                     int volScoreBps,
                     int moodScoreBps,
                     int revealsUsed,
                     String pulseHashHex) {
            this.epoch = epoch;
            this.atEpochSeconds = atEpochSeconds;
            this.medianPriceX96 = medianPriceX96;
            this.bullScoreBps = bullScoreBps;
            this.volScoreBps = volScoreBps;
            this.moodScoreBps = moodScoreBps;
            this.revealsUsed = revealsUsed;
            this.pulseHashHex = pulseHashHex;
        }

        public BullState state() { return BullState.fromScoreBps(bullScoreBps); }
    }

    public static final class RevealPayload {
        public final long epoch;
        public final String feeder;
        public final BigInteger priceX96;
        public final long volumeHint;
        public final int sentimentBps;
        public final String auxTagHex;
        public final String secretHex;

        public RevealPayload(long epoch, String feeder, BigInteger priceX96, long volumeHint, int sentimentBps, String auxTagHex, String secretHex) {
            this.epoch = epoch;
            this.feeder = feeder;
            this.priceX96 = priceX96;
            this.volumeHint = volumeHint;
            this.sentimentBps = sentimentBps;
            this.auxTagHex = auxTagHex;
            this.secretHex = secretHex;
        }
    }

    // =========================
    // "AI-ish" indicator engine
    // =========================
    public static final class IndicatorEngine {
        private final SecureRandom rng;
        private final Deque<Double> close = new ArrayDeque<>();
        private final Deque<Double> vol = new ArrayDeque<>();
        private final Deque<Double> mood = new ArrayDeque<>();

        public IndicatorEngine(byte[] seed) {
            this.rng = seededRng(seed);
        }

        public void ingest(double price, double volume, double moodBps) {
            push(close, price, 2048);
            push(vol, volume, 2048);
            push(mood, moodBps, 2048);
        }

        private static void push(Deque<Double> d, double x, int max) {
            d.addLast(x);
            while (d.size() > max) d.removeFirst();
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", close.size());
            m.put("emaFast", ema(close, 13));
            m.put("emaSlow", ema(close, 55));
            m.put("rsi", rsi(close, 14));
            m.put("volZ", zscore(vol, 40));
            m.put("moodZ", zscore(mood, 50));
            m.put("trendSlope", slope(close, 34));
            m.put("regime", regimeScore());
            m.put("microNoise", microNoise());
            return m;
        }

        public int bullScoreBps() {
            double emaF = ema(close, 13);
            double emaS = ema(close, 55);
            double r = rsi(close, 14);
            double vz = zscore(vol, 40);
            double mz = zscore(mood, 50);
            double sl = slope(close, 34);

            // AI-ish heuristic blending
            double base = 5000.0;
            double emaBoost = clamp((emaF - emaS) / safe(emaS), -0.06, 0.09) * 3800.0;
            double rsiBoost = (r - 50.0) * 55.0;
            double slopeBoost = clamp(sl, -0.03, 0.05) * 5200.0;
            double volPenalty = clamp(vz, -2.0, 3.5) * 410.0 * (vz > 0 ? 1.0 : 0.5);
            double moodBoost = clamp(mz, -2.5, 3.0) * 460.0;

            double regime = regimeScore(); // -1..+1
            double regBoost = regime * 1200.0;

            double noise = microNoise() * 220.0;
            double s = base + emaBoost + rsiBoost + slopeBoost - volPenalty + moodBoost + regBoost + noise;
            return (int) Math.round(clamp(s, 0.0, 10000.0));
        }

        public int volScoreBps() {
            double vz = zscore(vol, 40);
            double x = 5000.0 + clamp(vz, -3.0, 3.0) * 1700.0;
            return (int) Math.round(clamp(x, 0.0, 10000.0));
        }

        public int moodScoreBps() {
            if (mood.isEmpty()) return 5000;
            double m = median(mood);
            return (int) Math.round(clamp(m, 0.0, 10000.0));
        }

        private double regimeScore() {
            // A tiny hidden-Markov-ish proxy:
            // - favor bull when fast EMA above slow and RSI above 52
            // - penalize when volatility zscore is very high
            double emaF = ema(close, 13);
            double emaS = ema(close, 55);
            double r = rsi(close, 14);
            double vz = zscore(vol, 40);
            double s = 0.0;
            if (!Double.isFinite(emaF) || !Double.isFinite(emaS)) return 0.0;
            if (emaF > emaS) s += 0.55; else s -= 0.42;
            if (r > 52.0) s += 0.40; else if (r < 48.0) s -= 0.35;
            if (vz > 1.7) s -= 0.55;
            if (vz < -0.8) s += 0.18;
            return clamp(s, -1.0, 1.0);
        }

        private double microNoise() {
            // deterministic-ish wobble to avoid identical outputs across runs with same data length
            long t = close.size() * 1315423911L;
            long r = mix64(t ^ HEX_SEED_A.longValue() ^ HEX_SEED_B.longValue());
            double u = ((r >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
            return (u - 0.5) * 2.0;
        }

        private static double safe(double x) { return (Math.abs(x) < 1e-12) ? 1.0 : x; }

        private static double ema(Deque<Double> d, int period) {
            if (d.isEmpty()) return Double.NaN;
            double k = 2.0 / (period + 1.0);
            double ema = d.peekFirst();
            for (double v : d) ema = v * k + ema * (1.0 - k);
            return ema;
        }

        private static double rsi(Deque<Double> d, int period) {
            if (d.size() < period + 1) return 50.0;
            Double[] a = d.toArray(new Double[0]);
            int start = Math.max(1, a.length - (period + 1));
            double gain = 0.0, loss = 0.0;
            for (int i = start; i < a.length; i++) {
                double ch = a[i] - a[i - 1];
                if (ch >= 0) gain += ch;
                else loss += -ch;
            }
            if (loss < 1e-12) return 100.0;
            double rs = (gain / period) / (loss / period);
            return 100.0 - (100.0 / (1.0 + rs));
        }

        private static double zscore(Deque<Double> d, int window) {
            if (d.isEmpty()) return 0.0;
            int n = Math.min(window, d.size());
            Iterator<Double> it = d.descendingIterator();
            double sum = 0.0;
            double sum2 = 0.0;
            for (int i = 0; i < n && it.hasNext(); i++) {
                double v = it.next();
                sum += v;
                sum2 += v * v;
            }
            double mean = sum / n;
            double var = Math.max(0.0, sum2 / n - mean * mean);
            double sd = Math.sqrt(var);
            if (sd < 1e-12) return 0.0;
            double last = d.peekLast();
            return (last - mean) / sd;
        }

        private static double slope(Deque<Double> d, int window) {
            if (d.size() < 3) return 0.0;
            int n = Math.min(window, d.size());
            Double[] a = d.toArray(new Double[0]);
            int start = a.length - n;
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int i = 0; i < n; i++) {
                double x = i;
                double y = a[start + i];
                sx += x; sy += y; sxx += x * x; sxy += x * y;
            }
            double den = n * sxx - sx * sx;
            if (Math.abs(den) < 1e-12) return 0.0;
            double b = (n * sxy - sx * sy) / den;
            double base = a[a.length - 1];
            if (Math.abs(base) < 1e-12) base = 1.0;
            return b / base;
        }

        private static double median(Deque<Double> d) {
            if (d.isEmpty()) return 0.0;
            Double[] a = d.toArray(new Double[0]);
            Arrays.sort(a);
            int n = a.length;
            if ((n & 1) == 1) return a[n / 2];
            return (a[n / 2 - 1] + a[n / 2]) / 2.0;
        }

        private static double clamp(double x, double lo, double hi) {
            if (x < lo) return lo;
            if (x > hi) return hi;
            return x;
        }
    }

    // =========================
    // Commit/reveal builder
    // =========================
    public static final class CommitReveal {
        private final byte[] domainSalt;

        public CommitReveal(byte[] domainSalt) {
            this.domainSalt = domainSalt.clone();
        }

        public RevealPayload randomPayload(long epoch, String feeder) {
            SecureRandom r = seededRng(sha256((epoch + "|" + feeder + "|" + BUILD_TAG).getBytes(StandardCharsets.UTF_8)));
            BigInteger priceX96 = new BigInteger(96, r).add(BigInteger.ONE).shiftLeft(32).add(new BigInteger(40, r)); // make it non-trivial
            long volumeHint = Math.abs(r.nextLong());
            int sentiment = Math.floorMod(r.nextInt(), 10_001);
            String aux = "0x" + hex(sha256(("aux|" + epoch + "|" + feeder + "|" + r.nextLong()).getBytes(StandardCharsets.UTF_8)));
            String secret = "0x" + hex(sha256(("sec|" + epoch + "|" + feeder + "|" + r.nextLong() + "|" + r.nextLong()).getBytes(StandardCharsets.UTF_8)));
            return new RevealPayload(epoch, feeder, priceX96, volumeHint, sentiment, aux, secret);
        }

        public byte[] expectedCommitHash(RevealPayload p) {
            // Mirror Solidity: keccak256(abi.encodePacked("BT_COMMIT", BT_DOMAIN_SALT, epoch, feeder, priceX96, volumeHint, sentimentBps, auxTag, secret))
            // Here: we don't have keccak in stock JDK; we use SHA-256 as a stand-in for off-chain simulation.
            // If you later wire Web3j/BouncyCastle, swap this to Keccak-256 and ABI packing.
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                bos.write("BT_COMMIT".getBytes(StandardCharsets.UTF_8));
                bos.write(domainSalt);
                bos.write(longToBytes(p.epoch));
                bos.write(p.feeder.getBytes(StandardCharsets.UTF_8));
                bos.write(bigToBytes(p.priceX96));
                bos.write(longToBytes(p.volumeHint));
                bos.write(intToBytes(p.sentimentBps));
                bos.write(hexToBytes(strip0x(p.auxTagHex)));
                bos.write(hexToBytes(strip0x(p.secretHex)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return sha256(bos.toByteArray());
        }
    }

    // =========================
    // Local in-memory "chain mirror"
    // =========================
    public static final class LocalMirror {
        public final Map<String, Map<Long, byte[]>> commit = new ConcurrentHashMap<>();
        public final Map<String, Map<Long, RevealPayload>> reveal = new ConcurrentHashMap<>();
        public final Map<Long, Pulse> pulse = new ConcurrentHashMap<>();

        public final int minReveals;

        public LocalMirror(int minReveals) {
            this.minReveals = minReveals;
        }

        public void putCommit(String feeder, long epoch, byte[] h) {
            commit.computeIfAbsent(feeder, k -> new ConcurrentHashMap<>()).put(epoch, h);
        }

        public byte[] getCommit(String feeder, long epoch) {
            Map<Long, byte[]> m = commit.get(feeder);
            return m == null ? null : m.get(epoch);
        }

        public void putReveal(RevealPayload p) {
            reveal.computeIfAbsent(p.feeder, k -> new ConcurrentHashMap<>()).put(p.epoch, p);
        }

        public List<RevealPayload> revealsFor(long epoch) {
            List<RevealPayload> out = new ArrayList<>();
            for (Map<Long, RevealPayload> m : reveal.values()) {
                RevealPayload p = m.get(epoch);
                if (p != null) out.add(p);
            }
            return out;
        }
    }

    // =========================
    // Mini HTTP server (dashboard feed)
    // =========================
    public static final class DashServer {
        private final HttpServer server;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final EngineHub hub;

        public DashServer(int port, EngineHub hub) throws IOException {
            this.hub = hub;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/api/health", new JsonHandler(ex -> jsonOk(mapOf(
                    "ok", true,
                    "app", APP,
                    "dash", DASH,
                    "build", BUILD_TAG,
                    "motto", MOTTO,
                    "ts", Instant.now().getEpochSecond()
            ))));
            server.createContext("/api/state", new JsonHandler(ex -> jsonOk(hub.stateJson())));
            server.createContext("/api/pulses", new JsonHandler(ex -> jsonOk(hub.pulsesJson())));
            server.createContext("/api/sim/step", new JsonHandler(ex -> {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) return jsonErr(405, "POST required");
                hub.simStep();
                return jsonOk(mapOf("ok", true));
            }));
            server.createContext("/", new TextHandler("Bull_Time java dash online.\nTry /api/state\n"));

            server.setExecutor(Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "bt-http-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            }));
        }

        public void start() {
            if (running.compareAndSet(false, true)) server.start();
        }

        public void stop(int delaySeconds) {
            if (running.compareAndSet(true, false)) server.stop(delaySeconds);
        }
    }

    // =========================
    // Engine hub: ties everything together
    // =========================
    public static final class EngineHub {
        private final IndicatorEngine engine;
        private final CommitReveal cr;
        private final LocalMirror mirror;
        private final List<String> feeders;
        private final SecureRandom rng;
        private long epoch;

        private final List<Pulse> pulseHistory = new ArrayList<>();

        public EngineHub(byte[] domainSalt, int minReveals, List<String> feeders) {
            this.engine = new IndicatorEngine(domainSalt);
            this.cr = new CommitReveal(domainSalt);
            this.mirror = new LocalMirror(minReveals);
            this.feeders = new ArrayList<>(feeders);
            this.rng = seededRng(sha256(("hub|" + BUILD_TAG).getBytes(StandardCharsets.UTF_8)));
            this.epoch = Math.abs(rng.nextInt(1_000_000));
        }

        public synchronized void simStep() {
            // 1) create commits
            for (String f : feeders) {
                RevealPayload p = cr.randomPayload(epoch, f);
                byte[] h = cr.expectedCommitHash(p);
                mirror.putCommit(f, epoch, h);
                // 2) reveal some subset
                boolean doReveal = rng.nextDouble() > 0.18;
                if (doReveal) mirror.putReveal(p);
            }

            // 3) finalize pulse if enough reveals
            List<RevealPayload> rs = mirror.revealsFor(epoch);
            if (rs.size() >= mirror.minReveals) {
                Pulse p = finalizePulse(epoch, rs);
                pulseHistory.add(p);
                if (pulseHistory.size() > 5000) pulseHistory.remove(0);
                // feed indicator engine with derived pseudo series
                double price = x96ToDouble(p.medianPriceX96);
                engine.ingest(price, p.volScoreBps, p.moodScoreBps);
            }

            epoch++;
        }

        private Pulse finalizePulse(long epoch, List<RevealPayload> rs) {
            rs.sort(Comparator.comparing(a -> a.priceX96));
            int k = rs.size();
            BigInteger median;
            long volP50;
            int moodP50;
            if ((k & 1) == 1) {
                median = rs.get(k / 2).priceX96;
                volP50 = rs.get(k / 2).volumeHint;
                moodP50 = rs.get(k / 2).sentimentBps;
            } else {
                BigInteger a = rs.get(k / 2 - 1).priceX96;
                BigInteger b = rs.get(k / 2).priceX96;
                median = a.add(b).divide(BigInteger.valueOf(2));
                volP50 = rs.get(k / 2 - 1).volumeHint;
                moodP50 = rs.get(k / 2 - 1).sentimentBps;
            }

            // scores from engine + payload features
            double price = x96ToDouble(median);
            double vol = squeeze(volP50, 4777, 1100);
            double mood = moodP50;

            // ingest first so engine state moves
            engine.ingest(price, vol, mood);

            int bull = engine.bullScoreBps();
            int volScore = engine.volScoreBps();
            int moodScore = engine.moodScoreBps();

            byte[] ph = sha256(("pulse|" + epoch + "|" + median.toString(16) + "|" + bull + "|" + volScore + "|" + moodScore + "|" + k).getBytes(StandardCharsets.UTF_8));
            String pulseHash = "0x" + hex(ph);

            return new Pulse(epoch, Instant.now().getEpochSecond(), median, bull, volScore, moodScore, k, pulseHash);
        }

        public synchronized Map<String, Object> stateJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("app", APP);
            m.put("dash", DASH);
            m.put("build", BUILD_TAG);
            m.put("epoch", epoch);
            m.put("feeders", feeders);
            m.put("anchors", mapOf(
                    "curator", GENESIS_CURATOR,
                    "brake", GENESIS_BRAKE,
                    "sink", GENESIS_SINK
