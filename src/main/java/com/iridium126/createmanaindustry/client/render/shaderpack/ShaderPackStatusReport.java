package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.*;

/** Immutable command snapshot. Formatting never loads sources, compiles or polls GPU queries. */
public record ShaderPackStatusReport(List<Section> sections) {
    public enum State {
        ACTIVE("ACTIVE"), INACTIVE("INACTIVE"), OFF("OFF"),
        PENDING("PENDING"), FALLBACK("FALLBACK"), UNAVAILABLE("UNAVAILABLE");
        final String label;
        State(String label){this.label=label;}
    }
    public record Field(String name,String value) {
        public Field { name=clean(name);value=clean(value); }
    }
    public record Section(String name,State state,String path,String reason,List<Field> fields) {
        public Section {name=clean(name);Objects.requireNonNull(state);path=clean(path);reason=clean(reason);fields=List.copyOf(fields);}
        public Section(String name,State state,String path,String reason,Field... fields){this(name,state,path,reason,List.of(fields));}
    }
    private static final List<String> ORDER=List.of("Pack","MODEL","World","Sky/Cloud","Packages","Hex","Probe");
    public ShaderPackStatusReport {
        var copy=new ArrayList<>(sections);copy.sort(Comparator.comparingInt(s->ORDER.indexOf(s.name())));sections=List.copyOf(copy);
    }
    /** Plain, structured lines; the heading and colours come from CMICommandOutput. */
    public List<String> lines(boolean details) {
        var lines=new ArrayList<String>();
        for(var section:sections){
            var row=new StringBuilder(section.name()).append(" : ").append(section.state().label);
            if(!section.path().isEmpty())row.append("  |  path : ").append(section.path());
            if(!section.reason().isEmpty())row.append("  |  reason : ").append(section.reason());
            lines.add(row.toString());
            if(details)for(var field:section.fields())if(!field.value().isEmpty())
                lines.add("  "+field.name()+" : "+field.value());
        }
        return List.copyOf(lines);
    }
    public static Field field(String name,Object value){return new Field(name,Objects.toString(value,""));}
    public static String buffer(int index){return index<0?"none":"colortex"+index;}
    public static String milliseconds(boolean sampled,double value){return sampled?String.format(Locale.ROOT,"%.3f ms",value):"unmeasured";}
    private static String clean(String value){return Objects.requireNonNullElse(value,"").replace('\r',' ').replace('\n',' ').strip();}
}
