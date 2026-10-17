package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Protocol 15: consecutive journal records sharing one authority envelope.
 * Decode the entire bounded packet before allowing any world transaction. */
public final class PackageEnvironmentBatchCodec {
    public static final int RECORD_BYTES=1024,MAX_RECORDS=24,MAX_BYTES=RECORD_BYTES*MAX_RECORDS;

    public static boolean validLength(int bytes) {
        return bytes>0&&bytes<=MAX_BYTES&&bytes%RECORD_BYTES==0;
    }

    public static List<PackageEnvironmentEvent> decode(ByteBuffer input) {
        if(!validLength(input.remaining()))throw new IllegalArgumentException("Environment batch length");
        if(input.remaining()==RECORD_BYTES)return List.of(PackageEnvironmentEvent.decode(input));
        var source=input.slice();
        var events=new ArrayList<PackageEnvironmentEvent>(source.remaining()/RECORD_BYTES);
        while(source.hasRemaining()) {
            events.add(PackageEnvironmentEvent.decode(source.slice(source.position(),RECORD_BYTES)));
            source.position(source.position()+RECORD_BYTES);
        }
        return List.copyOf(events);
    }

    private PackageEnvironmentBatchCodec(){}
}
