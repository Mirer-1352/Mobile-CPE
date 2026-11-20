package dev.autohotspot;

import java.io.IOException;
import org.json.*;

/** A platform rejection is structured data, independent of transport failure. */
final class TetheringFailure extends IOException {
    final int code;
    final String name;
    TetheringFailure(int code, String name) {
        super("系统共享请求失败：" + name + " (" + code + ")");
        this.code = code;
        this.name = name;
    }
    JSONObject appendTo(JSONObject reply) throws JSONException {
        return reply.put("errorCode", code).put("errorName", name);
    }
}
