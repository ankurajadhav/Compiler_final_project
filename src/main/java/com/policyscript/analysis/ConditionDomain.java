package com.policyscript.analysis;

import com.policyscript.ast.Condition;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Exact truth-set operations for the finite version-1 request domain (7 x 1,440). */
public final class ConditionDomain {
    public static final int MINUTES_PER_DAY = 1440;
    public static final int DOMAIN_SIZE = DayOfWeek.values().length * MINUTES_PER_DAY;
    private static final Map<String, BitSet> CACHE = new ConcurrentHashMap<>();

    private ConditionDomain() {}

    public static BitSet mask(Condition condition) {
        if (condition == null) {
            BitSet all = new BitSet(DOMAIN_SIZE); all.set(0, DOMAIN_SIZE); return all;
        }
        String key = condition.canonical();
        BitSet cached = CACHE.get(key);
        if (cached != null) return (BitSet) cached.clone();
        BitSet mask = new BitSet(DOMAIN_SIZE);
        DayOfWeek[] days = DayOfWeek.values();
        for (int d = 0; d < days.length; d++) {
            for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
                if (condition.evaluate(days[d], LocalTime.of(minute / 60, minute % 60))) mask.set(d * MINUTES_PER_DAY + minute);
            }
        }
        CACHE.putIfAbsent(key, (BitSet) mask.clone());
        return mask;
    }

    public static int firstIntersection(BitSet left, BitSet right) {
        BitSet intersection = (BitSet) left.clone(); intersection.and(right);
        return intersection.nextSetBit(0);
    }
    public static boolean equivalent(Condition left, Condition right) { return mask(left).equals(mask(right)); }
    public static boolean subset(Condition left, Condition right) {
        BitSet a = mask(left), b = mask(right); a.andNot(b); return a.isEmpty();
    }
    public static int count(Condition condition) { return mask(condition).cardinality(); }
    public static DayOfWeek dayAt(int index) { return DayOfWeek.values()[index / MINUTES_PER_DAY]; }
    public static LocalTime timeAt(int index) {
        int minute = index % MINUTES_PER_DAY; return LocalTime.of(minute / 60, minute % 60);
    }
    public static void clearCache() { CACHE.clear(); }
}
