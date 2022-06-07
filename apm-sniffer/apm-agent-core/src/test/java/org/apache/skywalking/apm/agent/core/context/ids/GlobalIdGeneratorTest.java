package org.apache.skywalking.apm.agent.core.context.ids;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class GlobalIdGeneratorTest {

    @Test
    public void generate() {

        new Thread(()-> {
            GlobalIdGenerator.generate();
        }).start();

        new Thread(()-> {
            GlobalIdGenerator.generate();
        }).start();

        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

      /*  for(int i = 0; i < 10001; i++) {
            String id = GlobalIdGenerator.generate();
            System.out.println(id);
        }*/
    }
}
