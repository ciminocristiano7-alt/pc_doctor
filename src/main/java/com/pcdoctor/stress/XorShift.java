package com.pcdoctor.stress;

/** Generatore pseudo-casuale veloce e deterministico: stesso seme, stessa sequenza. */
final class XorShift {

    private long state;

    XorShift(long seed) {
        this.state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
    }

    long next() {
        long x = state;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        state = x;
        return x;
    }
}
