package dev.autohotspot;

import android.os.*;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;

/** Native per-subscription callbacks. No dumps of cell identities, phone numbers or APNs. */
final class RootRadioObserver implements AutoCloseable {
    private final Object registry;
    private final Method registration;
    private final int sub;
    private final Reply callback;
    private final Object listener;
    private final int physicalEvent, preciseEvent;
    private JSONArray channels = new JSONArray();
    private boolean known;
    private boolean received;
    private String apn = "";
    private int connection = -1;
    private long apnRevision;

    RootRadioObserver(int sub) throws Exception {
        this.sub = sub;
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "telephony.registry");
        Class<?> api = Class.forName("com.android.internal.telephony.ITelephonyRegistry");
        registry = Class.forName(api.getName() + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (registry == null) throw new IllegalStateException("No telephony registry");
        Class<?> type = Class.forName("com.android.internal.telephony.IPhoneStateListener");
        callback = new Reply(type); listener = callback.listener;
        Method selected = null;
        for (Method method : api.getMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().equals("listenWithEventList") && Arrays.equals(p, new Class<?>[]{boolean.class, boolean.class, int.class, String.class, String.class, type, int[].class, boolean.class})) selected = method;
        }
        if (selected == null) for (Method method : api.getMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().equals("listenForSubscriber") && (Arrays.equals(p, new Class<?>[]{int.class, String.class, String.class, type, int.class, boolean.class}) ||
                Arrays.equals(p, new Class<?>[]{int.class, String.class, type, int.class, boolean.class}))) selected = method;
        }
        if (selected == null) throw new NoSuchMethodException("Unsupported telephony listener ABI");
        registration = selected;
        Class<?> constants = Class.forName(selected.getName().equals("listenWithEventList") ? "android.telephony.TelephonyCallback" : "android.telephony.PhoneStateListener");
        physicalEvent = AndroidPlatform.field(constants, selected.getName().equals("listenWithEventList") ? "EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED" : "LISTEN_PHYSICAL_CHANNEL_CONFIGURATION").getInt(null);
        int precise = 0;
        if (callback.precise >= 0) try {
            precise = AndroidPlatform.field(constants, selected.getName().equals("listenWithEventList") ? "EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED" : "LISTEN_PRECISE_DATA_CONNECTION_STATE").getInt(null);
        } catch (ReflectiveOperationException optional) { }
        preciseEvent = precise;
        try { register(true, true); }
        catch (Exception deniedPrecise) {
            // Precise data is optional; a ROM rejecting it must not hide physical-channel observations.
            try { register(false, false); } catch (Exception ignored) { }
            try { register(true, false); }
            catch (Exception failure) { close(); throw failure; }
        }
    }

    private void register(boolean on, boolean precise) throws Exception {
        if (registration.getName().equals("listenWithEventList")) {
            int[] events = !on ? new int[0] : precise && preciseEvent != 0 ? new int[]{physicalEvent, preciseEvent} : new int[]{physicalEvent};
            registration.invoke(registry, false, false, sub, "android", null, listener, events, on);
        } else {
            int mask = on ? physicalEvent | (precise ? preciseEvent : 0) : 0;
            if (registration.getParameterCount() == 6) registration.invoke(registry, sub, "android", null, listener, mask, on);
            else registration.invoke(registry, sub, "android", listener, mask, on);
        }
    }
    synchronized JSONObject state() throws Exception {
        return new JSONObject().put("channelsKnown", known).put("channels", new JSONArray(channels.toString()))
            .put("channelStatus", known ? channels.length() == 0 ? "NO_CHANNELS" : "OK" : received ? "UNAVAILABLE" : "PENDING");
    }
    synchronized String apn() { return apn; }
    synchronized long revision() { return apnRevision; }

    private synchronized void physical(List<?> values) {
        received = true;
        if (values.size() > 64) { known = false; channels = new JSONArray(); return; }
        JSONArray result = new JSONArray();
        try {
            for (Object value : values) {
                int role = integer(value, "getConnectionStatus");
                if (role != 1 && role != 2) continue;
                int down = integer(value, "getCellBandwidthDownlinkKhz");
                if (down < 0) down = integer(value, "getCellBandwidthDownlink");
                result.put(new JSONObject().put("role", role).put("rat", integer(value, "getNetworkType"))
                    .put("band", integer(value, "getBand")).put("channel", integer(value, "getDownlinkChannelNumber"))
                    .put("downKhz", down).put("upKhz", integer(value, "getCellBandwidthUplinkKhz")));
            }
            channels = result; known = values.isEmpty() || result.length() > 0;
        } catch (Exception ignored) { known = false; channels = new JSONArray(); }
    }
    private synchronized void precise(Object value) {
        try {
            String selected;
            int state = integer(value, "getState");
            if (state < 0) state = integer(value, "getDataConnectionState");
            int id = integer(value, "getId");
            if (integer(value, "getTransportType") == 2) return; // IWLAN is not a cellular PDP context.
            try {
                Object setting = value.getClass().getMethod("getApnSetting").invoke(value);
                if (setting == null) {
                    if (id >= 0 && id == connection && state != 2 && state != 3) {
                        apn = ""; connection = -1; apnRevision++;
                    }
                    return;
                }
                int defaultType = AndroidPlatform.field(setting.getClass(), "TYPE_DEFAULT").getInt(null);
                defaultType = Integer.lowestOneBit(defaultType);
                int mask = integer(setting, "getApnTypeBitmask");
                if (defaultType == 0 || mask < 0 || (mask & defaultType) != defaultType) return;
                selected = (String) setting.getClass().getMethod("getApnName").invoke(setting);
            } catch (NoSuchMethodException legacy) {
                String types = String.valueOf(value.getClass().getMethod("getDataConnectionAPNType").invoke(value));
                if (!Arrays.asList(types.toLowerCase(Locale.ROOT).split(",")).contains("default")) return;
                selected = (String) value.getClass().getMethod("getDataConnectionAPN").invoke(value);
            }
            selected = (state == 2 || state == 3) && selected != null ? selected : "";
            if (!selected.equalsIgnoreCase(apn) || id >= 0 && id != connection) {
                apn = selected; connection = selected.isEmpty() ? -1 : id; apnRevision++;
            }
        } catch (Exception ignored) { if (!apn.isEmpty()) { apn = ""; connection = -1; apnRevision++; } }
    }
    static int integer(Object value, String name) {
        try { return (Integer) value.getClass().getMethod(name).invoke(value); }
        catch (Exception ignored) { return -1; }
    }
    @Override public void close() { try { register(false, false); } catch (Exception ignored) { } }

    private final class Reply extends Binder {
        final Object listener;
        final int physical, precise;
        final String descriptor;
        final Parcelable.Creator<?> channelsCreator, preciseCreator;
        Reply(Class<?> type) throws Exception {
            descriptor = type.getName(); Class<?> stub = Class.forName(descriptor + "$Stub");
            String methodName = null;
            for (Method method : type.getMethods()) if ((method.getName().equals("onPhysicalChannelConfigChanged") || method.getName().equals("onPhysicalChannelConfigurationChanged")) &&
                Arrays.equals(method.getParameterTypes(), new Class<?>[]{List.class})) methodName = method.getName();
            if (methodName == null) throw new NoSuchMethodException("No physical channel callback");
            physical = AndroidPlatform.field(stub, "TRANSACTION_" + methodName).getInt(null);
            int transaction = -1; Parcelable.Creator<?> creator = null;
            try {
                transaction = AndroidPlatform.field(stub, "TRANSACTION_onPreciseDataConnectionStateChanged").getInt(null);
                creator = (Parcelable.Creator<?>) Class.forName("android.telephony.PreciseDataConnectionState").getField("CREATOR").get(null);
            } catch (ReflectiveOperationException ignored) { }
            precise = transaction; preciseCreator = creator;
            channelsCreator = (Parcelable.Creator<?>) Class.forName("android.telephony.PhysicalChannelConfig").getField("CREATOR").get(null);
            listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
                if (method.getName().equals("asBinder")) return this;
                if (method.getName().startsWith("onPhysicalChannel")) { physical((List<?>) args[0]); return null; }
                if (method.getName().equals("onPreciseDataConnectionStateChanged")) { precise(args[0]); return null; }
                return TetheringBackend.objectMethod(self, method, args);
            });
            attachInterface((IInterface) listener, descriptor);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != physical && code != precise) return super.onTransact(code, data, reply, flags);
            try {
                data.enforceInterface(descriptor);
                if (code == physical) {
                    List<?> values = data.createTypedArrayList(channelsCreator);
                    physical(values == null ? Collections.emptyList() : values);
                } else if (preciseCreator != null && data.readInt() != 0) precise(preciseCreator.createFromParcel(data));
                if (reply != null) reply.writeNoException();
            } catch (RuntimeException ignored) { }
            return true;
        }
    }
}
