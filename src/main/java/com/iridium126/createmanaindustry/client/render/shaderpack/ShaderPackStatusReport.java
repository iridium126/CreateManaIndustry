package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.*;

/** Immutable command snapshot. Formatting never loads sources, compiles or polls GPU queries. */
public record ShaderPackStatusReport(List<Section> sections) {
    public enum State {
        ACTIVE("active","§a"), INACTIVE("inactive","§7"), OFF("off","§7"),
        PENDING("pending","§e"), FALLBACK("fallback","§c"), UNAVAILABLE("unavailable","§c");
        final String label,color;
        State(String label,String color){this.label=label;this.color=color;}
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
    public List<String> lines(boolean details) {
        var lines=new ArrayList<String>();lines.add("§b[CMI shaderpack]§r"+(details?" details":""));
        for(var section:sections){
            lines.add("§7"+section.name()+": "+section.state().color+section.state().label+"§r"
                    +(section.path().isEmpty()?"":" | "+section.path())
                    +(section.reason().isEmpty()?"":" | "+section.reason()));
            if(details)for(var field:section.fields())if(!field.value().isEmpty())
                lines.add("§7  "+field.name()+": §r"+field.value());
        }
        return List.copyOf(lines);
    }
    public static Field field(String name,Object value){return new Field(name,Objects.toString(value,""));}
    public static String buffer(int index){return index<0?"none":"colortex"+index;}
    public static String milliseconds(boolean sampled,double value){return sampled?String.format(Locale.ROOT,"%.3f ms",value):"unmeasured";}
    private static String clean(String value){return Objects.requireNonNullElse(value,"").replace('\r',' ').replace('\n',' ').strip();}
}
