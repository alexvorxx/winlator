package com.winlator.core;

import androidx.annotation.NonNull;

import java.util.Iterator;

public class KeyValueSet implements Iterable<String[]> {
    private String data = "";

    public KeyValueSet() {
        this.data = "";
    }

    public KeyValueSet(String data) {
        this.data = data != null && !data.isEmpty() ? data : "";
    }

    private int[] indexOfKey(String key) {
        int start = 0;
        int end = data.indexOf(",");
        if (end == -1) end = data.length();

        while (start < end) {
            int index = data.indexOf("=", start);
            String currKey = data.substring(start, index);
            if (currKey.equals(key)) return new int[]{start, end};
            start = end+1;
            end = data.indexOf(",", start);
            if (end == -1) end = data.length();
        }

        return null;
    }

    public String get(String key) {
        for (String[] keyValue : this) if (keyValue[0].equals(key)) return keyValue[1];
        return "";
    }

    public void put(String key, Object value) {
        int[] range = indexOfKey(key);
        if (range != null) {
            data = StringUtils.replace(data, range[0], range[1], key+"="+value);
        }
        else data = (!data.isEmpty() ? data+"," : "")+key+"="+value;
    }

    ///
    public String get(String key1, String key2) {
        if (this.data.isEmpty())
            return key2;
        for (String[] arrayOfString : this) {
            if (arrayOfString[0].equals(key1))
                return arrayOfString[1];
        }
        return key2;
    }

    ///
    public int getInt(String str1, int int1) {
        try {
            str1 = get(str1);
            str1 = str1.replaceAll("\\D", "");
            if (!str1.isEmpty()) {
                int1 = Integer.parseInt(str1);
            }
            return int1;
        } catch (NumberFormatException numberFormatException) {
            return int1;
        }
    }

    public boolean isEmpty() {
        return this.data.isEmpty();
    }

    public boolean getBoolean(String paramString) {
        return getBoolean(paramString, false);
    }

    public boolean getBoolean(String paramString, boolean paramBoolean) {
        paramString = get(paramString);
        if (!paramString.isEmpty()) {
            if (paramString.equals("1") || paramString.equals("t") || paramString.equals("true"))
                return true;
            paramBoolean = false;
        }
        return paramBoolean;
    }

    public float getFloat(String key, float fallback) {
        String value = get(key);
        try {
            if (!value.isEmpty()) return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            // Ignore exception and return fallback
        }
        return fallback;
    }

    @NonNull
    @Override
    public Iterator<String[]> iterator() {
        final int[] start = {0};
        final int[] end = {data.indexOf(",")};
        final String[] item = new String[2];
        return new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return start[0] < end[0];
            }

            @Override
            public String[] next() {
                int index = data.indexOf("=", start[0]);
                item[0] = data.substring(start[0], index);
                item[1] = data.substring(index+1, end[0]);
                start[0] = end[0]+1;
                end[0] = data.indexOf(",", start[0]);
                if (end[0] == -1) end[0] = data.length();
                return item;
            }
        };
    }

    @NonNull
    @Override
    public String toString() {
        return data;
    }
}
