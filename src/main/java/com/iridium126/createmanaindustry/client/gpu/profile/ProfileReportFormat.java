package com.iridium126.createmanaindustry.client.gpu.profile;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** On-demand chat formatting; never used by the sampling path. */
public final class ProfileReportFormat {
    private ProfileReportFormat() {}

    public static String bytes(double value) {
        if(value<1024)return String.format(Locale.ROOT,"%.0f B",value);
        if(value<1024*1024)return String.format(Locale.ROOT,"%.1f KiB",value/1024);
        return String.format(Locale.ROOT,"%.1f MiB",value/(1024*1024));
    }

    public static String slowest(Map<String,Double> millis,int limit) {
        if(limit<1)throw new IllegalArgumentException("Timing report limit");
        if(millis.isEmpty())return "pending";
        String entries=millis.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).limit(limit)
                .map(e->String.format(Locale.ROOT,"%s=%.3f",e.getKey(),e.getValue()))
                .collect(Collectors.joining(", "));
        return entries+(millis.size()>limit?" (+"+(millis.size()-limit)+" stages)":"");
    }

    /** Full stage list, four entries per chat line, without a raw Map dump. */
    public static String timings(Map<String,Double> millis) {
        if(millis.isEmpty())return "pending";
        var entries=millis.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).toList();
        var out=new StringBuilder();
        for(int i=0;i<entries.size();i++){
            if(i>0)out.append(i%4==0?"\n  ":", ");
            var entry=entries.get(i);out.append(String.format(Locale.ROOT,"%s=%.3f",entry.getKey(),entry.getValue()));
        }
        return out.toString();
    }

    public static String namedValues(String[] names,float[] values) {
        if(names.length!=values.length)throw new IllegalArgumentException("Diagnostic field names");
        var out=new java.util.StringJoiner(", ");
        for(int i=0;i<names.length;i++)if(values[i]!=0)out.add(String.format(Locale.ROOT,"%s=%.2f",names[i],values[i]));
        return out.toString();
    }
}
