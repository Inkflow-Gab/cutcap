package app.openroll.mobile;

/** Parses an HTTP Range header into an inclusive byte span, clamped to the file. */
final class Range {

    private Range() {}

    /** Null when the header asks for nothing that exists (416). */
    static long[] parse(String header, long total) {
        if (header == null || total <= 0) return null;
        String spec = header.trim();
        if (!spec.startsWith("bytes=")) return null;
        // Only the first range is honoured; media decoders ask for one at a time.
        int comma = spec.indexOf(',', 6);
        spec = (comma > 0 ? spec.substring(6, comma) : spec.substring(6)).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String from = spec.substring(0, dash).trim();
        String to = spec.substring(dash + 1).trim();
        try {
            if (from.isEmpty()) {
                // "bytes=-500": the last 500 bytes.
                long n = Long.parseLong(to);
                if (n <= 0) return null;
                long start = Math.max(0, total - n);
                return new long[] { start, total - 1 };
            }
            long start = Long.parseLong(from);
            if (start >= total) return null;
            long end = to.isEmpty() ? total - 1 : Math.min(Long.parseLong(to), total - 1);
            if (end < start) return null;
            return new long[] { start, end };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
