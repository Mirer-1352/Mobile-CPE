package dev.autohotspot;

import android.content.Context;
import android.os.SystemClock;
import android.telephony.*;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Optional radio observations use their own ROOT session; modem I/O never blocks tethering. */
final class RootCellularBackend implements AutoCloseable {
    private final Context context;
    private final ExecutorService modem = Executors.newSingleThreadExecutor();
    private RootRadioObserver observer;
    private int sub = -1, slot = -1;
    private String binding = "", channelError = "PENDING", ambrStatus = "PENDING";
    private long epoch, nextObserver, nextAmbr, apnRevision = -1;
    private boolean pending, closed;
    private JSONObject ambr;

    RootCellularBackend(Context context) throws Exception {
        AndroidPlatform.initializeTelephony();
        this.context = context;
    }
    synchronized JSONObject snapshot() throws Exception {
        long now = SystemClock.elapsedRealtime();
        try {
            int selected = RadioSubscription.active();
            TelephonyManager phone = context.getSystemService(TelephonyManager.class);
            if (phone == null || selected < 0) { reset(-1); return unavailable("NO_SIM"); }
            phone = phone.createForSubscriptionId(selected);
            if (phone.getSimState() != TelephonyManager.SIM_STATE_READY) { reset(-1); return unavailable("NO_SIM"); }
            if (sub != selected) reset(selected);
            try { slot = (Integer) SubscriptionManager.class.getMethod("getSlotIndex", int.class).invoke(null, sub); }
            catch (ReflectiveOperationException unsupportedSlot) { slot = -1; }
            int dataRat = RootRadioObserver.integer(phone, "getDataNetworkType");
            String current = sub + ":" + slot + ":" + phone.getNetworkOperator() + ":" + dataRat;
            if (!current.equals(binding)) { binding = current; epoch++; ambr = null; nextAmbr = 0; ambrStatus = "PENDING"; }
            if (observer == null && now >= nextObserver) {
                nextObserver = now + 300_000;
                try { observer = new RootRadioObserver(sub); channelError = "PENDING"; }
                catch (Exception | LinkageError unsupported) { channelError = "UNSUPPORTED"; }
            }
            JSONObject state = observer == null ? unavailable(channelError) : observer.state();
            if (dataRat == TelephonyManager.NETWORK_TYPE_UNKNOWN) state.put("channelsKnown", false)
                .put("channels", new org.json.JSONArray()).put("channelStatus", "NO_CHANNELS");
            if (observer != null && observer.revision() != apnRevision) {
                apnRevision = observer.revision(); epoch++; ambr = null; nextAmbr = 0; ambrStatus = "PENDING";
            }
            String apn = observer == null || dataRat == TelephonyManager.NETWORK_TYPE_UNKNOWN ? "" : observer.apn();
            if (apn.isEmpty()) { ambr = null; ambrStatus = "NO_CONTEXT"; }
            else if (!pending && now >= nextAmbr) {
                pending = true;
                final long token = epoch; final int selectedSlot = slot, selectedSub = sub;
                final long revision = apnRevision;
                modem.submit(() -> query(token, revision, selectedSlot, selectedSub, apn));
            }
            if (ambr != null && now - ambr.optLong("observed") > 90_000) { ambr = null; ambrStatus = "STALE"; }
            return state.put("subscription", sub).put("slot", slot).put("ambrStatus", ambrStatus)
                .put("ambr", ambr == null ? JSONObject.NULL : new JSONObject(ambr.toString()));
        } catch (Exception | LinkageError unavailable) {
            reset(-1); return unavailable("UNAVAILABLE");
        }
    }
    private void query(long token, long revision, int slot, int subscription, String apn) {
        JSONObject result = null; String status;
        try { result = ModemAmbrReader.read(slot, apn); status = "OK"; }
        catch (AmbrProtocol.Unavailable unavailable) { status = unavailable.status; }
        catch (Exception | LinkageError unavailable) { status = "UNAVAILABLE"; }
        synchronized (this) {
            pending = false;
            if (closed || token != epoch || observer == null || revision != observer.revision() || !apn.equalsIgnoreCase(observer.apn())) return;
            ambrStatus = status; ambr = result;
            nextAmbr = SystemClock.elapsedRealtime() + (result != null ? 60_000 :
                status.equals("NO_CONTEXT") ? 30_000 : status.equals("BUSY") || status.equals("NO_AMBR") ? 60_000 : 300_000);
            if (result != null) try { result.put("observed", SystemClock.elapsedRealtime()).put("subscription", subscription); }
            catch (Exception invalid) { ambr = null; ambrStatus = "UNAVAILABLE"; }
        }
    }
    private JSONObject unavailable(String status) throws Exception {
        return new JSONObject().put("subscription", sub).put("slot", slot).put("channelsKnown", false)
            .put("channelStatus", status).put("ambrStatus", status);
    }
    private void reset(int nextSub) {
        if (observer != null) observer.close();
        observer = null; sub = nextSub; slot = -1; epoch++; ambr = null; binding = ""; apnRevision = -1;
        nextObserver = nextAmbr = 0; channelError = ambrStatus = "PENDING";
    }
    @Override public synchronized void close() { closed = true; reset(-1); modem.shutdownNow(); }
}
