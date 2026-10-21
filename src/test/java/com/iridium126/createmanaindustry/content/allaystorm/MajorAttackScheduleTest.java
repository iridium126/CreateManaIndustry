package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MajorAttackScheduleTest {
 @Test void independentClocksConsumeFailedAndSkippedOpportunities(){var s=new MajorAttackSchedule();assertFalse(s.due(2,100,30));assertFalse(s.due(1,100,30));assertFalse(s.due(2,699,30));assertTrue(s.due(2,700,30));assertTrue(s.due(1,700,30));assertFalse(s.due(2,700,30));assertTrue(s.due(2,5000,30));assertFalse(s.due(2,5000,30));assertEquals(List.of(0,1,2,0),java.util.stream.IntStream.range(0,4).map(i->s.first()).boxed().toList());}
 @Test void opportunitiesRotateEvenWhenIntervalsAreMultiplesOfThree(){var s=new MajorAttackSchedule();s.due(2,0,30);s.due(1,0,30);for(int i=1;i<=6;i++){assertTrue(s.ready(i*600));assertEquals((i-1)%3,s.first());assertTrue(s.due(2,i*600,30));assertTrue(s.due(1,i*600,30));assertFalse(s.ready(i*600));}for(int i=0;i<6;i++){assertEquals(i%3,s.target(2,3));assertEquals(i%3,s.target(1,3));}}
}
