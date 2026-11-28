package org.json;

/** test stand-in: keeps an object's text as it came (enough for what the client passes through) */
public class JSONObject {
    private String text;

    public JSONObject() {
        text = "{}";
    }

    public JSONObject(String s) throws JSONException {
        if (!s.trim().startsWith("{") || !s.trim().endsWith("}")) throw new JSONException("not an object: " + s);
        text = s.trim();
    }

    public JSONObject put(String k, Object v) throws JSONException {
        String pair = "\"" + k + "\":\"" + v + "\"";
        text = "{}".equals(text) ? "{" + pair + "}" : text.substring(0, text.length() - 1) + "," + pair + "}";
        return this;
    }

    public String optString(String k) {
        int i = text.indexOf("\"" + k + "\":\"");
        if (i < 0) return "";
        int s = i + k.length() + 4;
        return text.substring(s, text.indexOf('"', s));
    }

    @Override
    public String toString() {
        return text;
    }
}
