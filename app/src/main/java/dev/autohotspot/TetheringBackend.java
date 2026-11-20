package dev.autohotspot;

import android.content.Context;
import android.net.*;
import android.os.*;
import org.json.*;
import java.lang.reflect.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.Executor;

/** Android/Mainline adaptation boundary. Callback state never writes unsolicited IPC replies. */
final class TetheringBackend implements AutoCloseable {
    final Object manager;
    private final Context context;
    private final Executor executor;
    private final TetheringObservation state = new TetheringObservation();
    private final Map<Integer, String> errorNames = new HashMap<>();
    private Object callback;
    private volatile String observerError;
    private final JSONObject platform;
    private volatile Network selectedUpstream;

    @android.annotation.SuppressLint("InlinedApi") // Hidden service name exists on API 30+.
    TetheringBackend(Context context, Executor executor) throws Exception {
        this.context = context;
        this.executor = executor;
        manager = context.getSystemService(Build.VERSION.SDK_INT >= 30 ? Context.TETHERING_SERVICE : Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new UnsupportedOperationException("此 ROM 未提供共享管理服务");
        platform = platform(context);
        for (Field field : manager.getClass().getFields()) {
            if (!field.getName().startsWith("TETHER_ERROR_") || field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) continue;
            try { errorNames.put(field.getInt(null), field.getName()); } catch (Exception ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 30) observe();
    }

    String errorName(int code) { return errorNames.getOrDefault(code, "TETHER_ERROR_" + code); }
    int startType(int logical) {
        return logical == 1 && Boolean.FALSE.equals(state.supports(1)) && Boolean.TRUE.equals(state.supports(4)) ? 4 : logical;
    }
    boolean hasNcmActive() throws Exception {
        JSONObject current = snapshot(), types = current.getJSONObject("interfaceTypes");
        JSONArray names = current.getJSONArray("active");
        for (int i = 0; i < names.length(); i++) {
            String name = names.getString(i);
            if (types.optInt(name, -1) == 4 || !types.has(name) && name.matches("ncm[0-9]+")) return true;
        }
        return false;
    }

    private void observe() {
        try {
            Class<?> type = Class.forName("android.net.TetheringManager$TetheringEventCallback");
            Object registered = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                Object base = objectMethod(proxy, method, args);
                if (method.getDeclaringClass() == Object.class) return base;
                try { event(method.getName(), args == null ? new Object[0] : args); }
                catch (Exception error) { observerError = error.toString(); }
                return null;
            });
            manager.getClass().getMethod("registerTetheringEventCallback", Executor.class, type).invoke(manager, executor, registered);
            callback = registered;
        } catch (Exception error) { observerError = rootCause(error).toString(); }
    }

    private void event(String name, Object[] args) throws Exception {
        if (args.length == 0) return;
        switch (name) {
            case "onTetheringSupported": state.overall((Boolean) args[0]); break;
            case "onSupportedTetheringTypes":
                Set<Integer> types = new LinkedHashSet<>();
                if (args[0] instanceof Number) {
                    long mask = ((Number) args[0]).longValue();
                    for (int i = 0; i < 63; i++) if ((mask & (1L << i)) != 0) types.add(i);
                } else for (Object value : values(args[0])) if (value instanceof Number) types.add(((Number) value).intValue());
                state.supported(types); break;
            case "onTetheredInterfacesChanged": state.interfaces("active", interfaces(args[0])); break;
            case "onTetherableInterfacesChanged": state.interfaces("available", interfaces(args[0])); break;
            case "onLocalOnlyInterfacesChanged": state.interfaces("local", interfaces(args[0])); break;
            case "onUpstreamChanged":
                selectedUpstream = (Network) args[0];
                state.upstream(upstream(selectedUpstream)); break;
            case "onOffloadStatusChanged": state.offload((Integer) args[0]); break;
            case "onError":
                Object iface = args[0];
                int code = (Integer) args[1];
                state.error(interfaceName(iface), interfaceType(iface), code, errorName(code)); break;
        }
    }

