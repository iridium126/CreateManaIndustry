package com.iridium126.createmanaindustry.client.allaystorm;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Section;
class StormCollisionDemandTest {
    @Test void aRetiredAttackKeepsCorpseCollisionUntilTheDeathWindowEnds(){
        var leases=new StormCollisionDemand();var body=new Section(0,8,0);var attack=new Section(8,4,0);
        leases.update(Set.of(body,attack),100);assertEquals(Set.of(body,attack),leases.update(Set.of(body),101));
        assertFalse(leases.due(120));assertEquals(Set.of(body,attack),leases.update(Set.of(body),120));
        assertTrue(leases.due(121));assertEquals(Set.of(body),leases.update(Set.of(body),121));
    }
    @Test void reactivatedCoverageCannotBeExpiredByAnOldLease(){
        var leases=new StormCollisionDemand();var key=new Section(-2,5000,-4);
        leases.update(Set.of(key),0);leases.update(Set.of(),1);leases.update(Set.of(key),10);
        assertFalse(leases.due(21));assertEquals(Set.of(key),leases.update(Set.of(key),100));
        assertEquals(Set.of(key),leases.update(Set.of(),101));assertEquals(Set.of(),leases.update(Set.of(),121));
    }
}
