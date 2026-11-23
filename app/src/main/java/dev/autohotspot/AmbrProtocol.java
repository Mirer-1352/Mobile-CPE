package dev.autohotspot;

import java.util.*;

/** 3GPP TS 27.007: preserve empty CSV fields; MBR/GBR must never become AMBR. */
final class AmbrProtocol {
    interface Query { String query(String command) throws Exception; }
    static class Unavailable extends Exception {
        final String status;
        Unavailable(String status) { super(status); this.status = status; }
    }
    static final class Limit {
        final String kind; final long downKbps, upKbps;
        Limit(String kind, long down, long up) { this.kind = kind; downKbps = down; upKbps = up; }
    }
    static Limit read(int slot, String internetApn, Query channel) throws Exception {
        if (slot < 0 || slot > 1) throw new Unavailable("SIM_UNCONFIRMED");
        if (internetApn == null || internetApn.isEmpty()) throw new Unavailable("NO_CONTEXT");
        channel.query("AT");
        List<List<String>> stack = rows(channel.query("AT+ESUO?"), "+ESUO:");
        if (stack.size() != 1 || stack.get(0).isEmpty() || number(stack.get(0).get(0)) != slot + 4)
            throw new Unavailable("SIM_UNCONFIRMED");
        List<List<String>> operator = rows(channel.query("AT+COPS?"), "+COPS:");
        if (operator.size() != 1 || operator.get(0).size() < 4) throw new Unavailable("UNAVAILABLE");
        int access = number(operator.get(0).get(3));
        if (access != 7 && access != 11 && access != 13) throw new Unavailable("UNSUPPORTED");
        boolean nr = access == 11;
        String active = channel.query("AT+CGACT?");
        int cid = context(channel.query("AT+CGDCONT?"), active, internetApn);
        if (cid < 0) throw new Unavailable(cid == -2 ? "AMBIGUOUS" : "NO_CONTEXT");
        long[] rates = limit(channel.query((nr ? "AT+C5GQOSRDP=" : "AT+CGEQOSRDP=") + cid), cid, nr);
        if (rates == null) throw new Unavailable("NO_AMBR");
        return new Limit(nr ? "Session-AMBR" : "APN-AMBR", rates[0], rates[1]);
    }
    static List<String> csv(String row) {
        List<String> result = new ArrayList<>(); StringBuilder value = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) { result.add(value.toString().trim()); value.setLength(0); }
            else value.append(ch);
        }
        if (quoted) return Collections.emptyList();
        result.add(value.toString().trim()); return result;
    }
    static List<List<String>> rows(String response, String prefix) {
        List<List<String>> result = new ArrayList<>();
        for (String line : response.split("[\\r\\n]+")) if (line.trim().startsWith(prefix)) {
            List<String> row = csv(line.trim().substring(prefix.length()));
            if (!row.isEmpty()) result.add(row);
        }
        return result;
    }
    static int number(String value) { try { return Integer.parseInt(value); } catch (Exception ignored) { return -1; } }
    static int context(String definitions, String active, String internetApn) {
        if (internetApn == null || internetApn.isEmpty()) return -1;
        Set<Integer> live = new HashSet<>();
        for (List<String> row : rows(active, "+CGACT:")) if (row.size() >= 2 && number(row.get(1)) == 1) live.add(number(row.get(0)));
        Set<Integer> candidates = new HashSet<>();
        for (List<String> row : rows(definitions, "+CGDCONT:")) {
            if (row.size() < 3 || !live.contains(number(row.get(0)))) continue;
            String apn = row.get(2).toLowerCase(Locale.ROOT);
            if (!apn.equalsIgnoreCase(internetApn)) continue;
            if (apn.isEmpty() || apn.equals("ims") || apn.startsWith("ims.") || apn.equals("sos") || apn.equals("xcap") || apn.contains("emergency")) continue;
            if (Arrays.asList("IP", "IPV6", "IPV4V6").contains(row.get(1).toUpperCase(Locale.ROOT))) candidates.add(number(row.get(0)));
        }
        if (candidates.size() != 1) return candidates.isEmpty() ? -1 : -2;
        int cid = candidates.iterator().next(); return cid >= 0 && cid <= 255 ? cid : -1;
    }
    static long[] limit(String response, int cid, boolean nr) {
        long[] result = null;
        for (List<String> row : rows(response, nr ? "+C5GQOSRDP:" : "+CGEQOSRDP:")) {
            if (row.size() < 8 || number(row.get(0)) != cid) continue;
            try {
                long down = Long.parseLong(row.get(6)), up = Long.parseLong(row.get(7));
                if (down <= 0 || up <= 0 || down > 1_000_000_000L || up > 1_000_000_000L) return null;
                if (result != null && (result[0] != down || result[1] != up)) return null;
                result = new long[]{down, up};
            } catch (NumberFormatException invalid) { return null; }
        }
        return result;
    }
}
