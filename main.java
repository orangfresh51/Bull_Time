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
