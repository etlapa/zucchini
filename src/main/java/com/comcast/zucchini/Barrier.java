package com.comcast.zucchini;

/**
 * This creates a barrier sync when using the Zucchini framework.
 *
 * This must be active under an AbstractZucchiniTest.
 *
 * @author Andrew Benton
 */
public class Barrier {
    /**
     * Creates a barrier sync that will wait until all {@see TestContext}'s join or fail.
     */
    public static int sync() {
        return Barrier.sync(-1);
    }

    /**
     * Creates a barrier sync that will wait until `milliseconds` after the first TestContext hits the barrier or all {@see TestContext}'s join or fail.
     */
    public static int sync(int milliseconds) {
        TestContext tc = TestContext.getCurrent();

        if(tc == null) return -1;

        AbstractZucchiniTest azt = tc.getParentTest();

        return azt.flexBarrier.await(milliseconds);
    }
}
