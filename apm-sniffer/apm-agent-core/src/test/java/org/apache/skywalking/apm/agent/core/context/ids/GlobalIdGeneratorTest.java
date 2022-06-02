package org.apache.skywalking.apm.agent.core.context.ids;

import org.junit.Test;

public class GlobalIdGeneratorTest {

    @Test
    public void generate() {
        for(int i = 0; i < 10001; i++) {
            String id = GlobalIdGenerator.generate();
            System.out.println(id);
        }
    }
}