    private JSONObject upstream(Network network) throws Exception {
        if (network == null) return null;
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        LinkProperties links = connectivity.getLinkProperties(network);
        boolean ipv4 = false, ipv6 = false;
        if (links != null) for (RouteInfo route : links.getRoutes()) if (route.isDefaultRoute()) {
            ipv4 |= route.getDestination().getAddress() instanceof java.net.Inet4Address;
            ipv6 |= route.getDestination().getAddress() instanceof java.net.Inet6Address;
        }
        return new JSONObject().put("network", network.toString()).put("interface", links == null ? JSONObject.NULL : links.getInterfaceName())
            .put("vpn", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            .put("cellular", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            .put("wifi", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            .put("validated", caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .put("ipv4Default", ipv4).put("ipv6Default", ipv6)
            .put("dnsServerCount", links == null ? 0 : links.getDnsServers().size())
            .put("mtu", links == null || Build.VERSION.SDK_INT < 29 ? 0 : links.getMtu());
    }

    JSONObject snapshot() throws Exception {
        String[] legacy = new String[0];
        try { legacy = (String[]) manager.getClass().getMethod("getTetheredIfaces").invoke(manager); }
        catch (Exception error) {
            if (!state.hasActive()) throw error;
            observerError = rootCause(error).toString();
        }
        JSONObject result = state.snapshot(legacy);
        // Refresh read-only link properties even if Android keeps the same upstream Network.
        try {
            Network network = selectedUpstream;
            JSONObject recorded = result.optJSONObject("upstream");
            if (network != null && recorded != null && network.toString().equals(recorded.optString("network")))
                result.put("upstream", upstream(network));
            JSONArray vpns = new JSONArray();
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            for (Network candidate : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpns.put(upstream(candidate));
            }
            result.put("vpnCandidates", vpns);
        } catch (Exception error) { result.put("networkObservationError", rootCause(error).toString()); }
        if (Build.VERSION.SDK_INT < 30) result.put("supportedTypes", new JSONArray(Arrays.asList(0, 1)));
        return result.put("observerRegistered", callback != null).put("observerError", observerError == null ? JSONObject.NULL : observerError)
            .put("ethernetInterfaces", ethernetInterfaces()).put("backend", manager.getClass().getName())
            .put("platform", platform);
    }

    @SuppressWarnings("deprecation")
    private static JSONObject platform(Context context) throws Exception {
        JSONObject result = new JSONObject().put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
            .put("opPackage", Build.VERSION.SDK_INT >= 29 ? context.getOpPackageName() : context.getPackageName());
        if (Build.VERSION.SDK_INT >= 29) {
            JSONObject modules = new JSONObject();
            for (String name : new String[]{"com.android.tethering", "com.google.android.tethering", "com.android.networkstack.tethering", "com.google.android.networkstack.tethering"}) {
                try { modules.put(name, context.getPackageManager().getPackageInfo(name, android.content.pm.PackageManager.MATCH_APEX).getLongVersionCode()); }
                catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
            }
            result.put("modules", modules);
        }
        try { result.put("selinuxContext", new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/attr/current"))).trim()); }
        catch (Exception ignored) { }
        return result;
    }

    @android.annotation.SuppressLint("WrongConstant") // Ethernet is a system service on older API levels.
    private JSONArray ethernetInterfaces() {
        Object ethernet = context.getSystemService("ethernet");
        if (ethernet != null) for (String method : new String[]{"getInterfaceList", "getAvailableInterfaces"}) {
            try { return new JSONArray(values(ethernet.getClass().getMethod(method).invoke(ethernet))); }
            catch (Exception ignored) { }
        }
        return new JSONArray();
    }

    static List<?> values(Object value) {
        if (value instanceof Collection) return new ArrayList<>((Collection<?>) value);
        List<Object> result = new ArrayList<>();
        if (value != null && value.getClass().isArray()) for (int i = 0; i < Array.getLength(value); i++) result.add(Array.get(value, i));
        return result;
    }
    private static Map<String, Integer> interfaces(Object value) throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Object iface : values(value)) result.put(interfaceName(iface), interfaceType(iface));
        return result;
    }
    private static String interfaceName(Object iface) throws Exception {
        return iface instanceof String ? (String) iface : (String) iface.getClass().getMethod("getInterface").invoke(iface);
    }
    private static int interfaceType(Object iface) throws Exception {
        return iface instanceof String ? -1 : (Integer) iface.getClass().getMethod("getType").invoke(iface);
    }
    static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return args != null && args.length == 1 && proxy == args[0];
            case "toString": return "MobileCpeCallback";
            default: return null;
        }
    }
    private static Throwable rootCause(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error; }

    @Override public void close() {
        if (callback == null) return;
        try { manager.getClass().getMethod("unregisterTetheringEventCallback", callback.getClass().getInterfaces()[0]).invoke(manager, callback); }
        catch (Exception ignored) { }
        callback = null;
    }
}
