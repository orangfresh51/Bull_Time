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
