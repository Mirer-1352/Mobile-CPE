package dev.autohotspot;

import org.json.*;
import java.util.*;

/** One coherent callback snapshot. No Android dependencies, I/O, or user-intent writes. */
final class TetheringObservation {
    private Map<String, Integer> available = new LinkedHashMap<>(), active, localOnly = new LinkedHashMap<>();
    private final Map<String, JSONObject> errors = new LinkedHashMap<>();
    private Set<Integer> supported;
    private Boolean overall;
    private JSONObject upstream;
    private boolean upstreamKnown;
    private Integer offload;
    private long revision;

    synchronized boolean hasActive() { return active != null; }
    synchronized Boolean supports(int type) {
        if (Boolean.FALSE.equals(overall)) return Boolean.FALSE;
        return supported == null ? null : Boolean.valueOf(supported.contains(type));
    }

    synchronized void interfaces(String kind, Map<String, Integer> values) {
        Map<String, Integer> copy = new LinkedHashMap<>(values);
        Map<String, Integer> old = kind.equals("active") ? active : kind.equals("available") ? available : localOnly;
        if (old != null) for (String iface : copy.keySet()) {
            if (copy.get(iface) < 0 && old.containsKey(iface)) copy.put(iface, old.get(iface));
        }
        if (Objects.equals(old, copy)) return;
        if (kind.equals("active")) active = copy;
        else if (kind.equals("available")) available = copy;
        else localOnly = copy;
        revision++;
    }

    synchronized void supported(Set<Integer> types) {
        if (!Objects.equals(supported, types)) { supported = new LinkedHashSet<>(types); revision++; }
    }
    synchronized void overall(boolean value) { if (!Objects.equals(overall, value)) { overall = value; revision++; } }
    synchronized void upstream(JSONObject value) {
        if (!upstreamKnown || !Objects.equals(String.valueOf(upstream), String.valueOf(value))) { upstream = value; revision++; }
        upstreamKnown = true;
    }
    synchronized void offload(int value) { if (!Objects.equals(offload, value)) { offload = value; revision++; } }
    synchronized void error(String iface, int type, int code, String name) throws JSONException {
        if (code == 0) {
            if (errors.remove(iface) != null) revision++;
            return;
        }
        JSONObject value = new JSONObject().put("interface", iface).put("type", type).put("code", code).put("name", name);
        value.put("sequence", ++revision);
        errors.put(iface, value);
        while (errors.size() > 32) errors.remove(errors.keySet().iterator().next());
    }

    synchronized JSONObject snapshot(String[] legacy) throws JSONException {
        Map<String, Integer> types = new LinkedHashMap<>(available);
        merge(types, localOnly);
        if (active != null) merge(types, active);
        JSONObject mapped = new JSONObject();
        for (Map.Entry<String, Integer> entry : types.entrySet()) if (entry.getValue() >= 0) mapped.put(entry.getKey(), entry.getValue());
        Set<Integer> capability = Boolean.FALSE.equals(overall) ? Collections.emptySet() : supported;
        return new JSONObject().put("active", new JSONArray(active == null ? Arrays.asList(legacy) : active.keySet()))
            .put("stateSource", active == null ? "legacy" : "callback").put("revision", revision)
            .put("interfaceTypes", mapped).put("available", new JSONArray(available.keySet()))
            .put("localOnly", new JSONArray(localOnly.keySet()))
            .put("supportedTypes", capability == null ? JSONObject.NULL : new JSONArray(capability))
            .put("tetheringSupported", overall == null ? JSONObject.NULL : overall)
            .put("upstream", upstream == null ? JSONObject.NULL : new JSONObject(upstream.toString()))
            .put("upstreamKnown", upstreamKnown)
            .put("offload", offload == null ? JSONObject.NULL : offload)
            .put("interfaceErrors", new JSONArray(errors.values()));
    }

    private static void merge(Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet())
            if (entry.getValue() >= 0 || !target.containsKey(entry.getKey())) target.put(entry.getKey(), entry.getValue());
    }
}
