package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Immutable offline boundary of q²-.5. All radial intervals are retained, including tiny gaps. */
public final class JuliaShape {
    public final float[] segments;
    private final double frontRadius;
    public final double entranceScale;
    public record ProjectionStyle(float weak,float gain) {}
    private JuliaShape(float[] segments,double frontRadius,double entranceScale) {
        this.segments=segments;this.frontRadius=frontRadius;this.entranceScale=entranceScale;
    }
    private static InputStream open(String name) throws IOException {
        String path="data/createmanaindustry/"+name;
        var stream=JuliaShape.class.getClassLoader().getResourceAsStream(path);
        if(stream==null)throw new FileNotFoundException("Missing bundled Julia data: "+path);
        return stream;
    }
    public static JuliaShape load() throws IOException {
        float[] segments;
        try(var input=new DataInputStream(open("quadratic-c050.bin"))) {
            if(input.readInt()!=0x434d494a)throw new IOException("Julia boundary magic");
            int count=input.readInt();if(count<1||count>100_000)throw new IOException("Julia boundary size");
            segments=new float[count*4];for(int i=0;i<segments.length;i++) {segments[i]=input.readFloat();if(!Float.isFinite(segments[i]))throw new IOException("Julia boundary value");}
        }
        try(var input=open("quadratic-c050-fit.json")) {
            var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            double front=json.get("front_inscribed_radius").getAsDouble(),entrance=json.get("entrance_scale").getAsDouble();
            if(!Double.isFinite(front)||front<=0||!Double.isFinite(entrance)||entrance<=0)throw new IOException("Invalid Julia projection fit");
            return new JuliaShape(segments,front,entrance);
        }
    }
    /** Object-centred weak perspective, with a continuous return to physical perspective near the entrance. */
    public static ProjectionStyle projection(double distance) {
        double a=Math.clamp((distance-128)/128,0,1),weak=a*a*(3-2*a);
        if(weak==0)return new ProjectionStyle(0,1);
        // Preserve the apparent cone of the contained 64-block entrance sphere as depth is removed.
        double ratio=64/distance;
        return new ProjectionStyle((float)weak,(float)(1/Math.sqrt(1-ratio*ratio*weak*(2-weak))));
    }
    public boolean referenceValid(double radius,double stormY,double planeY) { return Double.isFinite(radius)&&radius>0&&stormY>64&&planeY>=320; }
    public static double referenceScale(double radius,double stormY,double planeY,double frontRadius) {
        double distance=planeY-64;
        return distance*(1.2*radius/(stormY-64))/frontRadius/projection(distance).gain()*1.01;
    }
    public double scale(double radius,double stormY,double planeY,float age) {
        double target=entranceScale;
        if(referenceValid(radius,stormY,planeY))target=Math.max(target,referenceScale(radius,stormY,planeY,frontRadius));
        double a=Math.clamp(age/2.0,0,1);a=a*a*(3-2*a);
        return entranceScale+(target-entranceScale)*a;
    }
}
