package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import net.minecraft.resources.ResourceLocation;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackagePacket;

class PackageTransitionInboxTest {
    @Test void environmentAckBurstCanDrainIndependentlyOfAdmissionQuota(){
        var inbox=new PackageTransitionInbox();for(int i=0;i<512;i++){
            inbox.offer(packet(ClientboundPackagePacket.OFFER,i,1,0));inbox.offer(packet(ClientboundPackagePacket.ENVIRONMENT_ACK,i,1,40));
        }
        for(int i=0;i<512;i++)assertEquals(ClientboundPackagePacket.ENVIRONMENT_ACK,inbox.pollAcknowledgement().action());
        assertNull(inbox.pollAcknowledgement());assertEquals(512,inbox.size());
        for(int i=0;i<512;i++)assertEquals(ClientboundPackagePacket.OFFER,inbox.removeFirst().action());assertTrue(inbox.isEmpty());
    }
    private ClientboundPackagePacket packet(int action,int index,long lease,long serial){
        var baseline=new PackageAuthorityRegion.Baseline(index,new PackageLease.Identity(index+1,1),lease,1,
                new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(0,0,0,0,0,0,0),0));
        return new ClientboundPackagePacket(action,ResourceLocation.parse("minecraft:overworld"),new PackageRegion(0,0,0),1,1,serial,baseline,-1,new UUID(0,index),ResourceLocation.parse("create:cardboard"),1,1);
    }
    @Test void repeatedEnvironmentAcksAreCumulativeAndLifecycleSpecific(){
        var inbox=new PackageTransitionInbox();for(int n=1;n<=10000;n++)assertTrue(inbox.offer(packet(ClientboundPackagePacket.ENVIRONMENT_ACK,0,1,n)));
        assertEquals(1,inbox.size());inbox.offer(packet(ClientboundPackagePacket.ENVIRONMENT_ACK,0,1,3));
        inbox.offer(packet(ClientboundPackagePacket.ENVIRONMENT_ACK,0,2,1));assertEquals(2,inbox.size());
        assertEquals(10000,inbox.removeFirst().sequence());assertEquals(2,inbox.removeFirst().baseline().leaseEpoch());assertTrue(inbox.isEmpty());
    }
    @Test void overloadedAdmissionsPreserveRoomForExistingLifecycleAndDoNotDropControls(){
        var inbox=new PackageTransitionInbox();for(int n=0;n<512;n++)assertTrue(inbox.offer(packet(ClientboundPackagePacket.OFFER,n,1,0)));
        assertFalse(inbox.offer(packet(ClientboundPackagePacket.OFFER,513,1,0)));
        for(int n=0;n<512;n++)assertTrue(inbox.offer(packet(ClientboundPackagePacket.FINAL_BASELINE,n,1,0)));
        assertFalse(inbox.offer(packet(ClientboundPackagePacket.ACTIVE,513,1,0)));
        for(int n=0;n<1024;n++)assertTrue(inbox.offer(packet(ClientboundPackagePacket.ENVIRONMENT_ACK,n,1,1)));
        assertEquals(2048,inbox.size());assertTrue(inbox.offer(packet(ClientboundPackagePacket.ENVIRONMENT_ACK,1025,1,1)));assertEquals(2048,inbox.size());
        int controls=0,acks=0;while(!inbox.isEmpty()){var packet=inbox.removeFirst();if(packet.action()==ClientboundPackagePacket.ENVIRONMENT_ACK)acks++;else controls++;if(acks+controls<=1024)assertTrue(Math.abs(acks-controls)<=1);}
        assertEquals(1024,controls);assertEquals(1024,acks);
    }
}
