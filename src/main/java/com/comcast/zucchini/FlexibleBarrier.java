/**
 * Copyright 2014 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.comcast.zucchini;

import java.util.Collections;
import java.util.Set;

import java.lang.IllegalStateException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.Phaser;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import static com.comcast.zucchini.ZucchiniUtils.tcname;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * This is the heavy lifter behind the Barrier class.
 *
 * This integrates with the AbstractTestContext, the ZucchiniRuntime, and the TestContext to track when and what threads have failed, as well as kill the threads that should timeout.  The barrier will dynamically resize itself in accordance with the number of threads that can successfully finish.
 *
 * @author Andrew Benton
 */
class FlexibleBarrier {
    private static final Logger LOGGER = LogManager.getLogger(FlexibleBarrier.class);

    private AbstractZucchiniTest azt;
    private Phaser primary;
    private Phaser secondary;
    private Set<TestContext> arrivedThreads;
    private int primaryOrder;
    private boolean timedout;
    private int secondaryOrder;
    private int size;

    /**
     * Create a FlexibleBarrier and size it based on the number on contexts in <code>azt</code>.
     */
    FlexibleBarrier(AbstractZucchiniTest azt) {
        this(azt, azt.contexts.size());
    }

    /**
     * Create a FlexibleBarrier and size it based on <code>size</code> regardless of what is in <code>azt</code>.
     *
     * Future resizing is based on the contents of <code>azt</code>
     */
    FlexibleBarrier(AbstractZucchiniTest azt, int size) {
        this.azt = azt;
        this.size = size;
        this.primary = new Phaser(this.size);
        this.secondary = new Phaser(this.size);
        this.arrivedThreads = Collections.newSetFromMap(new ConcurrentHashMap<TestContext, Boolean>());
        this.timedout = false;
        this.primaryOrder = 0;
        this.secondaryOrder = 0;
    }

    /**
     * Kill all threads that have not reached this point and release all waiting threads.
     */
    void unlock() {
        //force all late tests to fail
        for(TestContext tc : this.azt.contexts) {
            //if the thread has not arrived or already been registered as failed, register it as failed, and stop it

            if(!this.arrivedThreads.contains(tc)) { //if it didn't successfully meet the barrier
                if(!this.azt.failedContexts.contains(tc)) { //and it wasn't already registered as failed
                    synchronized(azt.failedContexts) { //lock the contexts
                        if(!this.azt.failedContexts.contains(tc)) { //test again for race condition prevention
                            this.azt.failedContexts.add(tc);
                            this.dec();
                            if(tc.canKill) {
                                tc.getThread().stop();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Drive the arrival index of the primary barrier.
     */
    private synchronized int arrivePrimary() {
        return this.primaryOrder++;
    }

    /**
     * Drive the arrival index of the secondary barrier.
     */
    private synchronized int arriveSecondary() {
        return this.secondaryOrder++;
    }

    /**
     * Await until all {@link TestContexts} have reached this point or failed.
     */
    int await() {
        return this.await(-1);
    }

    /**
     * Await until all {@link TestContext}'s have reached this point, failed, or timedout.
     */
    int await(int milliseconds) {
        if(milliseconds == 0) //we aren't waiting, return no positionnal data
            return -1;

        TestContext tc = TestContext.getCurrent();

        if(this.azt.failedContexts.contains(tc)) {
            LOGGER.debug("Failed context, {}, that has continued has been terminated.", tc);
            Thread.currentThread().stop();
        }

        synchronized(this) {
            LOGGER.trace("registered {}", tcname());
            this.arrivedThreads.add(TestContext.getCurrent());
        }

        //clear thread interrupt
        Thread.interrupted();

        int phase = this.primary.arrive();

        long milli = getMonoMilliseconds() + milliseconds;

        if(milliseconds < 0)
            this.primary.awaitAdvance(phase);
        else {
            //if it's getting interrupted exceptions and it hasn't actually timedout, then ignore them
            while(true) {
                try {
                    this.primary.awaitAdvanceInterruptibly(
                            phase,
                            (milli - getMonoMilliseconds()),
                            TimeUnit.MILLISECONDS);
                    break;
                }
                catch(InterruptedException iex) {
                    if(timedout)
                        break;
                }
                catch(TimeoutException tex) {
                    if(!timedout) {
                        synchronized(this) {
                            if(!timedout) {
                                timedout = true;

                                this.unlock();
                            }
                        }
                    }
                    break;
                }
            }
        }

        int ret = this.arrivePrimary();

        //last one to release does the reset
        if(1 == (this.arrivedThreads.size() - ret)) {
            this.secondaryOrder = 0;
            this.unlock();
            this.timedout = false;
        }

        //secondary barrier to prevent overrun
        this.secondary.arriveAndAwaitAdvance();

        if(0 == this.arriveSecondary()) {
            this.primaryOrder = 0;
        }

        LOGGER.debug("free {} as order {}", tcname(), ret);

        return ret;
    }

    /**
     * Decrements the number of parties that the current barrier is waiting for, and decreases the future number of parties to wait for.
     *
     * This is indicative of a party (Context) having crashed on the scenario and being irrecoverable.
     */
    void dec() {
        this.primary.arriveAndDeregister();
        this.secondary.arriveAndDeregister();
    }

    /**
     * Reset the FlexibleBarrier for the next intercept in the scenario.
     */
    synchronized void reset() {
        this.arrivedThreads.clear();
        this.timedout = false;
        this.primary = new Phaser(this.size);
        this.secondary = new Phaser(this.size);
        this.primaryOrder = 0;
        this.secondaryOrder = 0;
    }

    /**
     * Reset the number of parties for the barrier back to full and removes arrived threads.
     */
    synchronized void refresh() {
        this.reset();
        this.primary.bulkRegister(this.azt.contexts.size() - this.primary.getRegisteredParties());
        this.secondary.bulkRegister(this.azt.contexts.size() - this.secondary.getRegisteredParties());
    }

    /**
     * Get the monotonic time in milliseconds.
     */
    private static long getMonoMilliseconds() {
        return System.nanoTime() / (1_000_000);
    }
}
