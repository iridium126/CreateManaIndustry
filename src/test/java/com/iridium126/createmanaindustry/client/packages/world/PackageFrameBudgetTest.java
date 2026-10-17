package com.iridium126.createmanaindustry.client.packages.world;


import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PackageFrameBudgetTest {
    @Test void catchupTicksShareCaptureAllowanceAndReserveUpload(){
        var budget=new PackageFrameBudget();long spent=0;
        for(int tick=0;tick<40;tick++){long available=budget.capture(10_000);long used=Math.min(available,1000);budget.consumed(used);spent+=used;}
        assertEquals(5000,spent);assertEquals(0,budget.capture(10_000));assertEquals(5000,budget.upload(10_000));
        budget.nextFrame();assertEquals(5000,budget.capture(10_000));assertEquals(10_000,budget.upload(10_000));
    }
    @Test void indivisibleOverrunAndSmallerConfigurationCannotProduceNegativeBudgets(){
        var budget=new PackageFrameBudget();budget.consumed(12_000);assertEquals(0,budget.capture(10_000));assertEquals(0,budget.upload(10_000));
        budget.nextFrame();budget.consumed(6000);assertEquals(0,budget.capture(4000));assertEquals(0,budget.upload(4000));
    }
}
