package dev.autohotspot;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.*;
import android.os.Handler;
import android.os.Parcel;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.*;
import java.util.*;
import io.github.libxposed.api.XposedModule;

/** Selects the actual VPN Network as the native tethering upstream, including native DNS/NAT. */
final class VpnHooks {
    private final XposedModule api;
    private final Set<Member> installed = new HashSet<>();
    private final Map<Object, Method> controllers = Collections.synchronizedMap(new WeakHashMap<>());
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean vpnEnabled = true;
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private Handler monitorHandler;

    VpnHooks(XposedModule api) { this.api = api; }

    synchronized void install(ClassLoader loader) {
        if ((api.getFrameworkProperties() & XposedModule.PROP_CAP_REMOTE) == 0) return;
        if (preferences == null) {
            try {
                preferences = api.getRemotePreferences("router");
                readPreference();
                preferenceListener = (prefs, key) -> { if ("vpnSharing".equals(key)) { readPreference(); refreshUpstream(); } };
                preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            } catch (RuntimeException error) { log("Remote settings unavailable", error); return; }
        }
        for (String name : new String[]{"com.android.networkstack.tethering.UpstreamNetworkMonitor",
                "com.android.server.connectivity.tethering.UpstreamNetworkMonitor"}) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    if (installed.contains(constructor)) continue;
                    api.hook(constructor).intercept(chain -> {
                        Object value = chain.proceed();
                        observe(chain.getThisObject());
                        return value;
                    });
                    installed.add(constructor);
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals("getCurrentPreferredUpstream") &&
                        !method.getName().equals("selectPreferredUpstreamType")) continue;
                    if (method.getReturnType() == void.class || installed.contains(method)) continue;
                    method.setAccessible(true);
                    api.hook(method).intercept(chain -> {
                        Object original = chain.proceed();
                        Object monitor = chain.getThisObject();
                        observe(monitor);
                        if (!enabled()) return original;
                        try {
                            Context context = (Context) AndroidPlatform.field(monitor, "mContext");
                            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
                            for (Network network : cm.getAllNetworks()) {
                                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                                LinkProperties links = cm.getLinkProperties(network);
                                // While a VPN is connecting, withdraw the old direct upstream.
                                if (links == null || links.getInterfaceName() == null) return null;
                                return state(method.getReturnType(), cm, network, links, caps);
                            }
                            if (Settings.Secure.getInt(context.getContentResolver(), "always_on_vpn_lockdown", 0) == 1) return null;
                        } catch (Throwable error) {
                            log("VPN upstream selection failed", error);
                            // An enabled but unsupported adapter must not claim success or force a direct route.
                            return null;
                        }
                        return original;
                    });
                    installed.add(method);
                }
            } catch (ClassNotFoundException ignored) { }
            catch (Throwable error) { log("Upstream adapter: " + name, error); }
        }
        for (String name : new String[]{"com.android.networkstack.tethering.Tethering",
                "com.android.server.connectivity.Tethering", "com.android.server.connectivity.tethering.Tethering"}) {
            try { controllers(Class.forName(name, false, loader), 0); }
            catch (ClassNotFoundException ignored) { }
            catch (Throwable error) { log("Tethering adapter: " + name, error); }
        }
    }

    private void controllers(Class<?> type, int depth) {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals("chooseUpstreamType") || method.getParameterTypes().length != 1 ||
                    method.getParameterTypes()[0] != boolean.class || installed.contains(method)) continue;
            method.setAccessible(true);
            api.hook(method).intercept(chain -> {
                controllers.put(chain.getThisObject(), method);
                return chain.proceed();
            });
            installed.add(method);
        }
        if (depth < 2) for (Class<?> nested : type.getDeclaredClasses()) controllers(nested, depth + 1);
    }

    private boolean enabled() {
        return vpnEnabled;
    }

    private void readPreference() {
        try { vpnEnabled = preferences.getBoolean("vpnSharing", true); }
        catch (RuntimeException error) { log("Retaining the last VPN preference", error); }
    }

    private synchronized void observe(Object monitor) {
        if (monitor == null || networkCallback != null) return;
        try {
            Context context = (Context) AndroidPlatform.field(monitor, "mContext");
            Handler handler;
            try { handler = (Handler) AndroidPlatform.field(monitor, "mHandler"); }
            catch (ReflectiveOperationException oldVersion) {
                Object target = AndroidPlatform.field(monitor, "mTarget");
                handler = handler(target);
            }
            if (handler == null) return;
            monitorHandler = handler;
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { refreshUpstream(); }
                @Override public void onLost(Network network) { refreshUpstream(); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) { refreshUpstream(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { refreshUpstream(); }
            };
            NetworkRequest request = new NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build();
            context.getSystemService(ConnectivityManager.class).registerNetworkCallback(request, callback, handler);
            networkCallback = callback;
        } catch (Throwable error) { log("VPN change observer unavailable", error); }
    }

    private void refreshUpstream() {
        List<Map.Entry<Object, Method>> targets;
        synchronized (controllers) { targets = new ArrayList<>(controllers.entrySet()); }
        for (Map.Entry<Object, Method> entry : targets) {
            Object target = entry.getKey();
            if (target == null) continue;
            Handler handler = handler(target);
            if (handler == null) handler = monitorHandler;
            if (handler != null) handler.post(() -> {
                try { entry.getValue().invoke(target, false); }
                catch (Throwable error) { log("VPN upstream refresh failed", error); }
            });
        }
    }

    private static Handler handler(Object target) {
        try { return (Handler) target.getClass().getMethod("getHandler").invoke(target); }
        catch (ReflectiveOperationException ignored) {
            try { return (Handler) AndroidPlatform.field(target, "mHandler"); }
            catch (ReflectiveOperationException absent) { return null; }
        }
    }

    @SuppressWarnings("deprecation")
    private static Object state(Class<?> type, ConnectivityManager cm, Network network, LinkProperties links,
                                NetworkCapabilities caps) throws ReflectiveOperationException {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] args = constructor.getParameterTypes();
            constructor.setAccessible(true);
            if (args.length == 3 && args[0] == LinkProperties.class && args[1] == NetworkCapabilities.class && args[2] == Network.class)
                return constructor.newInstance(copyLinks(links), new NetworkCapabilities(caps), network);
            if (args.length == 6 && args[0] == NetworkInfo.class && args[1] == LinkProperties.class &&
                    args[2] == NetworkCapabilities.class && args[3] == Network.class)
                return constructor.newInstance(cm.getNetworkInfo(network), copyLinks(links), new NetworkCapabilities(caps), network, null, null);
        }
        throw new NoSuchMethodException("Unsupported upstream state " + type.getName());
    }

    private static LinkProperties copyLinks(LinkProperties source) {
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return LinkProperties.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

    private void log(String message, Throwable error) { api.log(Log.WARN, "MCPE", message, error); }
}
