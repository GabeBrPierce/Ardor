package com.ardor.struct;

/**
 * Litematica's own bit-packing scheme for its BlockStates LongArray -- confirmed against the
 * litemapy reference implementation (github.com/SmylerMC/litemapy, storage.py): entries are
 * packed CONTIGUOUSLY with no long-boundary padding (an entry can straddle two longs), the old
 * pre-1.16 vanilla scheme, NOT the per-long-aligned packing modern structure blocks switched to.
 * Getting that distinction wrong silently misaligns every read after the first spanning entry.
 */
final class LitematicaBitPack {

    private LitematicaBitPack() {}

    static int bitsPerEntry(int paletteSize) {
        int ceilLog2 = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        return Math.max(ceilLog2, 2);
    }

    static long get(long[] array, int bitsPerEntry, long index) {
        long mask = (1L << bitsPerEntry) - 1;
        long startOffset = index * bitsPerEntry;
        int startArrIdx = (int) (startOffset >> 6);
        int endArrIdx = (int) (((index + 1) * bitsPerEntry - 1) >> 6);
        int startBitOffset = (int) (startOffset & 0x3F);

        if (startArrIdx == endArrIdx) {
            return (array[startArrIdx] >>> startBitOffset) & mask;
        }
        int endOffset = 64 - startBitOffset;
        return ((array[startArrIdx] >>> startBitOffset) | (array[endArrIdx] << endOffset)) & mask;
    }
}
