package dev.autohotspot;

import android.content.Context;
import android.hardware.usb.UsbManager;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.util.*;

/** Read USB data roles without changing roles or gadget functions. Power/charging is unrelated. */
final class UsbState {
    final boolean host;
    final Boolean device;
    final boolean gadgetConnected;

    private UsbState(boolean host, Boolean device, boolean gadgetConnected) {
        this.host = host;
        this.device = device;
        this.gadgetConnected = gadgetConnected;
    }

    static UsbState read(Context context) {
        UsbManager usb = context.getSystemService(UsbManager.class);
        boolean host = false, device = false, known = false;
        try {
            Object ports = usb.getClass().getMethod("getPorts").invoke(usb);
            List<?> values;
            if (ports instanceof List) values = (List<?>) ports;
            else {
                List<Object> array = new ArrayList<>(); // Android 9 returned an array.
                for (int i = 0; i < Array.getLength(ports); i++) array.add(Array.get(ports, i));
                values = array;
            }
            for (Object port : values) {
                Object status = port.getClass().getMethod("getStatus").invoke(port);
                if (status == null) continue;
                int role = (Integer) status.getClass().getMethod("getCurrentDataRole").invoke(status);
                known = true;
                host |= role == 1;
                device |= role == 2;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        // Also covers older/custom USB stacks without a usable Type-C port API.
        try { host |= !usb.getDeviceList().isEmpty(); } catch (RuntimeException ignored) { }
        boolean gadget = false;
        try {
            String state = new String(Files.readAllBytes(new File("/sys/class/android_usb/android0/state").toPath())).trim();
            gadget = state.equals("CONNECTED") || state.equals("CONFIGURED");
        } catch (Exception ignored) { }
        return new UsbState(host, known ? device : null, gadget);
    }

    boolean hostOnly() { return host && !Boolean.TRUE.equals(device); }

    JSONObject appendTo(JSONObject value) throws Exception {
        return value.put("usbHost", host).put("usbDevice", device == null ? JSONObject.NULL : device)
            .put("usbConnected", gadgetConnected).put("usbHardware", hardware());
    }

    /** Device IDs and driver bindings only; never read serials or user-visible USB strings. */
    private static JSONObject hardware() throws Exception {
        JSONObject result = new JSONObject();
        JSONArray devices = new JSONArray(), drivers = new JSONArray();
        try {
            File[] nodes = new File("/sys/bus/usb/devices").listFiles();
            if (nodes == null) throw new java.io.IOException("USB sysfs is unavailable");
            Arrays.sort(nodes, Comparator.comparing(File::getName));
            for (File node : nodes) {
                String port = node.getName();
                if (!port.matches("[0-9]+-[0-9]+(?:\\.[0-9]+)*(?::[0-9]+\\.[0-9]+)?")) continue;
                File vendor = new File(node, "idVendor");
                if (vendor.isFile()) devices.put(new JSONObject().put("port", port)
                    .put("vendor", attribute(vendor)).put("product", attribute(new File(node, "idProduct")))
                    .put("class", attribute(new File(node, "bDeviceClass"))));
                File driver = new File(node, "driver");
                if (driver.exists()) drivers.put(new JSONObject().put("port", port).put("driver", driver.getCanonicalFile().getName()));
            }
        } catch (Exception error) { result.put("error", error.toString()); }
        return result.put("devices", devices).put("drivers", drivers);
    }

    private static String attribute(File file) throws Exception {
        String value = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim();
        return value.matches("[a-fA-F0-9]{2,4}") ? value : "unknown";
    }
}
