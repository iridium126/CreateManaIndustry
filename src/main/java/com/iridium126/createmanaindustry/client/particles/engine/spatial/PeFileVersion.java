package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the Windows VERSIONINFO resource without loading the DLL or hashing it. */
public final class PeFileVersion {
    private PeFileVersion() {}

    public static String read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    static String read(byte[] bytes) throws IOException {
        try {
            var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if(b.getShort(0)!=(short)0x5a4d)throw new IOException("Not a Windows module");
            int pe=b.getInt(0x3c);
            if(b.getInt(pe)!=0x4550)throw new IOException("Invalid PE header");
            int sections=Short.toUnsignedInt(b.getShort(pe+6));
            int optional=pe+24,magic=Short.toUnsignedInt(b.getShort(optional));
            int directories=switch(magic){case 0x20b -> optional+112;case 0x10b -> optional+96;default -> throw new IOException("Invalid PE optional header");};
            int table=optional+Short.toUnsignedInt(b.getShort(pe+20));
            int resource=rva(b,b.getInt(directories+16),table,sections);
            int entry=resourceEntry(b,resource,resource,16);
            entry=resourceEntry(b,resource,entry,-1);
            entry=resourceEntry(b,resource,entry,-1);
            int version=rva(b,b.getInt(entry),table,sections);
            int length=Short.toUnsignedInt(b.getShort(version));
            if(length<52||version+(long)length>bytes.length)throw new IOException("Invalid VERSIONINFO length");
            String key="VS_VERSION_INFO";
            for(int i=0;i<key.length();i++)if(b.getChar(version+6+i*2)!=key.charAt(i))throw new IOException("Invalid VERSIONINFO key");
            if(b.getChar(version+6+key.length()*2)!=0)throw new IOException("Invalid VERSIONINFO terminator");
            int fixed=(version+6+(key.length()+1)*2+3)&~3;
            if(fixed+52>version+length||b.getInt(fixed)!=0xfeef04bd)throw new IOException("Missing fixed file version");
            int ms=b.getInt(fixed+8),ls=b.getInt(fixed+12);
            return (ms>>>16)+"."+(ms&65535)+"."+(ls>>>16)+"."+(ls&65535);
        } catch(IndexOutOfBoundsException|IllegalArgumentException|ArithmeticException failure) {
            throw new IOException("Malformed Windows module version",failure);
        }
    }

    private static int resourceEntry(ByteBuffer b,int root,int directory,int id) throws IOException {
        int count=Short.toUnsignedInt(b.getShort(directory+12))+Short.toUnsignedInt(b.getShort(directory+14));
        for(int i=0;i<count;i++){
            int at=directory+16+i*8,name=b.getInt(at),value=b.getInt(at+4);
            if(id<0||(name&0x80000000)==0&&name==id)return Math.addExact(root,value&0x7fffffff);
        }
        throw new IOException("Windows module has no version resource");
    }

    private static int rva(ByteBuffer b,int address,int table,int sections) throws IOException {
        long wanted=Integer.toUnsignedLong(address);
        for(int i=0;i<sections;i++){
            int at=table+i*40;
            long base=Integer.toUnsignedLong(b.getInt(at+12)),size=Integer.toUnsignedLong(b.getInt(at+16));
            if(wanted>=base&&wanted-base<size){
                long offset=Integer.toUnsignedLong(b.getInt(at+20))+wanted-base;
                if(offset>b.limit()-4L)break;
                return (int)offset;
            }
        }
        throw new IOException("Windows module resource lies outside file");
    }
}
