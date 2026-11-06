import java.util.HashMap;
import java.util.Map;

/** Argument parser shared by the current offscreen particle harnesses. */
final class ParticleFixtureOptions {
    private ParticleFixtureOptions() {}

    static Map<String,String> parse(String[] args){
        var result=new HashMap<String,String>();
        for(String arg:args){
            if(!arg.startsWith("--"))throw new IllegalArgumentException("Expected an option: "+arg);
            int separator=arg.indexOf('=');
            String key=separator<0?arg.substring(2):arg.substring(2,separator);
            String value=separator<0?"true":arg.substring(separator+1);
            if(result.putIfAbsent(key,value)!=null)throw new IllegalArgumentException("Duplicate option: "+key);
        }
        return result;
    }
}
