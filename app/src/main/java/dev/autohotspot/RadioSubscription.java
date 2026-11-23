package dev.autohotspot;

import android.telephony.SubscriptionManager;

/** Both the ordinary signal listener and ROOT radio observer follow the actual active data SIM. */
final class RadioSubscription {
    static int active() {
        try { return (Integer) SubscriptionManager.class.getMethod("getActiveDataSubscriptionId").invoke(null); }
        catch (NoSuchMethodException older) { return SubscriptionManager.getDefaultDataSubscriptionId(); }
        catch (ReflectiveOperationException unavailable) { return SubscriptionManager.INVALID_SUBSCRIPTION_ID; }
    }
}
