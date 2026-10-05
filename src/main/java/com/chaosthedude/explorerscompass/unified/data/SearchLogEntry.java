package com.chaosthedude.explorerscompass.unified.data;

import com.google.gson.Gson;
import java.util.Map;

/** 不可变事件快照；JSON 编码由写入线程执行。 */
public record SearchLogEntry(Map<String,Object> fields) {
    private static final Gson JSON=new Gson();
    public SearchLogEntry {fields=Map.copyOf(fields);}
    public String json() {return JSON.toJson(fields);}
}
