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
            ));
            Pulse last = pulseHistory.isEmpty() ? null : pulseHistory.get(pulseHistory.size() - 1);
            if (last != null) {
                m.put("lastPulse", pulseToJson(last));
                m.put("state", last.state().name());
            } else {
                m.put("lastPulse", null);
                m.put("state", BullState.UNKNOWN.name());
            }
            m.put("engine", engine.snapshot());
            m.put("ts", Instant.now().getEpochSecond());
            return m;
        }

        public synchronized Map<String, Object> pulsesJson() {
            List<Map<String, Object>> list = new ArrayList<>();
            int start = Math.max(0, pulseHistory.size() - 200);
            for (int i = start; i < pulseHistory.size(); i++) list.add(pulseToJson(pulseHistory.get(i)));
            return mapOf("pulses", list);
        }

        private static Map<String, Object> pulseToJson(Pulse p) {
            return mapOf(
                    "epoch", p.epoch,
                    "at", p.atEpochSeconds,
                    "atIso", iso(p.atEpochSeconds),
                    "medianPriceX96", "0x" + p.medianPriceX96.toString(16),
                    "bullScoreBps", p.bullScoreBps,
                    "volScoreBps", p.volScoreBps,
                    "moodScoreBps", p.moodScoreBps,
                    "revealsUsed", p.revealsUsed,
                    "state", p.state().name(),
                    "pulseHash", p.pulseHashHex
            );
        }
    }

    // =========================
    // CLI
    // =========================
    public static void main(String[] args) throws Exception {
        Map<String, String> flags = parseFlags(args);
        int port = parseInt(flags.getOrDefault("port", "8837"), 8837);
        boolean serve = "1".equals(flags.getOrDefault("serve", "1"));
        boolean simulate = "1".equals(flags.getOrDefault("simulate", "1"));
        int steps = parseInt(flags.getOrDefault("steps", "200"), 200);
        int minReveals = parseInt(flags.getOrDefault("minReveals", "5"), 5);

        byte[] domainSalt = sha256((MOTTO + "|" + GENESIS_CURATOR + "|" + Instant.now().getEpochSecond() + "|" + HEX_SEED_C.toString(16)).getBytes(StandardCharsets.UTF_8));
        List<String> feeders = defaultFeeders();

        EngineHub hub = new EngineHub(domainSalt, minReveals, feeders);
        DashServer server = new DashServer(port, hub);
        if (serve) server.start();

        println(banner(port, serve, simulate, steps, minReveals, feeders.size()));

        if (simulate) {
            for (int i = 0; i < steps; i++) {
                hub.simStep();
                if (i % 25 == 0) {
                    Map<String, Object> s = hub.stateJson();
                    Object lp = s.get("lastPulse");
                    String state = String.valueOf(s.get("state"));
                    println("step=" + i + " state=" + state + " lastPulse=" + (lp == null ? "null" : "ok"));
                }
                Thread.sleep(30);
            }
        }

        if (serve) {
            println("Server running. Ctrl+C to exit.");
            // keep alive
            Thread.currentThread().join();
        }
    }

    private static String banner(int port, boolean serve, boolean simulate, int steps, int minReveals, int feederN) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(APP).append(" / ").append(DASH).append("\n");
        sb.append("build: ").append(BUILD_TAG).append("\n");
        sb.append("motto: ").append(MOTTO).append("\n");
        sb.append("anchors: ").append(GENESIS_CURATOR).append(" | ").append(GENESIS_BRAKE).append(" | ").append(GENESIS_SINK).append("\n");
        sb.append("serve=").append(serve).append(" port=").append(port).append("\n");
        sb.append("simulate=").append(simulate).append(" steps=").append(steps).append(" minReveals=").append(minReveals).append(" feeders=").append(feederN).append("\n");
        sb.append("endpoints: /api/health /api/state /api/pulses /api/sim/step\n");
        return sb.toString();
    }

    // =========================
    // HTTP handlers
    // =========================
    private static final class TextHandler implements HttpHandler {
        private final byte[] body;
        TextHandler(String s) { this.body = s.getBytes(StandardCharsets.UTF_8); }
        @Override public void handle(HttpExchange ex) throws IOException {
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }

    private static final class JsonHandler implements HttpHandler {
        private final Responder responder;
        JsonHandler(Responder responder) { this.responder = responder; }
        @Override public void handle(HttpExchange ex) throws IOException {
            Response r;
            try {
                r = responder.respond(ex);
            } catch (Throwable t) {
                r = jsonErr(500, "internal: " + t.getClass().getSimpleName());
            }
            byte[] out = r.body.getBytes(StandardCharsets.UTF_8);
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", "application/json; charset=utf-8");
            h.set("Cache-Control", "no-store");
            h.set("X-BT-Build", BUILD_TAG);
            ex.sendResponseHeaders(r.code, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        }
    }

    @FunctionalInterface
    private interface Responder { Response respond(HttpExchange ex) throws Exception; }

    private static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }

    private static Response jsonOk(Object o) {
        return new Response(200, toJson(o));
    }

    private static Response jsonErr(int code, String msg) {
        return new Response(code, toJson(mapOf("ok", false, "error", msg)));
    }

    // =========================
    // JSON (tiny)
    // =========================
    private static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Boolean) { sb.append(((Boolean) o) ? "true" : "false"); return; }
        if (o instanceof Number) { sb.append(o.toString()); return; }
        if (o instanceof String) { sb.append('"').append(escape((String) o)).append('"'); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                writeJson(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object x : (Iterable<?>) o) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, x);
            }
            sb.append(']');
            return;
        }
        // fallback: stringify
        sb.append('"').append(escape(String.valueOf(o))).append('"');
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // =========================
    // Utilities
    // =========================
    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String a : args) {
            if (!a.startsWith("--")) continue;
            String t = a.substring(2);
            int i = t.indexOf('=');
            if (i < 0) m.put(t, "1");
            else m.put(t.substring(0, i), t.substring(i + 1));
        }
        return m;
    }

    private static int parseInt(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }

    private static void println(String s) {
        System.out.println(s);
    }

    private static String iso(long epochSeconds) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("UTC")).format(Instant.ofEpochSecond(epochSeconds));
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SecureRandom seededRng(byte[] seed) {
        try {
            SecureRandom r = SecureRandom.getInstance("SHA1PRNG");
            r.setSeed(seed);
            // warm it up slightly
            byte[] tmp = new byte[33];
            r.nextBytes(tmp);
            r.nextBytes(tmp);
            return r;
        } catch (Exception e) {
            SecureRandom r = new SecureRandom(seed);
            r.nextBytes(new byte[17]);
            return r;
        }
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static byte[] longToBytes(long x) {
        ByteBuffer b = ByteBuffer.allocate(8);
        b.putLong(x);
        return b.array();
    }

    private static byte[] intToBytes(int x) {
        ByteBuffer b = ByteBuffer.allocate(4);
        b.putInt(x);
        return b.array();
    }

    private static byte[] bigToBytes(BigInteger x) {
        byte[] a = x.toByteArray();
        // normalize: avoid sign-extension variability
        if (a.length > 1 && a[0] == 0) return Arrays.copyOfRange(a, 1, a.length);
        return a;
    }

    private static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        final char[] H = "0123456789abcdef".toCharArray();
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            out[i * 2] = H[v >>> 4];
            out[i * 2 + 1] = H[v & 0x0f];
        }
        return new String(out);
    }

    private static String strip0x(String s) {
        if (s == null) return "";
        if (s.startsWith("0x") || s.startsWith("0X")) return s.substring(2);
        return s;
    }

    private static byte[] hexToBytes(String s) {
        String t = s.trim();
        if ((t.length() & 1) == 1) t = "0" + t;
        int n = t.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = Character.digit(t.charAt(i * 2), 16);
            int lo = Character.digit(t.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static double x96ToDouble(BigInteger x96) {
        // interpret as unsigned fixed point (value = x96 / 2^96)
        BigDecimal a = new BigDecimal(x96);
        BigDecimal denom = new BigDecimal(BigInteger.ONE.shiftLeft(96));
        return a.divide(denom, 18, BigDecimal.ROUND_HALF_UP).doubleValue();
    }

    private static double squeeze(long x, double center, double scale) {
        if (scale == 0) return 5000.0;
        double dx = ((double) (x >>> 1)) - center;
        double y = (dx * 5000.0) / scale;
        y = Math.max(-5000.0, Math.min(5000.0, y));
        return 5000.0 + y;
    }

    private static List<String> defaultFeeders() {
        // deliberately mixed-case, 40 hex chars each (address-like)
        return Arrays.asList(
                "0x1aB2cD3eF4a5678901bCdEf2345678901AbCdEf2",
                "0x2bC3dE4fA5b6789012cDeF3456789012BcDeF345",
                "0x3Cd4eF5aB6c7890123dEf4567890123CdEf45678",
                "0x4dE5fA6bC7d8901234eF5678901234dE5fA6bC7d",
                "0x5eF6aB7cD8e9012345fA6789012345eF6aB7cD8e",
                "0x6aB7cD8eF90123456aB7cD8eF90123456Ab7cD8e",
                "0x7bC8dE9fA01234567bC8dE9fA01234567Bc8dE9f",
                "0x8cD9eF0aB12345678cD9eF0aB12345678Cd9eF0aB",
                "0x9eF0aB1cD23456789eF0aB1cD23456789Ef0aB1cD"
        );
    }

    @SafeVarargs
    private static <K, V> Map<K, V> mapOf(Object... kv) {
        Map<K, V> m = new LinkedHashMap<>();
        if ((kv.length & 1) == 1) throw new IllegalArgumentException("odd kv");
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked") K k = (K) kv[i];
            @SuppressWarnings("unchecked") V v = (V) kv[i + 1];
            m.put(k, v);
        }
        return m;
    }
}
