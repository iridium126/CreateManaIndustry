import com.iridium126.createmanaindustry.dimension.gen.markov.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Actual generated voxels, not an artist's impression. Run with model, seed, output directory. */
public class RedwoodPreview {
    static final int[] COLORS = {0,0x453b30,0x705039,0x997653,0x3f6040,0x29432d,
        0x618046,0x65563c,0x577244,0x4b6535,0x809557,0x504331,0x344e36,0x977b91,0x705039};
    static BufferedImage render(RedwoodVolume v, int ax,int ay,int az,int bx,int by,int bz) {
        int width=bx-ax+by-ay+4, height=bz-az+(bx-ax+by-ay)/2+4;
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        int[] depths=new int[width*height]; Arrays.fill(depths,Integer.MIN_VALUE);
        for(int z=az;z<bz;z++) for(int y=ay;y<by;y++) for(int x=ax;x<bx;x++) {
            int material=v.get(x,y,z); if(material==0) continue;
            int u=x-ax-(y-ay)+(by-ay)+1, w=bz-1-z+(x-ax+y-ay)/2+1;
            int depth=x+y+z*2, i=u+w*width;
            if(depth<depths[i]) continue;
            depths[i]=depth;
            float light=v.get(x,y,z+1)==0?1.12f:v.get(x+1,y,z)==0?.84f:.63f;
            int rgb=COLORS[material],r=Math.min(255,(int)((rgb>>16&255)*light));
            int g=Math.min(255,(int)((rgb>>8&255)*light)),b=Math.min(255,(int)((rgb&255)*light));
            image.setRGB(u,w,r<<16|g<<8|b);
        }
        return image;
    }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[2]); Files.createDirectories(output);
        EpicRedwoodModel model;
        try(var xml=Files.newInputStream(Path.of(args[0]))) { model=new EpicRedwoodModel(xml); }
        var volume=model.generate(Integer.parseInt(args[1]),(stage,grid)->{
            try {
                var digest=MessageDigest.getInstance("SHA-256");
                grid.writeTo(new DigestOutputStream(OutputStream.nullOutputStream(),digest));
                System.out.println(stage+" sha256="+HexFormat.of().formatHex(digest.digest()));
            } catch(Exception e) { throw new RuntimeException(e); }
        });
        long[] counts=new long[EpicRedwoodModel.VALUES.length()];
        int[] vineBins=new int[41*41*24];
        var xz=new BufferedImage(volume.x,volume.z,BufferedImage.TYPE_BYTE_BINARY);
        var yz=new BufferedImage(volume.y,volume.z,BufferedImage.TYPE_BYTE_BINARY);
        int[] lo={volume.x,volume.y,volume.z},hi={-1,-1,-1};
        volume.writeTo(new OutputStream(){
            int y,z;
            public void write(int v) { throw new UnsupportedOperationException(); }
            public void write(byte[] row,int from,int length) {
                for(int x=0;x<length;x++) {
                    int v=row[from+x];counts[v]++;
                    if(v==0) continue;
                    if(v==7) vineBins[x/16+y/16*41+z/128*41*41]++;
                    xz.setRGB(x,volume.z-1-z,0xffffff);yz.setRGB(y,volume.z-1-z,0xffffff);
                    lo[0]=Math.min(lo[0],x);lo[1]=Math.min(lo[1],y);lo[2]=Math.min(lo[2],z);
                    hi[0]=Math.max(hi[0],x);hi[1]=Math.max(hi[1],y);hi[2]=Math.max(hi[2],z);
                }
                if(++y==volume.y) {y=0;z++;}
            }
        });
        ImageIO.write(xz,"png",output.resolve("xz.png").toFile());
        ImageIO.write(yz,"png",output.resolve("yz.png").toFile());
        ImageIO.write(render(volume,0,0,0,648,648,3072),"png",output.resolve("tree.png").toFile());
        // Fixed world-coordinate windows permit honest before/after comparisons.
        ImageIO.write(render(volume,400,280,1640,496,376,1768),"png",output.resolve("needles.png").toFile());
        int best=0;
        for(int i=1;i<vineBins.length;i++) if(vineBins[i]>vineBins[best]) best=i;
        int vx=best%41*16,vy=best/41%41*16,vz=best/(41*41)*128;
        ImageIO.write(render(volume,vx-8,vy-8,vz,vx+24,vy+24,vz+128),"png",output.resolve("vines.png").toFile());
        ImageIO.write(render(volume,432,312,1700,464,344,1732),"png",output.resolve("spray.png").toFile());
        System.out.println("Vine detail origin="+vx+","+vy+","+vz);
        String report="seed="+args[1]+"\nvalues="+EpicRedwoodModel.VALUES+"\ncounts="+Arrays.toString(counts)
            +"\nmin="+Arrays.toString(lo)+"\nmax="+Arrays.toString(hi)+"\nallocatedBytes="+volume.allocatedBytes()+"\n";
        Files.writeString(output.resolve("metrics.txt"),report);System.out.print(report);
    }
}
